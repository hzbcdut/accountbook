package nt.ddeoid.accountbook

import android.os.Bundle
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
import dagger.hilt.android.AndroidEntryPoint
import nt.ddeoid.accountbook.security.lock.LockController
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
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var lockController: LockController

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
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
    }
}