package nt.ddeoid.accountbook.security.lock

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import nt.ddeoid.accountbook.security.crypto.SecretBytes
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用锁相关的**设置状态**。
 *
 * 注意这个类的语义和 [KeyVault] 不一样:KeyVault 关心"钥匙在不在、哪条路径能开",
 * LockPrefs 关心"用户**希望**锁什么时候启用、超时多久、上次离开是什么时候"。
 * 前者加密存,后者明文存(Q13=D:DataStore for settings)。
 *
 * ## 三件事
 *
 * 1. **是否启用应用锁**:Q2=C 默认开启,允许跳过。SetupWizard 完成后必须写一次;
 *    没写就视为"未启用"。
 * 2. **超时档位**:Q3=D + Q18=B 决定 4 档固定值。
 * 3. **上次进入后台的时间**:由 [LockController] 在 `onPause` 时写入,
 *    `onResume` 时读出来判断是否超时。
 *
 * ## DataStore + 阻塞读
 *
 * [snapshot] 是阻塞读,只能在 IO/后台线程调;UI 层用 [observe]。
 */
@Singleton
class LockPrefs @Inject constructor(
    private val context: Context,
) {

    private val store: DataStore<Preferences> = context.lockPrefsStore

    val observe: Flow<Snapshot> = store.data.map { it.toSnapshot() }

    suspend fun snapshot(): Snapshot = store.data.first().toSnapshot()

    suspend fun setLockEnabled(enabled: Boolean) {
        store.edit { it[KEY_LOCK_ENABLED] = enabled }
    }

    /**
     * 用户是否走完过 wizard(无论选 lock 还是 skip)。
     *
     * 用来在 [LockController.bootstrap] 里区分:
     * - 全新设备:wizardCompleted=false → 走 NeedsSetup
     * - 用户曾经 skip 过:wizardCompleted=true 且 lockEnabled=false → 走 Disabled
     *
     * 不写这一个标志的话,默认 `lockEnabled=false` 会让全新设备直接进 Disabled,绕过 wizard。
     */
    suspend fun setWizardCompleted(completed: Boolean) {
        store.edit { it[KEY_WIZARD_COMPLETED] = completed }
    }

    suspend fun setTimeout(tier: TimeoutTier) {
        store.edit { it[KEY_TIMEOUT_MS] = tier.millis }
    }

    /** 由 [LockController] 在 `onPause` 时调。 */
    suspend fun markBackgroundedAt(epochMs: Long) {
        store.edit { it[KEY_LAST_BACKGROUNDED_AT] = epochMs }
    }

    /** 由 [LockController] 在解锁成功后调,避免下一次回前台立刻又锁。 */
    suspend fun clearBackgroundMarker() {
        store.edit { it.remove(KEY_LAST_BACKGROUNDED_AT) }
    }

    /**
     * 一份"瞬时"的设置快照。
     *
     * @param lockEnabled 用户**当前**是否启用了应用锁。false 时 LockController 完全
     *   不应该试图锁定 —— 但 [KeyVault.isInitialized] 为 true 时也仍然持有熵,
     *   留作"之后重新启用"用。
     * @param wizardCompleted 用户是否走完过 SetupWizard(无论选 lock 还是 skip)。
     *   与 [lockEnabled] 配合判断 bootstrap 时的目标状态。
     */
    data class Snapshot(
        val lockEnabled: Boolean,
        val wizardCompleted: Boolean,
        val timeoutMs: Long,
        val lastBackgroundedAt: Long,
    ) {
        companion object {
            /** [LockController] 初始化时 DataStore 还没值时用的默认档。 */
            val DEFAULT = Snapshot(
                lockEnabled = false,
                wizardCompleted = false,
                timeoutMs = TimeoutTier.IMMEDIATE.millis,
                lastBackgroundedAt = 0L,
            )
        }
    }

    /**
     * Q18=B:固定 4 档,不开放自由输入。
     *
     * 4 档而不是更多 / 更少,是因为这正好覆盖两种**典型使用模式**:
     * - "离开座位就锁" → IMMEDIATE
     * - "放下手机接个电话" → SHORT
     * - "看一眼信息然后接着用" → MEDIUM
     * - "我希望几乎从不被自动锁" → LONG
     */
    enum class TimeoutTier(val millis: Long, val label: String) {
        IMMEDIATE(0L, "立即"),
        SHORT(15_000L, "15 秒"),
        MEDIUM(60_000L, "1 分钟"),
        LONG(300_000L, "5 分钟");

        companion object {
            fun fromMillis(ms: Long): TimeoutTier = entries.firstOrNull { it.millis == ms } ?: IMMEDIATE
        }
    }

    /** 擦除 [LockController] 在 prefs 里写的所有状态(Q3=D 重置)。 */
    suspend fun wipeRuntimeState() {
        store.edit { it.clear() }
    }

    /** 用 [SecretBytes.wipe] 风格的契约擦除 [charArray] —— 给上层调用方一个统一入口。 */
    private fun wipe(chars: CharArray) = chars.fill(0.toChar())

    @Suppress("unused") // 留作未来在 UI 入口处调用
    fun wipePinFromCaller(chars: CharArray) = wipe(chars)

    private fun Preferences.toSnapshot(): Snapshot = Snapshot(
        lockEnabled = this[KEY_LOCK_ENABLED] ?: false,
        wizardCompleted = this[KEY_WIZARD_COMPLETED] ?: false,
        timeoutMs = this[KEY_TIMEOUT_MS] ?: TimeoutTier.IMMEDIATE.millis,
        lastBackgroundedAt = this[KEY_LAST_BACKGROUNDED_AT] ?: 0L,
    )

    private companion object {
        val KEY_LOCK_ENABLED = booleanPreferencesKey("lock_enabled")
        val KEY_WIZARD_COMPLETED = booleanPreferencesKey("wizard_completed")
        val KEY_TIMEOUT_MS = longPreferencesKey("lock_timeout_ms")
        val KEY_LAST_BACKGROUNDED_AT = longPreferencesKey("last_backgrounded_at")

        /** [DataStore] 实例挂在这里做 lazy + 单例,跟官方推荐姿势对齐。 */
        val Context.lockPrefsStore: DataStore<Preferences> by preferencesDataStore(name = "accountbook_lock_prefs")
    }
}
