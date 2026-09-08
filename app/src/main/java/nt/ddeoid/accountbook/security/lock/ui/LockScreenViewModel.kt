package nt.ddeoid.accountbook.security.lock.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nt.ddeoid.accountbook.security.crypto.SecretBytes
import nt.ddeoid.accountbook.security.lock.LockController
import nt.ddeoid.accountbook.security.lock.KeyVault
import javax.inject.Inject

/**
 * 锁屏 ViewModel。
 *
 * ## 职责
 *
 * 1. 把 PIN 提交给 [LockController.attemptUnlockWithPin] —— ViewModel 本身**不存**用户输入,
 *    CharArray 从 Composable 的 `remember { CharArray(MAX_PIN_LENGTH) }` 来,提交后
 *    wipe。这是 Q14=B + Q17=B 的硬性要求:杀进程不能让 PIN 残留,锁屏 PIN 不能被任何
 *    ViewModel state 保存进 SavedStateHandle。
 * 2. 跟踪 [LockController.cooldownUntilEpochMs] → 给 UI 一个"剩余冷却秒数"的 ticker。
 *    底层是 `@Volatile` 字段,这里用 250ms poll —— 简单,不会有 race。
 * 3. 给 UI 报告 `biometricAvailable`(从 [KeyVault.hasBiometric] 读),让"用生物识别"
 *    按钮在没 enroll 的设备上隐藏。
 *
 * ## UI 状态机
 *
 * ```
 * Idle ──pin submit──► Unlocking ──success──►(导航切走,ViewModel 销毁)
 *                       │
 *                       └──failure──► Error / Cooldown(都展示 LockError)
 * ```
 *
 * 成功后状态机被外层 NavHost 接住(LockController.state → Unlocked → 路由切到 main),
 * ViewModel 随之销毁,所以这里**不**显式 transition 到"成功态"。
 */
