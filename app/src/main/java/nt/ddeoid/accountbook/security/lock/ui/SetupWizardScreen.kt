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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import nt.ddeoid.accountbook.R

/**
 * 首次启动的 SetupWizard。6 步,顺序走,不能跳。
 *
 * ## step 不进 ViewModel
 *
 * `currentStep` 是 [remember { mutableStateOf(Step.Welcome) }]。这意味着 process death
 * 后回到 Welcome,Q14+B + Q17+B 的硬性要求。ViewModel 持有的密钥(pin / masterKey /
 * mnemonic)也只在 ViewModel 生命周期内活着,ViewModel 跟着 NavBackStackEntry 被回收时
 * onCleared 会 wipe 一切。
 *
 * ## step 6 自动过渡
 *
 * step 6 Finishing 调 [SetupWizardViewModel.onFinishSetup],成功后 LockController.state
 * 推到 Unlocked → RootNavHost 自动切到 main。本屏**不**主动 navigate,只是检测到
 * lockController.state 变了就清 step 状态。
 */
@Composable
fun SetupWizardScreen(
    modifier: Modifier = Modifier,
    viewModel: SetupWizardViewModel = hiltViewModel(),
) {
    var step by remember { mutableStateOf(SetupStep.Welcome) }
    val state by viewModel.state.collectAsState()

    when (step) {
        SetupStep.Welcome -> WelcomeStep(
            onStart = { step = SetupStep.PinEntry },
            onSkip = viewModel::onSkipSetup,
            modifier = modifier,
        )
        SetupStep.PinEntry -> PinEntryStep(
            onPinAccepted = { step = SetupStep.ConfirmAndBiometric },
            viewModel = viewModel,
            modifier = modifier,
        )
        SetupStep.ConfirmAndBiometric -> ConfirmAndBiometricStep(
            onConfirmed = {
                viewModel.onEnterMnemonicStep()
                step = SetupStep.MnemonicDisplay
            },
            onMismatch = { step = SetupStep.PinEntry },
            viewModel = viewModel,
            modifier = modifier,
        )
        SetupStep.MnemonicDisplay -> MnemonicDisplayStep(
            words = state.mnemonicWords,
            onAcknowledged = { step = SetupStep.MnemonicVerify },
            modifier = modifier,
        )
        SetupStep.MnemonicVerify -> MnemonicVerifyStep(
            targetIndex = state.verificationTargetIndex,
            onSubmit = { position, word ->
                val ok = viewModel.onVerificationWordSubmitted(position, word)
                if (ok) {
                    viewModel.onFinishSetup()
                    step = SetupStep.Finishing
                }
                ok
            },
            modifier = modifier,
        )
        SetupStep.Finishing -> FinishingStep(modifier = modifier)
    }
}

enum class SetupStep { Welcome, PinEntry, ConfirmAndBiometric, MnemonicDisplay, MnemonicVerify, Finishing }

// --- step 1 ---------------------------------------------------------

@Composable
private fun WelcomeStep(
    onStart: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(stringResource(R.string.wizard_welcome_title), style = MaterialTheme.typography.headlineMedium)
        Text(
            stringResource(R.string.wizard_welcome_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.wizard_welcome_start))
        }
        TextButton(onClick = onSkip) {
            Text(stringResource(R.string.wizard_welcome_skip))
        }
    }
}

// --- step 2 ---------------------------------------------------------

@Composable
private fun PinEntryStep(
    onPinAccepted: () -> Unit,
    viewModel: SetupWizardViewModel,
    modifier: Modifier = Modifier,
) {
    var errorText by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(stringResource(R.string.wizard_pin_title), style = MaterialTheme.typography.headlineMedium)
        Text(
            stringResource(R.string.wizard_pin_body, SetupWizardViewModel.MIN_PIN_LENGTH),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (errorText != null) {
            Text(errorText!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        PinKeypad(
            onSubmit = { pin ->
                when (val r = viewModel.onPinEntered(pin)) {
                    SetupWizardViewModel.PinEntryResult.Accepted -> {
                        errorText = null
                        onPinAccepted()
                    }
                    is SetupWizardViewModel.PinEntryResult.TooShort -> {
                        errorText = "PIN 至少 ${r.minLength} 位"
                    }
                    is SetupWizardViewModel.PinEntryResult.TooWeak -> {
                        errorText = r.reason
                    }
                }
            },
            maxLength = SetupWizardViewModel.MIN_PIN_LENGTH,
        )
    }
}

// --- step 3 ---------------------------------------------------------

@Composable
private fun ConfirmAndBiometricStep(
    onConfirmed: () -> Unit,
    onMismatch: () -> Unit,
    viewModel: SetupWizardViewModel,
    modifier: Modifier = Modifier,
) {
    var mismatchError by remember { mutableStateOf(false) }
    var biometricChecked by remember { mutableStateOf(false) }
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(stringResource(R.string.wizard_confirm_title), style = MaterialTheme.typography.headlineMedium)
        Text(
            stringResource(R.string.wizard_confirm_body),
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
            maxLength = SetupWizardViewModel.MIN_PIN_LENGTH,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = biometricChecked, onCheckedChange = { biometricChecked = it })
            Text(stringResource(R.string.wizard_biometric_label))
        }
    }
}

// --- step 4 ---------------------------------------------------------

@Composable
private fun MnemonicDisplayStep(
    words: List<String>,
    onAcknowledged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.wizard_mnemonic_title), style = MaterialTheme.typography.headlineMedium)
        Text(
            stringResource(R.string.wizard_mnemonic_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        // 12 词以 3 列网格展示,每词带 1-12 的序号
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            words.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { w ->
                        MnemonicWordCell(index = words.indexOf(w) + 1, word = w, modifier = Modifier.weight(1f))
                    }
                    // 不足 3 个的填 Spacer 撑开
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onAcknowledged, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.wizard_mnemonic_ack))
        }
    }
}

@Composable
private fun MnemonicWordCell(index: Int, word: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = index.toString().padStart(2, '0'),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = word, style = MaterialTheme.typography.bodyLarge)
    }
}

// --- step 5 ---------------------------------------------------------

@Composable
private fun MnemonicVerifyStep(
    targetIndex: Int,
    onSubmit: (position: Int, word: String) -> Boolean,
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf("") }
    var wrongError by remember { mutableStateOf(false) }
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(stringResource(R.string.wizard_verify_title), style = MaterialTheme.typography.headlineMedium)
        Text(
            stringResource(R.string.wizard_verify_body, targetIndex + 1),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        OutlinedTextField(
            value = input,
            onValueChange = {
                input = it
                wrongError = false
            },
            singleLine = true,
            isError = wrongError,
            modifier = Modifier.fillMaxWidth(),
        )
        if (wrongError) {
            Text(
                stringResource(R.string.wizard_verify_wrong),
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(
            onClick = {
                if (onSubmit(targetIndex, input)) {
                    wrongError = false
                } else {
                    wrongError = true
                }
            },
            enabled = input.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_confirm))
        }
    }
}

// --- step 6 ---------------------------------------------------------

@Composable
private fun FinishingStep(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.wizard_finishing))
    }
}
