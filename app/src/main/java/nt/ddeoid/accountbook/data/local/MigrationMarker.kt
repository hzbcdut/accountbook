package nt.ddeoid.accountbook.data.local

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v0.3.0 → v0.4.0 迁移的"进程中断保护"标记。
 *
 * ## 为什么单独一个标记
 *
 * 迁移流程里有一步要对磁盘做**不可逆**操作 —— SQLCipher 的 [PRAGMA rekey]。如果这一步
 * 成功之后、KeyVault 写完之前进程被杀,DB 已经用新 key 加密但 vault 还没写。下次启动
 * [LegacyKeyMigrator] 想重试,但旧口令打不开文件,迁移就会卡死。
 *
 * 用一个**应用锁状态机外部**的标记位来检测这种情况:
 *
 * - rekey **之前**写 `inProgress = true`
 * - migrate **完成之后**(不论成功失败)写 `inProgress = false`
 * - 下次启动读:如果 `inProgress == true`,说明上次迁移被打断 —— UI 应该引导用户
 *   "从备份恢复",而不是试着再走一次迁移。
 *
 * ## 为什么不用 EncryptedSharedPreferences
 *
 * 这是状态机标记,跟密钥无关。万一加密层出问题(Keystore 损坏、用户改设备 PIN),
 * 这一位还得能读出来。所以用普通 SharedPreferences。
 */
@Singleton
class MigrationMarker @Inject constructor(
    private val prefs: SharedPreferences,
) {

    /**
     * 迁移是否处于"进行中"状态 —— 也就是已经走过 rekey 但还没走到最后清标记。
     *
     * 调用方 [LockController.bootstrap] 据此分发 [LockState.Migrating]:正常迁移走 wizard,
     * 中断的迁移走"恢复备份"。
     */
    var inProgress: Boolean
        get() = prefs.getBoolean(KEY_IN_PROGRESS, false)
        set(value) {
            prefs.edit().putBoolean(KEY_IN_PROGRESS, value).apply()
        }

    /**
     * 测试用的清空入口 —— 生产代码不应该调它,只用 `inProgress = false`。
     */
    fun reset() {
        prefs.edit().clear().apply()
    }

    /**
     * 生产代码用的 Hilt 入口 —— 把 `@ApplicationContext` 转成 SharedPreferences。
     * 拆出来是为了让 [MigrationMarker] 本身在 JVM 单测里能跑(测试直接传 in-memory prefs)。
     */
    class Factory @Inject constructor(
        @ApplicationContext private val context: Context,
    ) {
        fun create(): SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private companion object {
        const val PREFS_NAME = "accountbook_migration_marker"
        const val KEY_IN_PROGRESS = "in_progress"
    }
}
