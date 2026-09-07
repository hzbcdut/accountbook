package nt.ddeoid.accountbook.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import nt.ddeoid.accountbook.ui.screens.home.HomeScreen

/**
 * 单 Activity 多 Composable 导航。
 *
 * 路由命名:`screen_name`。Phase 1 只放 HomeScreen 一个目的地,Phase 2+ 会加
 * `account_edit` / `account_detail` / `settings` / `lock` 等。
 */
@Composable
fun AccountBookNavHost(
    navController: NavHostController = rememberNavController(),
) {
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen()
        }
    }
}

object Routes {
    const val HOME = "home"
}