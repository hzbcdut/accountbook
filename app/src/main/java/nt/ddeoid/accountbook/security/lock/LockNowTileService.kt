package nt.ddeoid.accountbook.security.lock

import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import nt.ddeoid.accountbook.MainActivity
import nt.ddeoid.accountbook.R
import nt.ddeoid.accountbook.security.lock.LockShortcutAction

/**
 * "立即锁定 AccountBook" 的 Quick Settings Tile(Q3/D 实现)。
 *
 * ## 行为
 *
 * - 用户点 tile → 调 [MainActivity] 起来 + 携带 `action=LOCK` 标识 →
 *   MainActivity.onCreate / onNewIntent 调 `LockController.lock()`
 * - 长按 tile → 启动 MainActivity(打开 app)
 *
 * ## Tile 状态更新
 *
 * TileService 没有 Hilt 注入 —— 通过 [EntryPointAccessors] 从 applicationContext 拿
 * LockController(本服务本身也是 Hilt-aware 的入口点,但这样更直接)。
 *
 * `LockController.state` 反映 LockController 状态。Tile 在:
 * - Unlocked: ACTIVE state(可点,点击会锁)
 * - Locked / NeedsSetup / Migrating / Disabled: UN AVAILABLE state(灰着,点了无效)
 *
 * 当前 LockController 状态变了 → [requestListeningState] 触发 onStartListening 再
 * 更新。这里我们不在锁屏完成时去更新 tile,因为 lock 是被 tile 自己触发的,
 * [LockController.lock] 会把 state 推到 Locked,然后 app 进 LockScreen(同时 tile
 * 也被 tile 框架重新组合)。
 *
 * ## Android 7.0 之前不接
 *
 * TileService API 从 API 24 (Android 7.0) 引入,但 [TileService.requestListeningState]
 * 等方法需要 API 28+ 才能把状态同步给 QS panel。本服务的 build.gradle 已经限定了
 * minSdk ≥ 24,足够 TileService 存在;TileService 实体的 manifest 注册在
 * AndroidManifest 里用 `<service android:permission="android.permission.BIND_QUICK_SETTINGS_TILE">`。
 *
 * ## 不在 onClick 里直接 lock()
 *
 * TileService 可以直接调 LockController.lock(),但那样会绕过 MainActivity,
 * LockScreen 显示不到。统一走 MainActivity(intent.LOCK) 这条路,行为跟 long-press
 * icon shortcut 一致(Q3+D "lock now" 单一入口)。
 */
class LockNowTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onClick() {
        super.onClick()
        // 启动 MainActivity 并附带 LOCK intent action —— 复用 #32 接好的那一条路径
        val intent = Intent(this, MainActivity::class.java).apply {
            action = LockShortcutAction.ACTION_LOCK_NOW
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        try {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "Tile 点击启动 MainActivity 失败", t)
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        // Tile 进入面板可见区域时被调 —— 同步一次当前 LockController 状态
        try {
            val lockController = EntryPointAccessors.fromApplication(
                applicationContext,
                LockControllerEntryPoint::class.java,
            ).lockController()
            scope.launch {
                val state = lockController.state.value
                applyState(state)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Tile 状态同步失败(bootstrap 未完成?)", t)
            // 兜底:active,等下次 onStartListening 再校准
            qsTile?.state = Tile.STATE_ACTIVE
        }
    }

    private fun applyState(state: LockController.LockState) {
        val tile = qsTile ?: return
        tile.label = getString(R.string.tile_lock_label)
        tile.contentDescription = getString(R.string.tile_lock_description)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Tile icon 用 launcher foreground —— 跟 short-cut 复用
            tile.icon = Icon.createWithResource(this, R.drawable.ic_launcher_foreground)
        }
        tile.state = when (state) {
            is LockController.LockState.Unlocked -> Tile.STATE_ACTIVE
            // 其他状态都不可点 —— 锁了再锁是 no-op,但应用锁未启用时点 tile 是没意义的
            else -> Tile.STATE_UNAVAILABLE
        }
        tile.updateTile()
    }

    @dagger.hilt.EntryPoint
    @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
    interface LockControllerEntryPoint {
        fun lockController(): LockController
    }

    private companion object {
        const val TAG = "LockNowTileService"
    }
}