@HiltViewModel
class LockScreenViewModel @Inject constructor(
    private val lockController: LockController,
    private val keyVault: KeyVault,
) : ViewModel() {

    private val _state = MutableStateFlow(
        LockScreenUiState(biometricAvailable = keyVault.hasBiometric()),
    )
    val state: StateFlow<LockScreenUiState> = _state.asStateFlow()

    /**
     * 把 [LockController.cooldownUntilEpochMs] 转成"剩余毫秒数",给 UI 渲染倒计时用。
     *
     * 单独的 `StateFlow` 而不是塞进 [LockScreenUiState] 是因为:
     * - 主状态变化频率低(只有 PIN 提交 / 解锁成功)
     * - 冷却每秒 tick,合在一起会触发整个 LockScreen 重组合,PinKeypad 的输入缓冲
     *   可能被重建
     * - 拆开后 PinKeypad 只订阅冷却那一支,自己持有 `remember { CharArray(...) }`,
     *   不受影响
     *
     * 用 `WhileSubscribed`:LockScreen 不可见时 ticker 停转,省电;LockScreen 一被组合
     * (Compose `collectAsState` 自动订阅)就立刻开始 tick,首帧不会卡 250ms 才看到
     * 正确剩余 —— 初始值用 [computeInitialCooldown] 算准。
     */
    val cooldownRemainingMs: StateFlow<Long> = kotlinx.coroutines.flow.flow {
        while (true) {
            val until = lockController.cooldownUntilEpochMs
            val now = System.currentTimeMillis()
            emit((until - now).coerceAtLeast(0L))
            delay(COOLDOWN_TICK_MS)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = computeInitialCooldown(),
    )

    /** 重置 [LockScreenUiState.error] —— 用户开始输入新 PIN 时调,清掉"再试一次"。 */
    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    /**
     * 用户在 PinKeypad 上点完最后一位后调。PIN 是调用方 [CharArray] 持有的,本方法
     * **不存储**它(只交给 LockController 用一次)。
     *
     * 调用方负责在调用本方法**之前或之后**立刻 `SecretBytes.wipe(pin)` —— 这边
     * 接过去只是把它递给 controller,不会再有第二个引用。
     */
    fun onPinSubmit(pin: CharArray) {
        if (_state.value.unlocking) return
        // 直接读 controller.cooldownUntilEpochMs 而不是 StateFlow.value —— StateFlow
        // 是 250ms tick,首次 tick 前拿到的是 stale 值。Controller 才是 source of truth,
        // 这里必须读到最新。
        if (lockController.cooldownUntilEpochMs > System.currentTimeMillis()) {
            // 冷却中不应该走到这一步 —— PinKeypad 自己看 `cooldownRemainingMs`
            // 禁用按键,这里是兜底。
            return
        }
        _state.update { it.copy(unlocking = true, error = null) }
        viewModelScope.launch {
            try {
                val result = lockController.attemptUnlockWithPin(pin)
                if (result.isFailure) {
                    val error = LockError.from(result.exceptionOrNull()!!)
                    _state.update { it.copy(unlocking = false, error = error) }
                }
                // 成功 → LockController.state 推到 Unlocked → RootNavHost 自动切路由,
                // ViewModel 即将被 NavBackStackEntry 拆掉,这里不需要再动 _state。
            } finally {
                SecretBytes.wipe(pin)
            }
        }
    }

    private fun computeInitialCooldown(): Long {
        val until = lockController.cooldownUntilEpochMs
        return (until - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    /**
     * 触发生物识别解锁。结果通过 [LockScreenUiState.error] 回流到 UI;成功时同样由
     * RootNavHost 切路由处理。
     *
     * @param gate 实际的 BiometricGate,由 Composable 在 FragmentActivity 上下文里
     *   提供(单元测试里传 [FakeBiometricGate.success] / [FakeBiometricGate.failure]
     *   替身)。
     */
    fun onUseBiometric(gate: BiometricGate) {
        if (_state.value.unlocking) return
        _state.update { it.copy(unlocking = true, error = null) }
        viewModelScope.launch {
            val result = gate.prompt()
            when (result) {
                is BiometricGate.Result.Success -> {
                    val unlock = lockController.unlockWithBiometric()
                    if (unlock.isFailure) {
                        _state.update {
                            it.copy(unlocking = false, error = LockError.from(unlock.exceptionOrNull()!!))
                        }
                    }
                    // 成功同上,让外层 NavHost 接管
                }
                is BiometricGate.Result.Failed -> {
                    _state.update {
                        it.copy(unlocking = false, error = LockError.from(result.cause))
                    }
                }
                is BiometricGate.Result.Cancelled -> {
                    // 用户主动取消 —— 不报错,清掉 unlocking,留着让他们试 PIN
                    _state.update { it.copy(unlocking = false, error = null) }
                }
                is BiometricGate.Result.NoHardware -> {
                    _state.update {
                        it.copy(
                            unlocking = false,
                            error = LockError.BiometricInvalidated, // 用户的生物识别没 enroll / 被清
                            biometricAvailable = false,
                        )
                    }
                }
            }
        }
    }

    private companion object {
        /** 250ms tick。1s 一档的话 UI 看起来"卡",250ms 刚好。 */
        const val COOLDOWN_TICK_MS = 250L
    }
}

/**
 * 锁屏 UI 状态。
 *
 * @param error 上一次解锁失败的归一化错误;用户开始新输入时由 [LockScreenViewModel.clearError] 清掉。
 * @param biometricAvailable 是否展示"用生物识别"按钮 —— [KeyVault.hasBiometric] 的快照。
 *   enroll 后这个值可能滞后,Phase 5 接 Settings re-enroll 后会刷新。
 * @param unlocking 正在调 LockController / BiometricGate,UI 上禁用按钮避免重复提交。
 */
data class LockScreenUiState(
    val error: LockError? = null,
    val biometricAvailable: Boolean = false,
    val unlocking: Boolean = false,
)
