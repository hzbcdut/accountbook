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
import nt.ddeoid.accountbook.data.local.DatabaseRekeyer
import nt.ddeoid.accountbook.data.local.MigrationMarker
import nt.ddeoid.accountbook.data.seed.SeedDataInitializer
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
 *
 *                    skipSetup()
 * NeedsSetup ──────────────────────────► Disabled (vault NOT initialized)
 *                                                │
 *                                                │ prepareLockFromDisabled()
 *                                                ▼
 *                                            NeedsSetup
 * ```
 *
 * - `NeedsSetup`:第一次启动,什么都没初始化。SetupWizard 走完后跳到 `Unlocked`。
 * - `Locked`:KeyVault 初始化过但 master key 不在内存里,DB 关着。需要解锁才能跳走。
 * - `Unlocked`:master key 在内存里(只在本对象持有,作为 [activeHandle]),DB 开着。
 * - `Disabled`:用户在 SetupWizard 里选了 Skip。**KeyVault 不存在,DB 用随机生成的
 *   legacy passphrase 打开**(在 EncryptedSharedPrefs 里),直接进主 app。Settings 里能
 *   重新启用(走 `prepareLockFromDisabled` → NeedsSetup → Unlocked 这条线)。
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
    private val databaseRekeyer: DatabaseRekeyer,
    private val migrationMarker: MigrationMarker,
    private val seedDataInitializer: SeedDataInitializer,
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
     * 启动时调用一次:根据 KeyVault / Legacy passphrase / MigrationMarker / wizardCompleted
     * 决定初始状态。
     *
     * 四态分发(Q4 + Phase 4 #30 + Phase 4 #31):
     *
     * - **Migrating**:`migrationMarker.inProgress == true`(上次迁移中断)**或**
     *   `!isInitialized() && passphraseProvider.hasLegacy()`(v0.3.0 升级)。
     *   走迁移 wizard 或恢复备份引导。
     * - **NeedsSetup**:`!wizardCompleted`(全新设备,或用户从 Settings 重新启用锁)。
     *   走 SetupWizard。
     * - **Disabled**:`wizardCompleted && !lockEnabled`(用户曾经选过 Skip)。直接进主 app。
     * - **Locked**:KeyVault 已初始化 + lockEnabled=true。已经走过 setup 或迁移,需要解锁。
     */
    suspend fun bootstrap() = withContext(Dispatchers.IO) {
        val initialized = keyVault.isInitialized()
        val hasLegacy = passphraseProvider.hasLegacy()
        val migrationInterrupted = migrationMarker.inProgress
        val prefs = lockPrefs.snapshot()
        val target = when {
            migrationInterrupted -> LockState.Migrating
            // Phase 4 #37:区分"v0.3.0 真升级"和"fresh-install Skip 后产生了 legacy passphrase"。
            // 后者的 wizardCompleted=true(用户已走完 wizard 选了 Skip),应当走 Disabled 而不是 Migrating。
            !initialized && hasLegacy && !prefs.wizardCompleted -> LockState.Migrating
            !prefs.wizardCompleted -> LockState.NeedsSetup
            !initialized -> LockState.Disabled      // Skip from fresh install (wizardCompleted=true, lockEnabled=false)
            !prefs.lockEnabled -> LockState.Disabled
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
            // 如果 DB 还在用 legacy passphrase(用户在 SetupWizard 选了 Skip 后从 Settings
            // 又启用锁),先 rekey 再 open。fresh install 路径上 hasLegacy=false → 走
            // openDatabaseWith 直接开新库。
            rekeyOrOpenDatabaseWith(handle)
            activeHandle = handle
            lockPrefs.setLockEnabled(lockEnabled)
            lockPrefs.setWizardCompleted(true)
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
            lockPrefs.setWizardCompleted(true)
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

    /**
     * Bug #39 入口:finishSetup 在打开 DB 前判断当前 DB 是否还在用 legacy passphrase,
     * 是的话先 rekey 到从 handle.entropy 派生的新 master key,再走 openDatabaseWith。
     *
     * 检测靠 `passphraseProvider.hasLegacy()` —— 这是 Skip 路径**唯一**的副作用,
     * `LegacyKeyMigrator.migrate()` 成功后会主动擦除,普通 fresh install 也不会产生。
     * 因此这条分支只在"用户曾经选过 Skip、又从 Settings 启用 PIN"的场景触发,不会误伤。
     *
     * rekey 是 SQLCipher 事务级操作:失败时文件**仍**用旧口令加密,调用方可以重试。
     * 这里不单独处理 rekey 异常 —— 直接让异常冒泡,finishSetup 的 transition.withLock
     * 保证状态不变,KeyVault 仍然初始化(wizard step 4 写过的熵还在),wizard 可以让用户
     * 重新尝试。
     */
    private fun rekeyOrOpenDatabaseWith(handle: KeyVault.MasterKeyHandle) {
        if (!passphraseProvider.hasLegacy() || !databaseProvider.isOpen) {
            // fresh install / 库没开 → 直接走普通 open。
            openDatabaseWith(handle)
            return
        }
        databaseProvider.close()
        val oldPass = SecretBytes(passphraseProvider.getOrCreate())
        try {
            SecretBytes(MasterKeyFactory.fromEntropy(handle.entropy).bytes).use { newMaster ->
                databaseRekeyer.rekey(oldPass, newMaster)
            }
            // rekey 成功 → 旧口令已经打不开文件了,best-effort 擦掉。
            // 失败也无所谓(只是 prefs 里残留一段不再能开库的密文),不让它阻塞用户。
            runCatching { passphraseProvider.wipe() }
                .onFailure { android.util.Log.w(TAG, "wipe legacy 失败(非致命)", it) }
        } finally {
            oldPass.wipe()
        }
        openDatabaseWith(handle)
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
        /** 用户在 SetupWizard 选了 Skip —— KeyVault 不存在,DB 不开,直接进主 app。 */
        data object Disabled : LockState
    }

    /**
     * PIN 解锁失败节流(Q5 类 / Phase 4 #31 决议):
     * 连续 5 次错误 → 30s 冷却,之后每次再 5 次错误翻倍,封顶 5min。
     *
     * **进程内即可**(杀进程就绕过) —— 这是文档化的限制,真要对抗攻击者得写到
     * EncryptedSharedPreferences,留作 Phase 5+。
     *
     * LockScreen 的冷却计时器从这里读;UI 用 [produceState] 每秒 tick 刷新。
     */
    @Volatile
    var cooldownUntilEpochMs: Long = 0L
        private set

    /** 连续错误计数。冷却期内不再累加 —— 冷却本身就是节流。 */
    @Volatile
    private var failedAttempts: Int = 0

    /**
     * 在 [unlockWithPin] 外面再包一层,做冷却判定 + 失败计数。
     *
     * - 冷却期内直接返回 [Result.failure] + [LockException.InCooldown],**不**调
     *   [keyVault.unlockWithPin] —— 这是节流的核心。
     * - 成功后清零 `failedAttempts` 和 `cooldownUntilEpochMs`。
     * - 失败:5/10/15/20 次错误 → 冷却 30s/60s/120s/240s,封顶 5min。
     *
     * 跟裸 [unlockWithPin] 的区别:这层对外是"我能不能现在试 PIN",[unlockWithPin] 是
     * "我把 PIN 给你了,试一下"。冷却期调用 [unlockWithPin] 仍然会消耗一次尝试 —— 这里
     * 设计成冷却期直接拦截,**不**计入 attempts。
     */
    suspend fun attemptUnlockWithPin(pin: CharArray): Result<Unit> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (now < cooldownUntilEpochMs) {
            return@withContext Result.failure(
                LockException.InCooldown(cooldownUntilEpochMs - now),
            )
        }
        val result = unlockWithPin(pin)
        // cooldownUntilEpochMs / failedAttempts 是 @Volatile,直接读写安全。
        // 真正的串行化由 [unlockWithPin] 内部的 transition.withLock 保证:
        // 两个 attemptUnlockWithPin 并发 → 串行过 unlockWithPin → 各自拿到 result 后
        // 更新计数,顺序不会乱。
        if (result.isSuccess) {
            failedAttempts = 0
            cooldownUntilEpochMs = 0L
        } else {
            failedAttempts++
            if (failedAttempts >= 5 && failedAttempts % 5 == 0) {
                cooldownUntilEpochMs = System.currentTimeMillis() + computeBackoff(failedAttempts)
            }
        }
        result
    }

    /**
     * backoff 阶梯:5→30s,10→60s,15→120s,20→240s,25+→300s(封顶)。
     *
     * `tier = (attempts - 5) / 5`:5 次错 → tier 0,10 次 → tier 1,以此类推。
     * `30 << tier`:0/1/2/3/4 → 30/60/120/240/480s,再 `coerceAtMost(300)` 卡在 5min。
     */
    private fun computeBackoff(attempts: Int): Long {
        val tier = (attempts - 5) / 5
        return ((30L shl tier.coerceAtMost(4)).coerceAtMost(300)) * 1000L
    }

    /**
     * 测试用:清空 [failedAttempts] 和 [cooldownUntilEpochMs]。
     *
     * 用于在测试里"模拟时间过去"——真实的冷却靠 [System.currentTimeMillis] 流逝,
     * 但单测跑得比 30s 快得多,所以测试需要这个 seam 来重置。
     */
    @androidx.annotation.VisibleForTesting
    fun resetCooldownForTesting() {
        failedAttempts = 0
        cooldownUntilEpochMs = 0L
    }

    /**
     * 测试用:仅清空 [cooldownUntilEpochMs],**不**动 [failedAttempts]。
     *
     * 用于"失败次数累加但模拟冷却已过"的场景 —— 比 [resetCooldownForTesting] 更精细,
     * 适合测 backoff 阶梯(5/10/15/20/25 次错误的 cool down 各是多少)。
     */
    @androidx.annotation.VisibleForTesting
    fun clearCooldownForTesting() {
        cooldownUntilEpochMs = 0L
    }

    /**
     * 用户在 SetupWizard 第一步选 Skip —— 跳过整个 wizard,本会话内永远不进锁定。
     *
     * 与 [finishSetup] 的区别:
     * - 不接 [KeyVault.MasterKeyHandle](用户没设 PIN)
     * - 不写 KeyVault(永远 isInitialized()=false)
     * - 不打开 DB(DB 永远不开 —— 但 wizard 一结束 UI 就要进 Home,这一步需要另外处理)
     *
     * **状态推到 [LockState.Disabled]**。下次启动 [bootstrap] 看到 `wizardCompleted=true &&
     * lockEnabled=false` → 直接进 Disabled → 外层 NavHost 切到 MAIN 路由。
     *
     * ⚠️ DB 不打开:Home 进入时 `DatabaseProvider.isOpen()=false` → 调用方需要在
     * 切到 MAIN 之前显式 open(用空 passphrase 走 v0.3.0 兼容路径,或者从 KeyVault
     * 派生 master key 开库)。Phase 4 #31 的 AccountBookApp.onCreate 会协调这一点。
     */
    suspend fun skipSetup() = withContext(Dispatchers.IO) {
        transition.withLock {
            check(_state.value == LockState.NeedsSetup) { "不能在 NeedsSetup 之外调 skipSetup: state=${_state.value}" }
            lockPrefs.setLockEnabled(false)
            lockPrefs.setWizardCompleted(true)
            // Phase 4 #37 热修复:Skip 路径必须把库打开 + 播种 —— 不然 Home 进入后所有
            // 写操作都会在 [DatabaseProvider.requireDatabase] 抛 DatabaseNotOpenException,
            // 导致保存账号时闪退;平台下拉框也会是空的。
            //
            // 设计上[DatabaseBootstrap.openWithLegacyKey]对全新设备(没有 legacy 口令)
            // 不做任何事,因为它假设"全新设备 → 走 SetupWizard → 不会 Skip"。但 #31 加
            // 了 Skip 按钮之后,这个假设破了。Skip 之后的状态机分支没有"开库"这个动作,
            // 需要补上。
            //
            // 行为:用 passphraseProvider.getOrCreate() 生成 32 字节随机口令并开库,
            // 等价于 Phase 1 / v0.3.0 的默认行为 —— 库依然用 EncryptedSharedPreferences
            // 里的随机串加密,只是**用户没有** PIN 派生路径。
            val justOpened = ensureDatabaseOpen()
            if (justOpened) seedDataInitializer.initialize()
            _state.value = LockState.Disabled
        }
    }

    /**
     * Skip 路径的开库兜底:仅在库尚未打开时执行,生成 legacy 口令并打开 DB。
     *
     * 升级用户(hasLegacy=true)由 AccountBookApp.onCreate 里调到的
     * [nt.ddeoid.accountbook.data.local.DatabaseBootstrap.openWithLegacyKey] 处理,
     * 那条路径已经开过库了,这里就是 no-op。
     *
     * @return true 表示本次调用**实际开了库**(全新设备 / fresh install),
     *         调用方据此决定要不要播种;false 表示库已经由其他路径打开,
     *         不需要再播种。
     */
    private fun ensureDatabaseOpen(): Boolean {
        if (databaseProvider.isOpen) return false
        // 没设过应用锁 → 不会有 vault。check 一下,把状态写明,以后 vault 路径开了锁
        // 再走 openDatabaseWith。
        check(!keyVault.isInitialized()) {
            "已启用应用锁的设备不该走 Skip:KeyVault 已初始化"
        }
        val passphrase = SecretBytes(passphraseProvider.getOrCreate())
        try {
            databaseProvider.open(passphrase)
        } finally {
            passphrase.wipe()
        }
        return true
    }

    /** 把 wizard 完成标记写回 prefs。 */
    suspend fun markWizardCompleted() = withContext(Dispatchers.IO) {
        lockPrefs.setWizardCompleted(true)
    }

    /**
     * Bug #39 入口:从 Settings 里启用 PIN —— 把状态从 [LockState.Disabled] 推到
     * [LockState.NeedsSetup],让 RootNavHost 自动把用户带回 SetupWizard。
     *
     * 是 [skipSetup] 的对偶:那一头 Skip→Disabled,这一头 Disabled→NeedsSetup。
     *
     * ## 为什么不在这里 rekey
     *
     * DB 现在用 legacy passphrase 加密,要换成新 master key 必须 rekey。但**不在**这里
     * 做 —— 推迟到用户走完 wizard、在 [finishSetup] 里做。理由:如果用户在 wizard 中途
     * 取消或被进程杀掉,wizard 已经写过 KeyVault 但 DB 还没 rekey → DB 仍然用 legacy
     * 口令 → 下次启动 [bootstrap] 看到 `hasLegacy=true && wizardCompleted=true` → 走
     * Disabled 状态(Phase 4 #37 的修复)→ 用户可以再点一次 Enable PIN 重来。状态机
     * 自洽,数据不丢。
     *
     * ## 自愈:孤儿 KeyVault
     *
     * 有两种路径会让 [keyVault] 已经初始化但 [LockState] 仍是 Disabled:
     *
     * 1. 用户曾点 Enable PIN → wizard step 4 写了 KeyVault → 进程被杀。
     *    下次启动 `bootstrap` 走 `!initialized && !wizardCompleted` 分支不命中、
     *    走 `!initialized` 也不命中(`initialized=true`),最后 `!prefs.lockEnabled` 命中
     *    → Disabled。
     * 2. 用户从 Settings 启用 PIN → wizard step 4 写了 KeyVault → 又在新 wizard 里选了
     *    Skip。[skipSetup] 把 state 推到 Disabled,但 KeyVault 没擦。
     *
     * 这两种情况下 PIN 已经在 wizard ViewModel scope 里被 wipe 了,但熵留在了
     * EncryptedSharedPrefs 里 —— 用户没法再 unlock。**这里自动 wipe**,让 wizard 从
     * step 1 重新跑出新的熵。
     *
     * ## 自愈:DB 没开
     *
     * 激进 process death 有可能让 `DatabaseBootstrap.openWithLegacyKey` 提前 return(它
     * 看到 `keyVault.isInitialized()=true` 就跳过),DB 没开。这种情况下 `prepareLockFromDisabled`
     * 自己用 legacy passphrase 开回去,不依赖外层补环境。
     */
    suspend fun prepareLockFromDisabled() = withContext(Dispatchers.IO) {
        transition.withLock {
            check(_state.value == LockState.Disabled) {
                "只能在 Disabled 状态启用 PIN: state=${_state.value}"
            }
            // 自愈:DB 没开 → 用 legacy 口令开回去。
            if (!databaseProvider.isOpen) {
                check(passphraseProvider.hasLegacy()) {
                    "DB 未开且没有 legacy 口令,无法启用 PIN"
                }
                val passphrase = SecretBytes(passphraseProvider.getOrCreate())
                try {
                    databaseProvider.open(passphrase)
                } finally {
                    passphrase.wipe()
                }
            }
            // 自愈:KeyVault 残留 → 擦掉,让 wizard 重新生成熵。
            if (keyVault.isInitialized()) {
                android.util.Log.w(
                    TAG,
                    "发现孤儿 KeyVault(已初始化但 state=Disabled),自动 wipe",
                )
                keyVault.wipe()
            }
            _state.value = LockState.NeedsSetup
        }
    }

    private companion object {
        const val TAG = "LockController"
    }
}
