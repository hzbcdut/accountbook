package nt.ddeoid.accountbook.data.export

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nt.ddeoid.accountbook.data.local.DatabaseNotOpenException
import nt.ddeoid.accountbook.data.local.DatabaseProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把 SQLCipher 加密的 [DatabaseProvider.databaseFile] 整个复制出去。
 *
 * ## 跟 [BackupExporter](明文 JSON/CSV)的关系
 *
 * | 维度 | 明文 JSON/CSV | 加密 DB 文件 |
 * | --- | --- | --- |
 * | 敏感性 | 明文账号 → 用户确认导出后**自负责任** | 仍是 SQLCipher 密文 → 没有 master key 拿不到 |
 * | 备份粒度 | 适用"换设备、跨平台看" | 适用"原设备 / 同 app 还原" |
 * | 依赖 | 不需要原设备 PIN | 需要原设备的 master key(PIN / 助记词 / 生物识别) |
 * | 用途 | 跨设备迁移、跨平台查看 | 同账号簿 app 跨设备还原完整 DB(含索引、外键、版本) |
 *
 * 两条线互补。明文那条允许"导出后用 Excel 看",加密这条允许"新设备一键还原一切"。
 *
 * ## 安全警告
 *
 * 加密 DB 文件依然是**用户文件** —— 任何拿到这个文件 + 知道 PIN 的人都能开。本类
 * 不会做任何 re-auth,Q7=A 不强制,但导出对话框必须显著提示"这个文件含有你的数据"。
 *
 * ## WAL / SHM 也要带上
 *
 * SQLCipher 用 WAL 模式时,会有 `-wal` 和 `-shm` 兄弟文件。如果只复制 `.db`,
 * 这部分未 checkpoint 的 page 会丢。**拷贝前必须 checkpoint**(调用 `wal_checkpoint(TRUNCATE)`),
 * 让所有 page 写回主文件。
 */
@Singleton
class EncryptedDatabaseBackup @Inject constructor(
    private val databaseProvider: DatabaseProvider,
) {

    /**
     * 默认导出文件名:`AccountBook-db-2026-09-08-183012.db`。
     *
     * 时间戳用本地时区 + 紧凑格式,便于用户肉眼排序。
     */
    fun defaultFileName(): String {
        val ts = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(Date())
        return "AccountBook-db-$ts.db"
    }

    /**
     * 把加密数据库导出到 [targetUri] 指向的位置。
     *
     * 流程:
     * 1. 拿 `databaseFile()` 路径
     * 2. 强制 checkpoint(WAL → 主文件)
     * 3. 用 [ContentResolver] 打开 `targetUri` 的输出流
     * 4. 分块复制(.db → outputStream)
     *
     * @return 写入的字节数;UI 可以用来显示 "已导出 4.3 MB"
     * @throws DatabaseNotOpenException 数据库未开(Locked 状态调用)
     * @throws java.io.IOException 复制过程失败(空间不足 / SAF 写入失败)
     */
    suspend fun exportTo(targetUri: Uri, contentResolver: ContentResolver): Long = withContext(Dispatchers.IO) {
        val src = databaseProvider.databaseFile()
        check(src.exists()) { "数据库文件不存在: ${src.absolutePath}" }
        try {
            checkpointWal(src)
        } catch (t: Throwable) {
            // checkpoint 失败不阻断导出 —— 数据仍可能一致,只是最近的事务可能丢
            Log.w(TAG, "WAL checkpoint 失败,继续导出(可能丢失最近事务)", t)
        }
        val out = contentResolver.openOutputStream(targetUri)
            ?: throw java.io.IOException("无法打开目标 URI 的输出流: $targetUri")
        out.use { dst ->
            src.inputStream().use { input ->
                input.copyTo(dst, bufferSize = COPY_BUFFER_BYTES)
            }
        }
        Log.i(TAG, "DB 加密备份导出完成: ${src.length()} bytes → $targetUri")
        src.length()
    }

    /**
     * 强制把 WAL 内容写回主文件。SQLCipher 4.x 支持 `PRAGMA wal_checkpoint(TRUNCATE)`。
     *
     * 走 raw SQL 因为 Room 的 [androidx.sqlite.db.SupportSQLiteDatabase] 同样暴露
     * `execSQL`,这里调它就行 —— 不需要走 Room 的 DAO。
     */
    private fun checkpointWal(@Suppress("UNUSED_PARAMETER") dbFile: File) {
        val db = databaseProvider.requireDatabase()
        db.openHelper.writableDatabase.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
    }

    private companion object {
        const val TAG = "EncryptedDatabaseBackup"
        const val COPY_BUFFER_BYTES = 64 * 1024
    }
}
