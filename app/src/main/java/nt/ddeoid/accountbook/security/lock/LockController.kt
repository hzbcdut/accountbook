package nt.ddeoid.accountbook.security.lock

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nt.ddeoid.accountbook.data.local.DatabasePassphraseProvider
import nt.ddeoid.accountbook.data.local.DatabaseProvider
import nt.ddeoid.accountbook.data.local.MigrationMarker
import nt.ddeoid.accountbook.security.crypto.MasterKeyFactory
import nt.ddeoid.accountbook.security.crypto.MnemonicCodec
import nt.ddeoid.accountbook.security.crypto.SecretBytes
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用锁的**状态机**。
 *
 * 这是 Q3/D(超时锁 + 手动锁 + 快捷锁)、Q4/C(关库 + 抹键)、Q14/B(锁定时 NavHost 不组合)
 * 这三条决议的**唯一交汇点**。其他模块(SetupWizard、LockScreen、Tile、Shortcut)都只
 * 通过这里观察 / 推动状态。
 *
 * ## 状态机
 *
 * ```
 *                    initialize()
 * NeedsSetup ─────────────────────────► Unlocked (vault+db open)
 *      ▲                                       │
 *      │ wipe() / disableLock()                │ lock()
 *      └──────────── Locked ◄──────────────────┘
 *                       │
 *                       │ unlockWith*()
 *                       ▼
 *                   Unlocked
 * ```
 *
 * - `NeedsSetup`:第一次启动,什么都没初始化。SetupWizard 走完后跳到 `Unlocked`。
 * - `Locked`:KeyVault 初始化过但 master key 不在内存里,DB 关着。需要解锁才能跳走。
 * - `Unlocked`:master key 在内存里(只在本对象持有,作为 [activeHandle]),DB 开着。
 *
 * ## 锁定时谁负责抹键
 *
 * 主密钥句柄(熵)在本类的 [activeHandle] 字段里 —— [LockController.lock] 会 wipe 它。
 * **注意**:SQLCipher 的 native 侧会复制一份到自己的 cipher context,这一层 Java
 * 抹不到 —— 这是公开已知的限制,跟 SecretBytes 文档里写的一致。
 *
 * ## timeout 触发
 *
 * [onAppBackgrounded] 只记录时间(不立刻锁,因为安卓不一定进 paused 后就立刻杀进程)。
 * [onAppForegrounded] 才检查"距离上次进后台过了多久",**超过了就调 [lock]**。
 * 这个语义跟系统 `onStop/onStart` 对齐,而不是更激进。
 */
