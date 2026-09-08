package nt.ddeoid.accountbook.security.lock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import nt.ddeoid.accountbook.R

/**
 * 锁屏主屏 —— 在 [LockController.LockState.Locked] 状态下被 RootNavHost 组合。
 *
 * 屏幕分四块:
 * 1. 标题 / 副标题 / 错误文案
 * 2. PIN dots(由 [PinKeypad] 自带)
 * 3. [PinKeypad]
 * 4. 底部"用生物识别"(可选)+"忘记 PIN"
 *
 * ## 路由回调
 *
 * - [onUnlocked]:留作备用 —— 当前的成功跳转靠 [LockController.state] 推 Unlocked,
 *   由 [RootNavHost] 自动切路由。Compose 这边不主动 nav。
 * - [onForgotPin]:显式 nav 到 [RootRoutes.RECOVERY] —— 由 LockScreen 直接调用
 *   `navController.navigate(...)`;这是 LockScreen 唯一**主动**碰 nav 的地方。
 *
 * ## 冷却期显示
 *
 * `cooldownRemainingMs` 由 ViewModel 250ms tick 提供;UI 上每秒 round 一次显示成秒数。
 * PinKeypad 在冷却期被 `enabled = false`,防止用户继续戳。
 */
@Composable
fun LockScreen(
    onForgotPin: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LockScreenViewModel = hiltViewModel(),
) {
    val uiState by viewModel.state.collectAsState()
    val cooldownMs by viewModel.cooldownRemainingMs.collectAsState()
    val gate = rememberBiometricGate()

    // 倒计时文案:剩 N 秒。0 时表示没在冷却。
    val cooldownSeconds = (cooldownMs / 1000L).toInt()
    val inCooldown = cooldownSeconds > 0

    // 错误文案渲染:每个 LockError variant 自己决定要哪些 string format 参数。
    // 故意不用 stringResource(messageRes(), ...) 一把梭 —— Cooldown 的秒数来自
    // ticker 不来自 error 字段;MnemonicUnknownWord 的位置 + 词都是字段值。
    val errorText: String? = uiState.error?.let { err ->
        when (err) {
            is LockError.Cooldown -> null // cooldownSeconds 单独显示
            is LockError.MnemonicUnknownWord -> stringResource(
                R.string.lock_error_mnemonic_unknown_word,
                err.position + 1,
                err.word,
            )
            is LockError.MnemonicWrongCount -> stringResource(R.string.lock_error_mnemonic_wrong_count, 0)
            is LockError.MnemonicChecksumMismatch -> stringResource(R.string.lock_error_mnemonic_checksum)
            is LockError.WrongPin -> stringResource(R.string.lock_error_wrong_pin)
            is LockError.DatabaseCorrupted -> stringResource(R.string.lock_error_database_corrupted)
            is LockError.BiometricInvalidated -> stringResource(R.string.lock_error_biometric_invalidated)
            is LockError.Unknown -> stringResource(R.string.lock_error_unknown, err.detail)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = stringResource(R.string.lock_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(R.string.lock_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (inCooldown) {
            Text(
                text = stringResource(R.string.lock_error_cooldown, cooldownSeconds),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyLarge,
            )
        } else if (errorText != null) {
            Text(
                text = errorText,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(8.dp))

        PinKeypad(
            onSubmit = viewModel::onPinSubmit,
            maxLength = 8, // 跟 KeyVault 一致;真正 PIN 长度由 LockController 验证
            enabled = !inCooldown,
            busy = uiState.unlocking,
        )

        Spacer(Modifier.height(8.dp))

        if (uiState.biometricAvailable) {
            IconButton(onClick = { viewModel.onUseBiometric(gate) }, enabled = !inCooldown) {
                Icon(
                    imageVector = Icons.Default.Fingerprint,
                    contentDescription = stringResource(R.string.lock_action_use_biometric),
                    modifier = Modifier.size(32.dp),
                )
            }
        }

        TextButton(onClick = onForgotPin) {
            Text(stringResource(R.string.lock_action_forgot_pin))
        }
    }
}
