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
import nt.ddeoid.accountbook.security.lock.LockController
import javax.inject.Inject

/**
 * 助记词恢复屏 ViewModel。
 *
 * ## 不持有用户输入的字面(只持有规范化后的)
 *
 * RecoveryScreen 是 12 个 `OutlinedTextField`,每个有自己的 `remember { mutableStateOf("") }`。
 * ViewModel **不**保存这些字段值 —— 不放到 StateFlow,不进 SavedStateHandle。这是 Q14=B +
 * Q17=B 的硬性要求:进程被杀重启后 12 格清空,用户重新输入。
 *
 * ViewModel 只持有**已规范化且开始校验**的中间结果。当前是简化版:
 * - 用户每次编辑完一格(失焦)→ Composable 调 [onWordChanged]
 * - ViewModel 把它放进 [RecoveryUiState.words],做 normalize + 单个词校验
 * - 12 词都填了之后,Composable 启用"恢复"按钮 → [onSubmit]
 * - 调 [LockController.unlockWithMnemonic],结果翻译成 LockError
 */
@HiltViewModel
class RecoveryViewModel @Inject constructor(
    private val lockController: LockController,
    private val mnemonicCodec: MnemonicCodec,
) : ViewModel() {

    private val _state = MutableStateFlow(RecoveryUiState())
    val state: StateFlow<RecoveryUiState> = _state.asStateFlow()

    /**
     * 12 格中第 [position] 格的内容更新了。
     *
     * 触发点应该是 OutlinedTextField 的 `onValueChange` 或 `onFocusChanged(已失焦)`。
     * 简化为 onValueChange:用户每打一个字符都会做一次 normalize,代价低(BIP39 词表查
     * map 即可)。
     */
    fun onWordChanged(position: Int, raw: String) {
        if (position !in 0 until WORD_COUNT) return
        // 单格输入理论上只有一个词,但 normalize 是 List;取第一个作为本格内容。
        val normalized = mnemonicCodec.normalize(raw).firstOrNull().orEmpty()
        val isWordInList = normalized.isNotEmpty() && normalized in mnemonicCodec.wordSet
        val newWords = _state.value.words.toMutableList().also {
            it[position] = normalized
        }
        _state.update {
            it.copy(
                words = newWords,
                isWordValidInList = it.isWordValidInList.toMutableMap().also { m ->
                    m[position] = isWordInList
                },
                allTwelveFilled = newWords.all { w -> w.isNotEmpty() },
                error = null,
            )
        }
    }

    /** 把 12 格统一调一遍 normalize —— 给"粘贴整段"的入口用,Phase 5+。 */
    fun onPaste(rawBlock: String) {
        val words = mnemonicCodec.normalize(rawBlock)
        val padded: List<String> = words.take(WORD_COUNT).let { firstN ->
            if (firstN.size < WORD_COUNT) firstN + List(WORD_COUNT - firstN.size) { "" }
            else firstN
        }
        _state.update {
            it.copy(
                words = padded,
                isWordValidInList = (0 until WORD_COUNT).associateWith { i ->
                    val w = padded[i]
                    w.isNotEmpty() && w in mnemonicCodec.wordSet
                },
                allTwelveFilled = padded.all { w -> w.isNotEmpty() },
                error = null,
            )
        }
    }

    /**
     * 用户按了"恢复"按钮。把 12 词交给 [LockController.unlockWithMnemonic],结果翻译
     * 成 [LockError]。
     *
     * 成功时 LockController.state → Unlocked,RootNavHost 切到 main,本 ViewModel 跟着
     * 被销毁 —— 这里**不**清 state。
     */
    fun onSubmit() {
        val s = _state.value
        if (!s.allTwelveFilled || s.unlocking) return
        _state.update { it.copy(unlocking = true, error = null) }
        viewModelScope.launch {
            val result = lockController.unlockWithMnemonic(s.words)
            if (result.isFailure) {
                _state.update {
                    it.copy(unlocking = false, error = LockError.from(result.exceptionOrNull()!!))
                }
            }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    companion object {
        const val WORD_COUNT = 12
    }
}

data class RecoveryUiState(
    val words: List<String> = List(RecoveryViewModel.WORD_COUNT) { "" },
    /**
     * 每一格的"是否在 BIP39 词表里"标志。`null` 表示还没校验过(刚 focus)。
     * UI 上**不**强制要求所有格都 inList —— 12 词整体还能再走一次
     * [MnemonicCodec.decode] 触发 ChecksumMismatch。
     */
    val isWordValidInList: Map<Int, Boolean> = emptyMap(),
    val allTwelveFilled: Boolean = false,
    val unlocking: Boolean = false,
    val error: LockError? = null,
)
