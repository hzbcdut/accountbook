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

/**
 * 单 Activity 多 Composable 导航。
 *
 * 路由:
 * - `home`                              主界面
 * - `account_detail/{accountId}`         详情页
 *
 * Phase 3+ 会加 `account_edit/{id}` / `settings` / `lock` 等。
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
    }
}

object Routes {
    const val HOME = "home"
    const val ARG_ACCOUNT_ID = "accountId"
    private const val ACCOUNT_DETAIL_BASE = "account_detail"
    const val ACCOUNT_DETAIL = "$ACCOUNT_DETAIL_BASE/{$ARG_ACCOUNT_ID}"
    fun detail(id: String): String = "$ACCOUNT_DETAIL_BASE/$id"
}
