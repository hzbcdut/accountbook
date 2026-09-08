package nt.ddeoid.accountbook.security.lock

/**
 * 应用锁相关 intent action 的统一入口。
 *
 * 长按图标 shortcut、Quick Settings Tile、第三方启动器等所有"立即锁定"入口
 * 都用同一个 [ACTION_LOCK_NOW]:
 *
 * - `res/xml/shortcuts.xml` 里的 `<intent android:action>`
 * - `LockNowTileService.onClick` 启动 MainActivity
 * - `MainActivity.handleLockShortcut(intent)` 接收并分发
 *
 * 集中在一处避免 action 字符串写错。
 */
object LockShortcutAction {
    const val ACTION_LOCK_NOW = "nt.ddeoid.accountbook.action.LOCK"
}
