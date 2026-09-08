package nt.ddeoid.accountbook.security.lock.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
) : ViewModel() {

    private val _state = MutableStateFlow(SetupWizardUiState())
    val state: StateFlow<SetupWizardUiState> = _state.asStateFlow()

    /** step 4/5 用的 12 词;只在这两步之间活着,提交后立刻 wipe。 */
    private var mnemonic: List<String> = emptyList()

    /** step 4/5 用的 master key;步骤结束后 wipe,DB 用派生出来的口令打开。 */
    private var masterKey: SecretBytes? = null

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
        val expected = pin ?: return false
        return if (raw.contentEquals(expected)) {
            SecretBytes.wipe(raw)
            true
        } else {
            SecretBytes.wipe(raw)
            pin?.let { SecretBytes.wipe(it) }
            pin = null
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
     * ## 不切到 Default
     *
     * 故意不在这里 `withContext(Dispatchers.Default)`,因为这一步只跑一次、用户
     * 也明确点了"进入下一步",500ms 的 PBKDF2 阻塞主线程是肉眼看不见的;但保持
     * 调用栈在 [viewModelScope] 的 Main 上,让测试用 [UnconfinedTestDispatcher]
     * 时不用处理"真实 Default 派发"的等待问题 —— 参见
     * [SetupWizardViewModelTest]。
     */
    fun onEnterMnemonicStep() {
        wipeMnemonicAndMasterKey()
        viewModelScope.launch {
            val currentPin = pin ?: return@launch
            val setupResult = keyVault.initialize(currentPin, mnemonicCodec)
            mnemonic = setupResult.mnemonic
            masterKey = setupResult.masterKey
            _state.update {
                it.copy(
                    mnemonicWords = setupResult.mnemonic,
                    verificationTargetIndex = (0 until WORD_COUNT).random(),
                )
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
        // 之后这两块都不再需要(masterKey 转给 controller,pinned 仅在 setup 流程内部用)
        pin = null
        masterKey = null
        viewModelScope.launch {
            val handle = KeyVault.MasterKeyHandle(
                entropy = mk.bytes.copyOf(),
                kind = KeyVault.MasterKeyHandle.Kind.DERIVED_FROM_PIN,
            )
            try {
                mk.wipe()
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
 */
data class SetupWizardUiState(
    val mnemonicWords: List<String> = emptyList(),
    val verificationTargetIndex: Int = -1,
)
