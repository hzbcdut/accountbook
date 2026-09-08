package nt.ddeoid.accountbook.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import nt.ddeoid.accountbook.ui.screens.detail.AccountDetailScreen
import nt.ddeoid.accountbook.ui.screens.home.HomeScreen
import nt.ddeoid.accountbook.ui.screens.settings.SettingsScreen

/**
 * 单 Activity 多 Composable 导航。
 *
 * 路由:
 * - `home`                              主界面
 * - `account_detail/{accountId}`         详情页
 * - `settings`                          设置(导出 / 导入 / 主题 / 关于)
 *
 * Phase 4+ 会加 `lock` / `account_edit/{id}`。
 */
@Composable
fun AccountBookNavHost(
    navController: NavHostController = rememberNavController(),
) {
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onNavigateToDetail = { id ->
                    navController.navigate(Routes.detail(id))
                },
                onNavigateToSettings = {
                    navController.navigate(Routes.SETTINGS)
                },
            )
        }
        composable(
            route = Routes.ACCOUNT_DETAIL,
            arguments = listOf(
                navArgument(Routes.ARG_ACCOUNT_ID) { type = NavType.StringType },
            ),
        ) {
            AccountDetailScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
            )
        }
    }
}

object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val ARG_ACCOUNT_ID = "accountId"
    private const val ACCOUNT_DETAIL_BASE = "account_detail"
    const val ACCOUNT_DETAIL = "$ACCOUNT_DETAIL_BASE/{$ARG_ACCOUNT_ID}"
    fun detail(id: String): String = "$ACCOUNT_DETAIL_BASE/$id"
}
