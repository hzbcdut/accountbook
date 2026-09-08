package nt.ddeoid.accountbook

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import nt.ddeoid.accountbook.security.clipboard.SensitiveClipboard
import nt.ddeoid.accountbook.security.lock.LockController
import nt.ddeoid.accountbook.security.lock.LockShortcutAction
import nt.ddeoid.accountbook.security.lock.ui.RootNavHost
import nt.ddeoid.accountbook.ui.theme.AccountBookTheme
import nt.ddeoid.accountbook.ui.theme.ThemeViewModel
import javax.inject.Inject

/**
 * App 单 Activity。
 *
 * 基类用 [FragmentActivity] 而不是 [androidx.activity.ComponentActivity]:后续
 * 接入 `androidx.biometric.BiometricPrompt` 时,它作为 DialogFragment 注入,
 * 必须由 `FragmentActivity` 才行 —— `ComponentActivity` 没有 FragmentManager。
 *
 * `setContent` / `enableEdgeToEdge` 在 `FragmentActivity` 上仍然可用(它继承
 * `ComponentActivity`)。
 *
 * 持有 [lockController] 是为了把它传给 [RootNavHost];`bootstrap()` 本身由
 * `AccountBookApp.onCreate` 在 application scope 上调 —— 这样 state 在 Activity
 * 还没 onCreate 之前就已经设置好了,UI 第一次组合就能看到正确目的地。
 *
 * ## FLAG_SECURE(Q14=B / Phase 4 #32)
 *
 * 始终给 window 加 `FLAG_SECURE` —— 锁定状态不希望 task switcher 截到 LockScreen、
 * 主界面也不希望被截图工具截到账号明文。**唯一例外**:某些 OEM 的"分屏"或"投屏"功能
 * 会因为 FLAG_SECURE 黑屏,这是文档化的取舍。
 *
 * ## 长按图标"立即锁定"Shortcut(Q3/D)
 *
 * shortcuts.xml 里的 `<intent android:action="...action.LOCK">` 启动 MainActivity,
 * 在 [onCreate] / [onNewIntent] 里识别这个 action → 调 [LockController.lock]。
 * 因为 launchMode 是 singleTask,Activity 已经在栈顶时只会触发 [onNewIntent] 不会
 * 重建。
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var lockController: LockController

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // FLAG_SECURE:阻止截图、屏幕录制、最近任务快照。Phase 4 #32。
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        enableEdgeToEdge()
        setContent {
            val themeVm: ThemeViewModel = hiltViewModel()
            val themePref by themeVm.preference.collectAsState()
            AccountBookTheme(preference = themePref) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RootNavHost(lockController = lockController)
                }
            }
        }
        handleLockShortcut(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // shortcut 在 Activity 已存在时只走这里
        handleLockShortcut(intent)
    }

    /**
     * 用户离开 app 的瞬间立刻清空敏感剪贴板 —— 比 60s 自动清空更激进,但符合
     * "离开 app = 可能切到别人能看到的场景" 的直觉。SensitiveClipboard 内部
     * 做 label 匹配,如果剪贴板已被用户复制成别的内容(URL / 文字),不会被误清。
     */
    override fun onStop() {
        super.onStop()
        SensitiveClipboard.clearNow(this)
    }

    /**
     * 处理"立即锁定"快捷方式。Activity 已经处于 Locked 状态时是 no-op(`lock()` 是幂等的)。
     */
    private fun handleLockShortcut(intent: Intent?) {
        if (intent?.action != LockShortcutAction.ACTION_LOCK_NOW) return
        Log.i(TAG, "Lock shortcut triggered → lock()")
        lifecycleScope.launch {
            try {
                lockController.lock()
            } catch (t: Throwable) {
                Log.w(TAG, "lock() 异常", t)
            }
        }
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
