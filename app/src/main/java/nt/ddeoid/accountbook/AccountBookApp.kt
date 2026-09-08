package nt.ddeoid.accountbook

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import nt.ddeoid.accountbook.data.local.DatabaseBootstrap
import javax.inject.Inject

/**
 * 应用入口。
 *
 * - [HiltAndroidApp] 触发 Hilt 组件图生成。
 * - **必须显式 `System.loadLibrary("sqlcipher")`** —— sqlcipher-android 4.6.1 移除了
 *   `SQLiteDatabase.loadLibs(Context)` 和 ReLinker 自动加载,需要应用自己 load `libsqlcipher.so`。
 *   不显式 load 会导致 Room 的 InvalidationTracker 在抢在前面触发 nativeOpen → UnsatisfiedLinkError。
 *
 * ## 启动时的开库
 *
 * 走 [DatabaseBootstrap] 而不是直接播种:开库是 IO 活(SQLCipher 打开 + 密钥验证),
 * 播种必须在库打开**之后**。两者都在 application scope 的 IO 协程里做,不阻塞主线程。
 *
 * Phase 4 接上 LockController 之后,这里会先问一句"应用锁启用了吗":
 * 未启用 → 照现在这样用设备上的旧口令开库;已启用 → 什么都不做,等用户在锁屏上
 * 用 PIN / 生物识别 / 助记词把 master key 解出来。
 */
@HiltAndroidApp
class AccountBookApp : Application() {

    @Inject
    lateinit var databaseBootstrap: DatabaseBootstrap

    /** 与进程同生共死的 scope;开库/播种这类启动任务挂在这里。 */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        try {
            System.loadLibrary("sqlcipher")
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e("AccountBookApp", "loadLibrary('sqlcipher') failed", e)
            throw e
        }
        applicationScope.launch {
            runCatching { databaseBootstrap.openWithLegacyKey() }
                .onFailure { android.util.Log.e("AccountBookApp", "启动开库失败", it) }
        }
    }
}
