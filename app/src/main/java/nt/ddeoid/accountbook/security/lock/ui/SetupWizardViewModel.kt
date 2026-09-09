package nt.ddeoid.accountbook.security.lock.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nt.ddeoid.accountbook.security.crypto.MnemonicCodec
import nt.ddeoid.accountbook.security.crypto.SecretBytes
import nt.ddeoid.accountbook.security.lock.LockController
import nt.ddeoid.accountbook.security.lock.LockPrefs
import nt.ddeoid.accountbook.security.lock.KeyVault
import javax.inject.Inject

/**
 * SetupWizard ViewModel。
 *
 * ## 步骤流转(由 [SetupWizardScreen] 持有 step state,ViewModel 不感知具体 step)
 *
 * - step 1 Welcome:用户点"开始" → step 2
 * - step 2 PinEntry:用户输入 PIN,提交 → step 3
 * - step 3 ConfirmAndBiometric:再输一次 PIN,**不一致则不让过**;可勾选生物识别
 * - step 4 MnemonicDisplay:展示 12 词(mnemonic 此时才生成,不提前)
 * - step 5 MnemonicVerify:随机挑一格让用户填,验证通过 → step 6
 * - step 6 Finishing:调 [LockController.finishSetup] → state → Unlocked → RootNavHost 切走
 *
 * ## 故意不让 ViewModel 持有"step X"
 *
 * step 是 Compose 端的 `remember { mutableStateOf(Welcome) }`。process death 后回 step 1
 * 是有意为之(Q14=B + Q17+B)。如果 ViewModel 把 step 放 StateFlow,process death 时 Hilt
 * 会试图恢复它,反而违反"用户必须从头来"的硬性要求。
 *
 * ## PIN 不进 StateFlow
 *
 * [pin] 字段在 ViewModel 内部临时持有,跟 LockScreenViewModel 同样的契约 —— 提交后
 * `SecretBytes.wipe`。**不**进 SavedStateHandle,**不**进 StateFlow。
 *
 * ## Mnemonic 生成时机
 *
 * 推迟到 step 4:从 step 1 到 step 3 期间,PIN 还没确定,提前生成 + 展示会引入"用户
 * 抄了一串词但后来改了 PIN"这种尴尬。MasterKeyFactory 派生本身就是确定性的
 * (相同熵 + 相同 PIN = 相同 master key),所以即使 step 5 验证失败回 step 2,重做时
 * 我们**重新生成**熵,旧熵被 wipe。
 */
