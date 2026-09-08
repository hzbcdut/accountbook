package nt.ddeoid.accountbook.security.lock

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 进程级 lifecycle 观察器(Q3/D 实现)。
 *
 * ## 为什么用 ProcessLifecycleOwner 而不是 Activity 的 onResume/onPause
 *
 * Activity 自己的 onResume/onPause 在配置变更(旋转屏幕、暗黑模式切换、深色 / 浅色
 * 切换)时会**成对**触发,如果用 activity 的 callback 驱动锁屏,旋转一下就锁了 —— 完全
 * 错。`ProcessLifecycleOwner` 只在整个 app 进入 / 离开前台时触发,跟用户对"我离开
 * 了 app"的语义对齐。
 *
 * ## 触发的两条路径
 *
 * - [onStart] → `lockController.onAppForegrounded()` → 检查超时,超过就锁
 * - [onStop] → `lockController.onAppBackgrounded()` → 记录当前时间戳
 *
 * 锁屏成功后用户回到 app 看到的 LockScreen —— [RootNavHost] 由 [LockController.state]
 * 驱动,所以这条线是自洽的。
 *
 * ## scope
 *
 * 进程级 scope(`SupervisorJob() + Dispatchers.IO`),单次 [ProcessLifecycleOwner.addObserver]
 * 后跟进程同生共死。**不**用 Hilt 的 `@ApplicationScope`,因为这个观察器的生命周期
 * 比 Hilt 短(只有 app 进程前台期),用本地 scope 直观一些。
 */
@Singleton
class AppLifecycleObserver @Inject constructor(
    private val lockController: LockController,
) : DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 在 [AccountBookApp.onCreate] 里调一次。 */
    fun register() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        Log.i(TAG, "AppLifecycleObserver 注册到 ProcessLifecycleOwner")
    }

    override fun onStart(owner: LifecycleOwner) {
        scope.launch {
            try {
                lockController.onAppForegrounded()
            } catch (t: Throwable) {
                // bootstrap 还没完成时这里也会跑(state 还是 NeedsSetup,onAppForegrounded
                // 内部 short-circuit 直接 return,这里只是兜底)
                Log.w(TAG, "onAppForegrounded 异常", t)
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        scope.launch {
            try {
                lockController.onAppBackgrounded()
            } catch (t: Throwable) {
                Log.w(TAG, "onAppBackgrounded 异常", t)
            }
        }
    }

    private companion object {
        const val TAG = "AppLifecycleObserver"
    }
}
