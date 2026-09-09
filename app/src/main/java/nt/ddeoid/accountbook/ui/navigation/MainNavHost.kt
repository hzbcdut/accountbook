package nt.ddeoid.accountbook.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * 主 app 的内层 NavHost(Q14=B 双 NavHost 架构的**内层**)。
 *
 * 外层 [RootNavHost] 在锁定 / SetupWizard / Recovery 阶段会**完全绕过这个 Composable**
 * (通过 `popUpTo(graph) { inclusive = true }` 把它从组合树里拆掉)。所以 back 键在锁
 * 定状态下不会带回 Home —— 直接退出 app。
 *
 * 名字叫 "Main" 是因为它就是"主 app 那块":只在 [nt.ddeoid.accountbook.security.lock.LockState.Unlocked]
 * 和 [nt.ddeoid.accountbook.security.lock.LockState.Disabled] 两个状态下才被组合。
 *
 * 路由:
 * - `home`                              主界面
 * - `account_detail/{accountId}`         详情页
 * - `settings`                          设置(导出 / 导入 / 主题 / 关于)
 *
 * Phase 4+ 会在 Settings 里加 `account_edit/{id}`。
 */
@Composable
fun MainNavHost(
    navController: NavHostController = rememberNavController(),
) {
    // Bug #40:详情页 FAB → 回主页打开编辑 BottomSheet 的桥。
    // null → id → null 三态循环:Detail 写 id → Home effect 消费后写回 null。
    var pendingEditId by remember { mutableStateOf<String?>(null) }

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onNavigateToDetail = { id ->
                    navController.navigate(Routes.detail(id))
                },
                onNavigateToSettings = {
                    navController.navigate(Routes.SETTINGS)
                },
                pendingEditId = pendingEditId,
                onPendingEditConsumed = { pendingEditId = null },
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
                onEditRequested = { accountId ->
                    // ⚠️ 先写 state 再 pop:如果先 pop,下一帧 Home 重组时 effect
                    // 可能看到过期的 null 而错过这次编辑意图。
                    pendingEditId = accountId
                    navController.popBackStack()
                },
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
