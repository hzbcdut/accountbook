package nt.ddeoid.accountbook.security.lock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import nt.ddeoid.accountbook.R

/**
 * 迁移向导 —— v0.3.0 → v0.4.0 升级用户。
 *
 * ## 两个入口
 *
 * 启动时 ViewModel 读 [MigrationMarker.inProgress]:
 *
 * - `true` → 上次 rekey 成功但 vault 没写完就崩了,直接展示
 *   [InterruptedStep](引导用户从备份恢复,**Phase 5 接 BackupImporter,本任务只暴露
 *   onRestoreFromBackup 回调给 RootNavHost**,目前是 no-op)
 * - `false` → 走 [PinEntryStep] → [ConfirmAndMigrateStep]
 *
 * ## step 不进 ViewModel
 *
 * 跟 SetupWizard 同样的契约 —— Compose 端 `remember` 持有 step,process death 后
 * 回 step 1 是 Q14=B + Q17=B 的硬性要求。
 *
 * ## "从备份恢复"是 placeholder
 *
 * [InterruptedStep] 上有个按钮调 `onRestoreFromBackup`,但本任务**不**实现这个流程,
 * 留给 Phase 5。当前这个回调在 RootNavHost 里是 no-op(往下沉一格到 manifest 也行,
 * 但 v0.4.0 不发 manifest 文件选择器)。等 Phase 5 把 BackupImporter 接入即可。
 */
@Composable
fun MigrationWizardScreen(
    onRestoreFromBackup: () -> Unit,
    viewModel: MigrationWizardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var step by remember {
        mutableStateOf(
            // interrupted 一开始就是 true → 直接跳到 InterruptedStep
            if (state.interrupted) MigrationStep.Interrupted else MigrationStep.PinEntry,
        )
    }

    when (step) {
        MigrationStep.Interrupted -> InterruptedStep(
            onRestoreFromBackup = onRestoreFromBackup,
        )
        MigrationStep.PinEntry -> PinEntryStep(
            onPinAccepted = { step = MigrationStep.ConfirmAndMigrate },
            viewModel = viewModel,
        )
        MigrationStep.ConfirmAndMigrate -> ConfirmAndMigrateStep(
            onConfirmed = {
                viewModel.onConfirmMigrate()
                step = MigrationStep.Migrating
            },
            onMismatch = { step = MigrationStep.PinEntry },
            viewModel = viewModel,
        )
        MigrationStep.Migrating -> MigratingStep(
            error = state.error,
            onRetry = {
                viewModel.clearError()
                step = MigrationStep.PinEntry
            },
            onRestoreFromBackup = onRestoreFromBackup,
        )
    }
}

private enum class MigrationStep { Interrupted, PinEntry, ConfirmAndMigrate, Migrating }

// --- interrupted ------------------------------------------------------

@Composable
private fun InterruptedStep(
    onRestoreFromBackup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(stringResource(R.string.migration_interrupted_title), style = MaterialTheme.typography.headlineMedium)
        Text(
            stringResource(R.string.migration_interrupted_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onRestoreFromBackup, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.migration_action_restore_backup))
        }
    }
}

// --- step 1 -----------------------------------------------------------

@Composable
private fun PinEntryStep(
    onPinAccepted: () -> Unit,
    viewModel: MigrationWizardViewModel,
    modifier: Modifier = Modifier,
) {
    var errorText by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(stringResource(R.string.migration_pin_title), style = MaterialTheme.typography.headlineMedium)
        Text(
            stringResource(R.string.migration_pin_body, MigrationWizardViewModel.MIN_PIN_LENGTH),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (errorText != null) {
            Text(errorText!!, color = MaterialTheme.colorScheme.error)
        }
        PinKeypad(
            onSubmit = { pin ->
                when (val r = viewModel.onPinEntered(pin)) {
                    MigrationWizardViewModel.PinEntryResult.Accepted -> {
                        errorText = null
                        onPinAccepted()
                    }
                    is MigrationWizardViewModel.PinEntryResult.TooShort -> {
                        errorText = "PIN 至少 ${r.minLength} 位"
                    }
                    is MigrationWizardViewModel.PinEntryResult.TooWeak -> {
                        errorText = r.reason
                    }
                }
            },
            maxLength = MigrationWizardViewModel.MIN_PIN_LENGTH,
        )
    }
}

// --- step 2 -----------------------------------------------------------

@Composable
private fun ConfirmAndMigrateStep(
    onConfirmed: () -> Unit,
    onMismatch: () -> Unit,
    viewModel: MigrationWizardViewModel,
    modifier: Modifier = Modifier,
) {
    var mismatchError by remember { mutableStateOf(false) }
    var biometricChecked by remember { mutableStateOf(false) }
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(stringResource(R.string.migration_confirm_title), style = MaterialTheme.typography.headlineMedium)
        Text(
            stringResource(R.string.migration_confirm_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (mismatchError) {
            Text(
                stringResource(R.string.wizard_confirm_mismatch),
                color = MaterialTheme.colorScheme.error,
            )
        }
        PinKeypad(
            onSubmit = { pin ->
                if (viewModel.onPinConfirmed(pin)) {
                    mismatchError = false
                    viewModel.onBiometricToggled(biometricChecked)
                    onConfirmed()
                } else {
                    mismatchError = true
                    onMismatch()
                }
            },
            maxLength = MigrationWizardViewModel.MIN_PIN_LENGTH,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = biometricChecked, onCheckedChange = { biometricChecked = it })
            Text(stringResource(R.string.wizard_biometric_label))
        }
    }
}

// --- step 3 -----------------------------------------------------------

@Composable
private fun MigratingStep(
    error: MigrationError?,
    onRetry: () -> Unit,
    onRestoreFromBackup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        if (error == null) {
            // 正在跑(用户没看到任何错误,转圈 + 文字)
            CircularProgressIndicator()
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.migration_progress))
        } else {
            Text(stringResource(R.string.migration_failed_title), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(R.string.migration_failed_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.migration_action_retry))
            }
            OutlinedButton(onClick = onRestoreFromBackup, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.migration_action_restore_backup))
            }
        }
    }
}

// --- 占位:根 Box,无错误时永远进不到这里,但保留以防 step state 出错 -----------------

@Composable
@Suppress("unused")
private fun MigrationScreenDebugBox(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
