package nt.ddeoid.accountbook.data.local

import android.content.Context
import androidx.room.Room
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import nt.ddeoid.accountbook.data.local.dao.AccountDao
import nt.ddeoid.accountbook.data.local.dao.PlatformCatalogDao
import nt.ddeoid.accountbook.data.local.dao.TagDao
import nt.ddeoid.accountbook.security.crypto.SecretBytes
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 数据库的**可开可关**持有者。
 *
 * 这个类存在的唯一理由就是 Q4=C:"锁定"必须是真锁定 —— 关掉 SQLCipher 连接、
 * 把口令从内存里抹掉,而不只是在 UI 上盖一层。原来的 `DatabaseModule` 把
 * [AppDatabase] 做成 eager `@Singleton` 直接注入,那个结构下库一旦打开就再也关不掉
 * (DAO 实例的生命周期比库还长),所以必须换成这一层。
 *
 * ## 密钥的生命周期
 *
 * [open] 会**复制**一份口令自己持有,调用方可以立刻擦掉自己那份;[close] 时把这份
 * 副本擦掉。也就是说口令在内存里存活的时间 = 库打开的时间,不多不少。
 *
 * ⚠️ 这仍然是尽力而为:SQLCipher 的 native 层会把密钥调度进自己的 cipher context,
 * 那部分 Java 侧擦不到。见 [SecretBytes] 的说明。
 *
 * ## 打开即验证
 *
 * [open] 里会主动碰一次 `openHelper.readableDatabase`,强制 SQLCipher 立刻用这把钥匙
 * 去读文件头。这样"PIN 错了"会在 [open] 当场抛 [net.zetetic.database.DatabaseErrorHandler]
 * 系的异常,而不是等到用户第一次查询时才炸 —— 后者会让解锁界面没法给出反馈。
 */
@Singleton
class DatabaseProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** 当前打开的库;null = 未打开(未启动、已锁定、或正在开)。 */
    private val databaseFlow = MutableStateFlow<AppDatabase?>(null)

    /** 本次打开时持有的口令副本,只在 [close] 里擦除。受 [this] 锁保护。 */
    private var heldPassphrase: SecretBytes? = null

    val isOpen: Boolean get() = databaseFlow.value != null

    /** 库的开关状态,给需要"等它打开"的调用方用。 */
    val openState: StateFlow<AppDatabase?> = databaseFlow.asStateFlow()

    /**
     * 用 [passphrase] 打开数据库。
     *
     * 已经打开就直接返回现有实例(不会用新口令重开 —— 那是 [rekey] / 迁移的职责)。
     * 调用方可以在返回后立刻擦除自己那份 [passphrase]。
     *
     * @throws DatabaseOpenException 口令不对(文件解不开)或底层 IO 失败
     */
    @Synchronized
    fun open(passphrase: SecretBytes): AppDatabase {
        databaseFlow.value?.let { return it }

        val ourCopy = passphrase.copy()
        val db = try {
            val factory = SupportOpenHelperFactory(ourCopy.bytes)
            Room.databaseBuilder(
                context,
                AppDatabase::class.java,
                AppDatabase.DATABASE_NAME,
            )
                .openHelperFactory(factory)
                // 接进 ALL_MIGRATIONS(v0.5.0 引入,后续累积)。如果忘了写某个迁移,
                // 这里的 ADD_MIGRATIONS_CALLBACK 会打 log 提示;而不是默认抛
                // IllegalStateException 让人一头雾水。
                .addMigrations(*ALL_MIGRATIONS)
                .addCallback(ALL_MIGRATIONS_CALLBACK)
                .build()
                .also { built ->
                    // 强制真正打开 + 验证密钥。错了就在这里抛,不要拖到第一次查询。
                    built.openHelper.readableDatabase
                }
        } catch (t: Throwable) {
            ourCopy.wipe()
            throw DatabaseOpenException(t)
        }

        heldPassphrase = ourCopy
        databaseFlow.value = db
        return db
    }

    /**
     * 关库 + 擦除口令。幂等:没打开就什么都不做。
     *
     * 关库失败也要继续擦密钥 —— 那种情况下留着密钥只会让事情更糟。
     */
    @Synchronized
    fun close() {
        val db = databaseFlow.value
        val key = heldPassphrase
        // 先把状态置空,让所有 `deferred` 的收集方立刻停止拿到旧实例,再真正关库。
        databaseFlow.value = null
        heldPassphrase = null

        if (db != null) {
            runCatching { db.close() }
                .onFailure { android.util.Log.w(TAG, "关闭数据库时出错", it) }
        }
        key?.wipe()
    }

    /** 拿到当前打开的库。没打开就抛 —— 这是编程错误,不是可恢复状态。 */
    fun requireDatabase(): AppDatabase =
        databaseFlow.value ?: throw DatabaseNotOpenException()

    /**
     * 挂起直到库打开,然后返回它。
     *
     * 存在的理由:开库是异步的(SQLCipher 打开 + 密钥验证不能放主线程),而 UI 可能在
     * 那之前就开始收集 Flow。让调用方**等**,比让它崩掉好得多 —— 而且这个语义在
     * Phase 4 里正好复用:锁定期间库是关的,Flow 就静静等着,解锁后自动恢复。
     */
    suspend fun awaitDatabase(): AppDatabase = databaseFlow.filterNotNull().first()

    fun databaseOrNull(): AppDatabase? = databaseFlow.value

    // --- DAO 便捷访问 -------------------------------------------------------
    // 每次调用都重新解析,所以拿到的一定是"当前这个"库实例的 DAO,
    // 不会像直接注入那样把一个已经关掉的库的 DAO 长期攥在手里。

    fun accountDao(): AccountDao = requireDatabase().accountDao()
    fun tagDao(): TagDao = requireDatabase().tagDao()
    fun platformCatalogDao(): PlatformCatalogDao = requireDatabase().platformCatalogDao()

    /**
     * 把"取 DAO → 返回 Flow"推迟到**收集时**,并且等库真的打开。
     *
     * ViewModel 常在构造期就 `combine(a.observe(), b.observe())`,那会在构造的瞬间解析
     * DAO。用这个包一层之后:构造期不碰库;订阅时如果库还没开(冷启动正在开、或者当前
     * 处于锁定状态)就挂起等待,开好了再继续。
     *
     * 配合 Q14=B(锁定时整棵 NavHost 不组合),这就同时消掉了两种崩溃:"ViewModel
     * 构造时库还没开"和"库关了 Flow 还在收集"。
     *
     * 注意:库在上游被 [close] 之后,这个 Flow **不会自动重连** —— 它会正常结束。
     * 这是有意的:锁定后 UI 整棵树被拆掉,没有人在收集;解锁后重新组合会创建新的 Flow。
     */
    fun <T> deferred(block: (AppDatabase) -> Flow<T>): Flow<T> = flow {
        emitAll(block(awaitDatabase()))
    }

    /** 数据库文件路径,加密备份(Q6=A)导出时要用。 */
    fun databaseFile(): java.io.File = context.getDatabasePath(AppDatabase.DATABASE_NAME)

    private companion object {
        const val TAG = "DatabaseProvider"
    }
}

/** 库没打开就试图访问。属于状态机没管好的编程错误。 */
class DatabaseNotOpenException :
    IllegalStateException("数据库未打开:调用方必须在解锁之后才访问数据")

/** 打开失败,通常意味着口令不对。 */
class DatabaseOpenException(cause: Throwable) :
    Exception("无法打开加密数据库(口令可能不正确)", cause)