@Singleton
class LockController @Inject constructor(
    private val databaseProvider: DatabaseProvider,
    private val keyVault: KeyVault,
    private val lockPrefs: LockPrefs,
    private val mnemonicCodec: MnemonicCodec,
    private val passphraseProvider: DatabasePassphraseProvider,
    private val migrationMarker: MigrationMarker,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {

    private val _state = MutableStateFlow<LockState>(LockState.NeedsSetup)
    val state: StateFlow<LockState> = _state.asStateFlow()

    /** 当前持有的 master key 句柄;[LockState.Unlocked] 时非 null。 */
    private var activeHandle: KeyVault.MasterKeyHandle? = null

    /**
     * 串行化所有改状态的入口 —— 这是个**有副作用的状态机**,不是纯函数。任何两个
     * `unlock*()` 并发都可能让数据库被打两次,所以用 mutex 串起来。
     */
    private val transition = Mutex()

    /**
     * 启动时调用一次:根据 KeyVault / Legacy passphrase / MigrationMarker 决定初始状态。
     *
     * 三态分发(Q4 + Phase 4 #30):
     *
     * - **Migrating**:`migrationMarker.inProgress == true`(上次迁移中断)**或**
     *   `!isInitialized() && passphraseProvider.hasLegacy()`(v0.3.0 升级)。
     *   走迁移 wizard 或恢复备份引导。
     * - **NeedsSetup**:全新设备,既没 vault 也没 legacy。走 SetupWizard。
     * - **Locked**:KeyVault 已初始化(说明已经走过 setup 或迁移)。需要解锁。
     */
    suspend fun bootstrap() = withContext(Dispatchers.IO) {
        val initialized = keyVault.isInitialized()
        val hasLegacy = passphraseProvider.hasLegacy()
        val migrationInterrupted = migrationMarker.inProgress
        val target = when {
            migrationInterrupted -> LockState.Migrating
            !initialized && hasLegacy -> LockState.Migrating
            !initialized -> LockState.NeedsSetup
            else -> LockState.Locked
        }
        _state.value = target
        // 把 prefs 里残留的"上次进后台时间"清掉 —— 启动永远从 Locked 开始
        lockPrefs.clearBackgroundMarker()
    }

    /**
     * 完成 SetupWizard 后的写操作:
     * 1. 把 [handle] 缓存到 [activeHandle]
     * 2. 用它派生 master key 并打开 DB
     * 3. 把 state 推到 Unlocked
     * 4. 写 prefs.lockEnabled
     */
    suspend fun finishSetup(
        handle: KeyVault.MasterKeyHandle,
        lockEnabled: Boolean,
        timeout: LockPrefs.TimeoutTier = LockPrefs.TimeoutTier.IMMEDIATE,
    ) = withContext(Dispatchers.IO) {
        transition.withLock {
            check(_state.value == LockState.NeedsSetup) { "不能在 NeedsSetup 之外调 finishSetup: state=${_state.value}" }
            openDatabaseWith(handle)
            activeHandle = handle
            lockPrefs.setLockEnabled(lockEnabled)
            lockPrefs.setTimeout(timeout)
            lockPrefs.clearBackgroundMarker()
            _state.value = LockState.Unlocked
        }
    }

    /**
     * 完成迁移 wizard 后的写操作。
     *
     * 区别于 [finishSetup]:KeyVault 已经在 [nt.ddeoid.accountbook.data.local.LegacyKeyMigrator]
     * 里通过 `initializeWithExistingEntropy` 初始化过了。这里只负责:
     * 1. 用 [handle] 派生 master key 并打开 DB(此时 DB 已经用新 key 加密)
     * 2. 缓存 handle
     * 3. 写 prefs
     * 4. 推状态到 Unlocked
     *
     * @param handle 由 [nt.ddeoid.accountbook.data.local.LegacyKeyMigrator] 提供的 master key
     *   句柄 —— 它持有迁移时新生成的熵。
     */
    suspend fun finishMigration(
        handle: KeyVault.MasterKeyHandle,
        lockEnabled: Boolean,
        timeout: LockPrefs.TimeoutTier = LockPrefs.TimeoutTier.IMMEDIATE,
    ) = withContext(Dispatchers.IO) {
        transition.withLock {
            check(_state.value == LockState.Migrating) {
                "不能在 Migrating 之外调 finishMigration: state=${_state.value}"
            }
            openDatabaseWithOrWipe(handle)
            activeHandle = handle
            lockPrefs.setLockEnabled(lockEnabled)
            lockPrefs.setTimeout(timeout)
            lockPrefs.clearBackgroundMarker()
            _state.value = LockState.Unlocked
        }
    }

    /**
     * 启用应用锁路径下的"PIN 解锁"。
     *
     * @return 解锁成功时返回 [KeyVault.MasterKeyHandle],**调用方不必再 wipe** —— 本方法在
     *   内部 open DB 之后会 wipe;若 open 失败(handle 已无主),调用方负责 wipe。
     */
    suspend fun unlockWithPin(pin: CharArray): Result<Unit> = withContext(Dispatchers.IO) {
        transition.withLock {
            if (_state.value == LockState.Unlocked) return@withLock Result.success(Unit)
            check(_state.value == LockState.Locked) { "在 ${_state.value} 状态下不能 unlock" }
            val handle = try {
                keyVault.unlockWithPin(pin)
            } catch (t: Throwable) {
                return@withLock Result.failure(t)
            }
            openDatabaseWithOrWipe(handle)
            activeHandle = handle
            lockPrefs.clearBackgroundMarker()
            _state.value = LockState.Unlocked
            Result.success(Unit)
        }
    }

    /** 生物识别解锁。[secretKey] 由 BiometricPrompt 的 `CryptoObject` 提供 —— Keystore 自己认证。 */
    suspend fun unlockWithBiometric(): Result<Unit> = withContext(Dispatchers.IO) {
        transition.withLock {
            if (_state.value == LockState.Unlocked) return@withLock Result.success(Unit)
            check(_state.value == LockState.Locked) { "在 ${_state.value} 状态下不能 unlock" }
            val handle = try {
                keyVault.unlockWithBiometric()
            } catch (t: Throwable) {
                return@withLock Result.failure(t)
            }
            openDatabaseWithOrWipe(handle)
            activeHandle = handle
            lockPrefs.clearBackgroundMarker()
            _state.value = LockState.Unlocked
            Result.success(Unit)
        }
    }

    /**
     * 助记词恢复。这条路径**绕过 KeyVault**:助记词本身就是熵(Q10=A),直接走
     * [KeyVault.recoverFromMnemonic]。
     *
     * 解锁成功会把熵持久化到 KeyVault,但**不**自动启用生物识别 —— 用户需要后续在
     * Settings 里主动重 enroll。
     */
    suspend fun unlockWithMnemonic(words: List<String>): Result<Unit> = withContext(Dispatchers.IO) {
        transition.withLock {
            if (_state.value == LockState.Unlocked) return@withLock Result.success(Unit)
            check(_state.value == LockState.Locked) { "在 ${_state.value} 状态下不能 unlock" }
            val handle = try {
                keyVault.recoverFromMnemonic(words, mnemonicCodec)
            } catch (t: Throwable) {
                return@withLock Result.failure(t)
            }
            openDatabaseWithOrWipe(handle)
            activeHandle = handle
            lockPrefs.clearBackgroundMarker()
            _state.value = LockState.Unlocked
            Result.success(Unit)
        }
    }

    /** 立刻锁定:Q4=C 的"关库 + 抹键 + 状态置 Locked"。幂等。 */
    suspend fun lock() = withContext(Dispatchers.IO) {
        transition.withLock {
            wipeActiveHandle()
            databaseProvider.close()
            _state.value = LockState.Locked
        }
    }

    /**
     * 应用进入后台时调(Q3=D 的"timeout 起点"):只记录时间,不立刻锁。
     * 这样设计是因为:用户按 home 键只是想切个应用,几秒后就回来 —— 立刻锁反而
     * 烦人。真正的判定在 [onAppForegrounded]。
     */
    suspend fun onAppBackgrounded() = withContext(Dispatchers.IO) {
        val prefs = lockPrefs.snapshot()
        if (!prefs.lockEnabled) return@withContext
        if (_state.value != LockState.Unlocked) return@withContext
        lockPrefs.markBackgroundedAt(System.currentTimeMillis())
    }

    /**
     * 应用回到前台时调:如果超过 timeoutMs 就锁。
     */
    suspend fun onAppForegrounded() = withContext(Dispatchers.IO) {
        val prefs = lockPrefs.snapshot()
        if (!prefs.lockEnabled) return@withContext
        if (_state.value != LockState.Unlocked) return@withContext
        val bgAt = prefs.lastBackgroundedAt
        if (bgAt == 0L) return@withContext
        val elapsed = System.currentTimeMillis() - bgAt
        if (elapsed >= prefs.timeoutMs) {
            lock()
        }
    }

    /**
     * 用户从 Settings 里**主动禁用**应用锁。区别于 [lock]:这里还要清 KeyVault。
     *
     * 禁用完之后的状态是 Locked 且 [LockPrefs.snapshot.lockEnabled] == false —— 由
     * [LockController] 自身保证。下次启动 [bootstrap] 会回到 NeedsSetup(因为 vault 已擦)。
     */
    suspend fun disableLock() = withContext(Dispatchers.IO) {
        transition.withLock {
            wipeActiveHandle()
            databaseProvider.close()
            keyVault.wipe()
            lockPrefs.wipeRuntimeState()
            _state.value = LockState.NeedsSetup
        }
    }

    /** 在 [finishSetup] / [unlockWith*] 成功后把 DB 打开。失败时把 [handle] 一起擦掉。 */
    private fun openDatabaseWithOrWipe(handle: KeyVault.MasterKeyHandle) {
        try {
            openDatabaseWith(handle)
        } catch (t: Throwable) {
            handle.wipe()
            throw t
        }
    }

    private fun openDatabaseWith(handle: KeyVault.MasterKeyHandle) {
        // master key 派生 → SQLCipher 口令。SQLCipher 自己的副本会留在 native 层,
        // 这里只能保证 JVM 这一侧立刻 wipe。
        SecretBytes(MasterKeyFactory.fromEntropy(handle.entropy).bytes).use { derived ->
            // 复制一份独立的 SecretBytes 给 DatabaseProvider.open:open 内部会 wipe 自己的副本,
            // 不能让它 wipe 我们马上还要用的 derived。
            val copy = SecretBytes(derived.bytes.copyOf())
            try {
                databaseProvider.open(copy)
            } finally {
                copy.wipe()
            }
        }
    }

    private fun wipeActiveHandle() {
        activeHandle?.wipe()
        activeHandle = null
    }

    /**
     * 设置变化(用户在 Settings 里调了 timeout)时调,只读一次 prefs 让下次
     * [onAppForegrounded] 用新档位判断 timeout。
     */
    suspend fun refreshFromPrefs() {
        // 目前没有缓存,所有读都是直取 DataStore。这里留作未来加缓存时挂 invalidation。
        // No-op.
    }

    /** 暴露给 UI 观察的状态。四态枚举,显式 sealed(不可 null)。 */
    sealed interface LockState {
        data object NeedsSetup : LockState
        /** v0.3.0 升级用户:走迁移 wizard,或者迁移被打断 → 引导恢复备份。 */
        data object Migrating : LockState
        data object Locked : LockState
        data object Unlocked : LockState
    }
}
