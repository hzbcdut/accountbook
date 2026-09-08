package nt.ddeoid.accountbook.security.lock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
 * 助记词恢复屏。
 *
 * 从 LockScreen 的"忘记 PIN"进来;成功时由 [LockController.unlockWithMnemonic] 推
 * state → Unlocked → [RootNavHost] 切到 main。本屏**不**主动 navigate。
 *
 * 文案引导:
 * - 标题:请输入 12 个助记词
 * - 副标题:输入你抄在纸上那 12 个词,顺序不要变
 * - 错误:由 [LockError] 翻译层决定
 *
 * ## 安全性
 *
 * - 12 词的字面值**不**入 [RecoveryViewModel] 之外的任何存储(state flow 只给 UI 用,
 *   进程被杀重启后回到初始状态)
 * - 12 格 keystrokes 不进系统 IME 学习(Android 自带的 autofill 框架看到 `keyboardType = Text`
 *   + `autoCorrect = false` + 无 `autofillHints` 通常不会拦截;实在要硬防可加
 *   `Modifier.semantics { contentType = ContentType.Password }`,留作 Phase 5)
 */
@Composable
fun RecoveryScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecoveryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.recovery_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(R.string.recovery_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(8.dp))

        MnemonicGrid(
            onWordChanged = viewModel::onWordChanged,
            wordValidInList = state.isWordValidInList,
            modifier = Modifier.fillMaxWidth(),
        )

        if (state.error != null) {
            val text = renderRecoveryError(state.error!!)
            Text(
                text = text,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = viewModel::onSubmit,
            enabled = state.allTwelveFilled && !state.unlocking,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.recovery_action_submit))
        }
        TextButton(onClick = onBack) {
            Text(stringResource(R.string.action_back))
        }
    }
}

@Composable
private fun renderRecoveryError(error: LockError): String = when (error) {
    is LockError.MnemonicUnknownWord -> stringResource(
        R.string.lock_error_mnemonic_unknown_word,
        error.position + 1,
        error.word,
    )
    is LockError.MnemonicWrongCount -> stringResource(R.string.lock_error_mnemonic_wrong_count, 12)
    is LockError.MnemonicChecksumMismatch -> stringResource(R.string.lock_error_mnemonic_checksum)
    is LockError.Cooldown -> stringResource(R.string.lock_error_cooldown, (error.retryAfterMs / 1000L).toInt())
    is LockError.WrongPin -> stringResource(R.string.lock_error_wrong_pin)
    is LockError.DatabaseCorrupted -> stringResource(R.string.lock_error_database_corrupted)
    is LockError.BiometricInvalidated -> stringResource(R.string.lock_error_biometric_invalidated)
    is LockError.Unknown -> stringResource(R.string.lock_error_unknown, error.detail)
}