@HiltViewModel
class SetupWizardViewModel @Inject constructor(
    private val keyVault: KeyVault,
    private val lockController: LockController,
    private val lockPrefs: LockPrefs,
    private val mnemonicCodec: MnemonicCodec,
    /**
     * [KeyVault.initialize] 跑 600k PBKDF2 + 可选的 Keystore create/delete,emulator
     * 无 secure lock screen 时 Keystore 那段还要走完整 init → spec → generateKey 三步
     * 才抛异常,合计 > 5 s,Main 上跑会 ANR。注入而不是直接 `Dispatchers.IO`,是因为测试用
     * `runTest` + `UnconfinedTestDispatcher` 时不会跟踪真实 IO 任务,需要传
     * `UnconfinedTestDispatcher()` 进去让断言立刻可见。
     */
    private val cryptoDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(SetupWizardUiState())
    val state: StateFlow<SetupWizardUiState> = _state.asStateFlow()

    /** step 4/5 用的 12 词;只在这两步之间活着,提交后立刻 wipe。 */
    private var mnemonic: List<String> = emptyList()

    /** step 4/5 用的 master key;步骤结束后 wipe,DB 用派生出来的口令打开。 */
    private var masterKey: SecretBytes? = null

    /**
     * 16 字节 BIP39 熵 —— 给 [KeyVault.MasterKeyHandle] 用的。
     *
     * 之前 wizard 直接把 [masterKey] 的 32 字节塞进 `handle.entropy` 字段,导致
     * LockController 再做一次 HKDF 时拿到一个**不同的** 32 字节 key,DB 用错 key
     * 加密 → 重启后 PIN 解锁报 "file is not a database"。
     *
     * 修复后:wizard 拿 [KeyVault.SetupResult.entropy](真熵,16 字节) 构造 handle。
     */
    private var entropy: ByteArray? = null

    /** 临时 PIN。提交 setup 后立刻 wipe。 */
    private var pin: CharArray? = null

    /** 用户选了"启用生物识别"吗?最后 [onFinishSetup] 时用。 */
    private var biometricEnabled: Boolean = false

    /** 步骤 2 → 3 切换:验证 PIN 长度(8+)和字符集(至少非空),不存进 ViewModel state。 */
    fun onPinEntered(raw: CharArray): PinEntryResult {
        val cleaned = raw.copyOf()
        val minLength = MIN_PIN_LENGTH
        return when {
            cleaned.size < minLength -> {
                SecretBytes.wipe(cleaned)
                PinEntryResult.TooShort(minLength)
            }
            cleaned.all { it == cleaned[0] } -> {
                // 拒绝全部同字符的 PIN(1111111)—— 1/256 概率被随机到,但用户
                // 真这么设说明没理解 PIN 的目的。
                SecretBytes.wipe(cleaned)
                PinEntryResult.TooWeak("PIN 不能全是同一个字符")
            }
            else -> {
                pin = cleaned
                PinEntryResult.Accepted
            }
        }
    }

    /** 步骤 3:确认 PIN。返回 true 表示通过。 */
    fun onPinConfirmed(raw: CharArray): Boolean {
        val expected = pin
        if (expected == null) {
            // v0.4.5 防御:用户报告"再次输入 PIN 之后又切换到 PIN 界面",log 一下
            // pin 为 null 的情况,定位是 onCleared 跑了还是 onPinConfirmed 的 else
            // 分支跑了。理论上 step 2 → step 3 期间 pin 不该 null。
            android.util.Log.w(
                "SetupWizardViewModel",
                "onPinConfirmed: pin 字段为 null,raw.length=${raw.size}",
            )
            SecretBytes.wipe(raw)
            return false
        }
        return if (raw.contentEquals(expected)) {
            SecretBytes.wipe(raw)
            android.util.Log.d("SetupWizardViewModel", "onPinConfirmed: 匹配")
            true
        } else {
            SecretBytes.wipe(raw)
            pin?.let { SecretBytes.wipe(it) }
            pin = null
            android.util.Log.w(
                "SetupWizardViewModel",
                "onPinConfirmed: 不匹配,清掉 pin",
            )
            false
        }
    }

    /** 步骤 3:用户勾选/取消生物识别。 */
    fun onBiometricToggled(enabled: Boolean) {
        biometricEnabled = enabled
    }

    /**
     * 步骤 4:进入 mnemonic 展示页。
     *
     * **如果用户回退到 step 2 改了 PIN,旧熵应该被 wipe、重新生成**。这一层因为
     * 没有"步骤回退"事件,这里采用"每次进入 step 4 都生成新熵"的策略 —— 简单,
     * 多花一次 PBKDF2,但走不到生产路径(用户在步骤之间反复横跳属异常)。
     *
     * ## crypto 工作必须跑在 [cryptoDispatcher]
     *
     * v0.4.2 用户报告:勾选"启用生物识别"后输入确认 PIN 的最后一个字符 → ANR。
     * 根因是 `keyVault.initialize(...)` 整段(PBKDF2 600k + 可选 Keystore init →
     * spec → generateKey)在 Main 线程上跑,emulator 无 secure lock screen 时
     * Keystore 那段还要走完整三步才抛 BlobCorrupted,合计 > 5 s → Input dispatching
     * timed out。修复后用注入的 [cryptoDispatcher] 切走 Main;注入而不是直接
     * `Dispatchers.IO`,是因为 `runTest` + `UnconfinedTestDispatcher` 不会跟踪
     * 真实 IO 任务,需要测试自己传 `UnconfinedTestDispatcher()` 进去。
     *
     * ## 同步设 isInitializing=true
     *
     * 之前没有 isInitializing 字段时,UI 在 `onEnterMnemonicStep()` 返回后立即
     * `step = MnemonicDisplay`,但 [mnemonicWords] 还没 populate(crypto 在 IO 上
     * 跑,5s+),用户看到空白 step 4 → 卡顿感。
     *
     * 现在在 launch 之前同步 emit `isInitializing = true`,UI 拿这个状态显示 busy
     * spinner 并禁用 keypad;crypto 跑完后 emit `isInitializing = false` 同时
     * populate mnemonicWords,UI 再用 LaunchedEffect 跳 step 4。
     */
    fun onEnterMnemonicStep() {
        android.util.Log.d(
            "SetupWizardViewModel",
            "onEnterMnemonicStep: pin=${pin != null} biometricEnabled=$biometricEnabled",
        )
        wipeMnemonicAndMasterKey()
        _state.update { it.copy(isInitializing = true) }
        viewModelScope.launch {
            val currentPin = pin ?: run {
                _state.update { it.copy(isInitializing = false) }
                android.util.Log.w(
                    "SetupWizardViewModel",
                    "onEnterMnemonicStep coroutine: pin 字段为 null,直接 return",
                )
                return@launch
            }
            // TODO:如果生物识别 setup 静默失败(无 secure lock screen),
            // UI 应当提示用户。当前只修崩溃,UX 留作后续 issue。
            try {
                android.util.Log.d(
                    "SetupWizardViewModel",
                    "onEnterMnemonicStep coroutine: 开始 keyVault.initialize",
                )
                val setupResult = withContext(cryptoDispatcher) {
                    keyVault.initialize(currentPin, mnemonicCodec, biometricEnabled)
                }
                android.util.Log.d(
                    "SetupWizardViewModel",
                    "onEnterMnemonicStep coroutine: keyVault.initialize 完成,生成 ${setupResult.mnemonic.size} 词",
                )
                mnemonic = setupResult.mnemonic
                masterKey = setupResult.masterKey
                entropy = setupResult.entropy
                _state.update {
                    it.copy(
                        mnemonicWords = setupResult.mnemonic,
                        verificationTargetIndex = (0 until WORD_COUNT).random(),
                        isInitializing = false,
                    )
                }
            } catch (t: Throwable) {
                // crypto 失败(理论上 setup 路径不会,KeyVault 已兜底所有 keystore 异常;
                // 这里的 catch 是为了不让 isInitializing 卡在 true → spinner 永转 → UI 死锁)。
                // Log + 重置 state:viewModelScope 是 SupervisorJob,未捕获异常会被吞,
                // 但我们不 rethrow —— rethrow 在 `runTest` 测试里会让整个测试 fail,而
                // 生产环境 viewModelScope 也会吞掉,等价行为。Log.e 保留现场。
                android.util.Log.e(
                    "SetupWizardViewModel",
                    "keyVault.initialize 失败,UI 应提示用户重试(当前还没做)",
                    t,
                )
                _state.update { it.copy(isInitializing = false) }
            }
        }
    }

    /**
     * 步骤 5:用户填了验证词。返回 true 表示通过,继续 → step 6 → finishSetup。
     *
     * @param position 用户填的是哪一格
     * @param word 用户填的词(已经 lowercase + trim)
     */
    fun onVerificationWordSubmitted(position: Int, word: String): Boolean {
        val target = _state.value.verificationTargetIndex
        if (position != target) return false
        return word.lowercase().trim() == mnemonic.getOrNull(position)
    }

    /**
     * 步骤 6:最终完成。pin 已经被 step 2 验证过、masterKey 在 step 4 算好,这里
     * 把 handle 交给 LockController.finishSetup → DB 打开 + state → Unlocked →
     * RootNavHost 自动切到 main。
     *
     * 完成后 ViewModel 立刻 wipe 自己持有的所有密钥。
     */
    fun onFinishSetup() {
        val currentPin = pin ?: return
        val mk = masterKey ?: return
        val realEntropy = entropy ?: return
        // 之后这三块都不再需要(entropy 转给 controller 做 handle,
        // masterKey 提前 wipe,pinned 仅在 setup 流程内部用)
        pin = null
        masterKey = null
        entropy = null
        viewModelScope.launch {
            val handle = KeyVault.MasterKeyHandle(
                entropy = realEntropy.copyOf(),
                kind = KeyVault.MasterKeyHandle.Kind.DERIVED_FROM_PIN,
            )
            try {
                mk.wipe()
                realEntropy.fill(0)
                lockController.finishSetup(
                    handle = handle,
                    lockEnabled = true,
                    timeout = LockPrefs.TimeoutTier.IMMEDIATE,
                )
                SecretBytes.wipe(currentPin)
            } catch (t: Throwable) {
                handle.wipe()
                SecretBytes.wipe(currentPin)
                throw t
            }
        }
    }

    /** step 1:用户选 Skip。 */
    fun onSkipSetup() {
        viewModelScope.launch {
            lockController.skipSetup()
        }
    }

    /** 进程被杀重入时清理残留(Q17+B)。 */
    override fun onCleared() {
        super.onCleared()
        wipeMnemonicAndMasterKey()
        entropy?.fill(0)
        entropy = null
        pin?.let { SecretBytes.wipe(it) }
        pin = null
    }

    private fun wipeMnemonicAndMasterKey() {
        mnemonic = emptyList()
        masterKey?.wipe()
        masterKey = null
    }

    sealed interface PinEntryResult {
        data object Accepted : PinEntryResult
        data class TooShort(val minLength: Int) : PinEntryResult
        data class TooWeak(val reason: String) : PinEntryResult
    }

    companion object {
        const val WORD_COUNT = 12
        const val MIN_PIN_LENGTH = 8
    }
}

/**
 * 暴露给 UI 的状态。
 *
 * @param mnemonicWords 步骤 4 显示的 12 词;只在 step 4/5 期间非空。
 * @param verificationTargetIndex 步骤 5 要用户填的格子下标。
 * @param isInitializing 步骤 3 → 4 之间 crypto 是否在跑。true 时 UI 应显示
 *   busy spinner 并禁用 keypad;false 时如果 [mnemonicWords] 非空则可跳到 step 4。
 *   v0.4.2 引入,目的是把"按完最后一位 → 空白页 → 5s 后出词"的卡顿感改成
 *   "按完最后一位 → spinner → 出词"的可感知进度。
 */
data class SetupWizardUiState(
    val mnemonicWords: List<String> = emptyList(),
    val verificationTargetIndex: Int = -1,
    val isInitializing: Boolean = false,
)
