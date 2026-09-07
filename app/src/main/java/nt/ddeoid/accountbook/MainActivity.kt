package nt.ddeoid.accountbook

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.navigation.compose.hiltViewModel
import dagger.hilt.android.AndroidEntryPoint
import nt.ddeoid.accountbook.ui.navigation.AccountBookNavHost
import nt.ddeoid.accountbook.ui.theme.AccountBookTheme
import nt.ddeoid.accountbook.ui.theme.ThemeViewModel

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeVm: ThemeViewModel = hiltViewModel()
            val themePref by themeVm.preference.collectAsState()
            AccountBookTheme(preference = themePref) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AccountBookNavHost()
                }
            }
        }
    }
}