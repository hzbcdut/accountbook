package nt.ddeoid.accountbook

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import nt.ddeoid.accountbook.data.local.DatabaseBootstrap
import nt.ddeoid.accountbook.security.lock.LockController
import javax.inject.Inject

/**
 * 应用入口。
 *
 * - [HiltAndroidApp] 触发 Hilt 组件图生成。
 * - **必须显式 `System.loadLibrary("sqlcipher")`** —— sqlcipher-android 4.6.1 移除了
 *   `SQLiteDatabase.loadLibs(Context)` 和 ReLinker 自动加载,需要应用自己 load `libsqlcipher.so`。
 *   不显式 load 会导致 Room 的 InvalidationTracker 在抢在前面触发 nativeOpen → UnsatisfiedLinkError。
 *
 * ## 启动时的两条任务
 *
 * - [DatabaseBootstrap.openWithLegacyKey]:用 v0.3.0 老口令打开 DB(只用于尚未
 *   启用应用锁 / 走迁移 wizard 之前还能看到老数据的情况)。
 * - [LockController.bootstrap]:决定 [LockController.state] 的初始值 —— 这条线
 *   **优先**于 DB 开,因为开不开 DB 要看 state:Locked 状态下 DB 不应该被开,
 *   等用户在锁屏上输入 PIN 后才开。
 *
 * 两者都在 application scope 的 IO 协程里做,不阻塞主线程;state 更新后会通过
 * StateFlow 推到 [MainActivity]。
 */
@HiltAndroidApp
class AccountBookApp : Application() {

    @Inject lateinit var databaseBootstrap: DatabaseBootstrap
    @Inject lateinit var lockController: LockController

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
        // bootstrap 必须先于 DB 开:state 决定了 DB 开不开 —— Locked 状态下 DB 绝不能开。
        applicationScope.launch {
            runCatching { lockController.bootstrap() }
                .onFailure { android.util.Log.e("AccountBookApp", "LockController.bootstrap 失败", it) }
        }
        applicationScope.launch {
            runCatching { databaseBootstrap.openWithLegacyKey() }
                .onFailure { android.util.Log.e("AccountBookApp", "启动开库失败", it) }
        }
    }
}
