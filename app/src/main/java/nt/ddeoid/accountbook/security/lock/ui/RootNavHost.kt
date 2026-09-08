package nt.ddeoid.accountbook.security.lock.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import nt.ddeoid.accountbook.security.lock.LockController
import nt.ddeoid.accountbook.security.lock.LockController.LockState
import nt.ddeoid.accountbook.ui.navigation.MainNavHost

/**
 * 外层 NavHost(Q14=B 双 NavHost 架构的**外层**)。
 *
 * 只装锁相关界面:`SetupWizard` / `MigrationWizard` / `LockScreen` / `Recovery`,
 * 以及主 app 的 [MainNavHost] 作为"已经解锁之后"的容器。
 *
 * ## 为什么需要这一层
 *
 * 当 [LockController.state] 是 Locked / NeedsSetup / Migrating 时,**只有这一层被组合**,
 * [MainNavHost] 整棵被拆掉(由 `popUpTo(graph) { inclusive = true }` + `launchSingleTop`
 * 实现)。结果就是:
 *
 * - 锁定状态下 back 键不会带回 Home(Home 的 nav back-stack 已经被擦掉),而是直接退到
 *   launcher —— 防止"我锁了屏结果 back 一下就绕过去"这种攻击向量。
 * - wizard 在跑的时候 HomeScreen / 数据库状态机全部断电,Compose 不会持有任何对
 *   unlocked-only 数据的引用。
 *
 * ## 路由表
 *
 * | 路由 | 何时被选中 |
 * |------|-------------|
 * | `setup_wizard`     | [LockState.NeedsSetup]      |
 * | `migration_wizard` | [LockState.Migrating]       |
 * | `lock_screen`      | [LockState.Locked]          |
 * | `recovery`         | 由 LockScreen 跳过来(独立 push,不绑 state) |
 * | `main`             | [LockState.Disabled] / [LockState.Unlocked] |
 *
 * ## Recovery 是 push,不是 state
 *
 * Recovery 由 LockScreen 的"忘记 PIN"按钮 `navController.navigate(...)`,**不**参与
 * state→route 的自动跳转。理由:从 LockScreen 切到 Recovery 是用户的**显式选择**,跳回
 * LockScreen 时要保留 LockScreen 那个 back-stack 项。
 *
 * ## 启动竞态
 *
 * `LockController` 的初始 state 是 `NeedsSetup`,bootstrap 真正完成后才会变成 Locked /
 * Disabled / 等。理论上 UI 第一次组合会瞬时看到 SetupWizard,然后 state 更新 → 跳到正确
 * 目的地。bootstrap 在 applicationScope 上跑、几乎瞬时返回,用户察觉不到。如果以后
 * bootstrap 变慢(比如要做云校验之类),这块要加 splash 兜底。
 */
@Composable
fun RootNavHost(
    lockController: LockController,
    navController: NavHostController = rememberNavController(),
    modifier: Modifier = Modifier,
) {
    val state by lockController.state.collectAsState()

    LaunchedEffect(state) {
        val target = when (state) {
            is LockState.NeedsSetup -> RootRoutes.SETUP_WIZARD
            is LockState.Migrating -> RootRoutes.MIGRATION_WIZARD
            is LockState.Locked -> RootRoutes.LOCK_SCREEN
            is LockState.Disabled -> RootRoutes.MAIN
            is LockState.Unlocked -> RootRoutes.MAIN
        }
        if (navController.currentDestination?.route != target) {
            // popUpTo(graph) 把整张图从 back-stack 里擦掉 —— 这是 Q14=B 的关键:
            // 锁定时 HomeScreen / DAO 数据这些组合路径**不存在**,back 键直接退出 app。
            navController.navigate(target) {
                popUpTo(navController.graph.id) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = RootRoutes.SETUP_WIZARD, // bootstrap 完成后 LaunchedEffect 会跳到正确目的地
        modifier = modifier,
    ) {
        composable(RootRoutes.SETUP_WIZARD) { SetupWizardPlaceholder() }
        composable(RootRoutes.MIGRATION_WIZARD) { MigrationWizardPlaceholder() }
        composable(RootRoutes.LOCK_SCREEN) { LockScreenPlaceholder() }
        composable(RootRoutes.RECOVERY) { RecoveryPlaceholder() }
        composable(RootRoutes.MAIN) { MainNavHost() }
    }
}

/**
 * 外层路由表。故意和 [nt.ddeoid.accountbook.ui.navigation.Routes] 用不同前缀,
 * 防止以后外层加新路由时跟内层撞名。
 */
object RootRoutes {
    const val SETUP_WIZARD = "root/setup_wizard"
    const val MIGRATION_WIZARD = "root/migration_wizard"
    const val LOCK_SCREEN = "root/lock_screen"
    const val RECOVERY = "root/recovery"
    const val MAIN = "root/main"
}

// --- 临时占位 composables ------------------------------------------------
//
// 这些会被 Step 6 (LockScreen) / Step 7 (Recovery) / Step 8 (SetupWizard) /
// Step 9 (MigrationWizard) 替换。占位期间保留功能:用户能看到当前 state 的字面值,
// 避免锁定状态下 MainNavHost 错误地加载(后者会因为 DB 没开炸)。
// -------------------------------------------------------------------------

@Composable
private fun SetupWizardPlaceholder() {
    Placeholder("Setup Wizard · 将在 Step 8 替换")
}

@Composable
private fun MigrationWizardPlaceholder() {
    Placeholder("Migration Wizard · 将在 Step 9 替换")
}

@Composable
private fun LockScreenPlaceholder() {
    Placeholder("Lock Screen · 将在 Step 6 替换")
}

@Composable
private fun RecoveryPlaceholder() {
    Placeholder("Recovery Screen · 将在 Step 7 替换")
}

@Composable
private fun Placeholder(label: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}
