package nt.ddeoid.accountbook.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import nt.ddeoid.accountbook.security.crypto.SecretBytes
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 用 SQLCipher 的 [PRAGMA rekey] 把同一个 DB 文件从一把口令换到另一把。
 *
 * 这是 Phase 4 #30(v0.3.0 → v0.4.0 数据迁移)的**核心原语**。它不依赖 Room,
 * 直接走 [SupportOpenHelperFactory] + [androidx.sqlite.db.SupportSQLiteOpenHelper],
 * 因为迁移窗口里我们只想动 native 那一层,不想让 Room 的 schema 校验、InvalidationTracker
 * 之类的旁路介入。
 *
 * ## 原子性
 *
 * SQLCipher 4.x 的 `PRAGMA rekey` 是**事务级**操作:
 *
 * - SQL 返回 0 → 文件已用 newPassphrase 重加密
 * - 抛 [net.zetetic.database.sqlcipher.SQLiteException] → 文件仍用 oldPassphrase(未变)
 *
 * 因此 [rekey] 失败时调用方可以**重试**而不必担心数据半损坏。
 *
 * ## 为什么 PRAGMA 字符串用 lowercase hex
 *
 * `PRAGMA rekey = '<key>'` 的 key 部分是字符串,SQLCipher 内部把它当 hex 解析。
 * 用 lowercase hex 避免单引号转义问题,也跟 SQLCipher 自己的文档示例保持一致。
 * 调用方不需要再做任何编码。
 *
 * ## 输入参数的责任
 *
 * [rekey] **不**擦 [oldPassphrase] / [newPassphrase] —— 两个 [SecretBytes] 都由调用方负责。
 * 这里只负责 SQLCipher 这一层的 IO。
 */
@Singleton
class DatabaseRekeyer @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * 打开 [oldPassphrase] 对应的 DB 文件,执行 `PRAGMA rekey = newPassphrase`,关闭。
     *
     * 调用方必须保证 [oldPassphrase] 真的能打开这个文件 —— 否则会抛
     * [DatabaseOpenException]。如果旧口令是对的,但新 key 出问题导致 rekey 失败,
     * 抛 [DatabaseRekeyException]。
     *
     * @throws DatabaseOpenException 旧口令打不开文件
     * @throws DatabaseRekeyException rekey SQL 失败(几乎不会发生,但保留这一层语义)
     */
    fun rekey(oldPassphrase: SecretBytes, newPassphrase: SecretBytes) {
        val dbFile = context.getDatabasePath(AppDatabase.DATABASE_NAME)
        // SupportOpenHelperFactory 接受 ByteArray —— 不要在外面再包一层 SecretBytes,
        // 否则 native 那一侧会拿到封装对象,这里直接喂底层字节。
        val oldBytes = oldPassphrase.bytes.copyOf()
        val newBytes = newPassphrase.bytes.copyOf()
        try {
            val helper: SupportSQLiteOpenHelper = SupportOpenHelperFactory(oldBytes).create(
                SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name(dbFile.absolutePath)
                    .build(),
            )
            try {
                val db = helper.writableDatabase
                db.execSQL("PRAGMA rekey = '${newBytes.toHexLower()}';")
            } finally {
                runCatching { helper.close() }
                    .onFailure { android.util.Log.w(TAG, "关闭 SQLCipher helper 时出错", it) }
            }
        } catch (t: Throwable) {
            throw when (t) {
                is DatabaseOpenException, is DatabaseRekeyException -> t
                // SupportOpenHelperFactory.create 失败 = 旧口令对不上文件头
                else -> DatabaseOpenException(t)
            }
        } finally {
            oldBytes.fill(0)
            newBytes.fill(0)
        }
    }

    private fun ByteArray.toHexLower(): String =
        joinToString(separator = "") { "%02x".format(it) }

    private companion object {
        const val TAG = "DatabaseRekeyer"
    }
}

/** rekey 调用本身失败。SQLCipher 的 rekey 是事务级的,理论上不会半成品,但保留这一层语义。 */
class DatabaseRekeyException(cause: Throwable) :
    Exception("SQLCipher PRAGMA rekey 失败", cause)
