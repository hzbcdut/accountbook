package nt.ddeoid.accountbook.security.lock.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    // v0.4.5 修复 + 防御:吃掉 wizard 期间的 back 键。
    //
    // 用户报告"再次输入 PIN 后又切换到再次输入 PIN 界面,循环",我在 emulator 上复现
    // 不到状态机循环 —— 但发现 **按 back 键会把整个 activity pop 到 launcher**(因为
    // SetupWizard 是 startDestination,backstack 只有它一个)。app 退出后用户重开 →
    // bootstrap 看到 NeedsSetup → 重启 wizard → 用户从 step 1 重新走到 step 3。
    // 用户感知就是"又到再次输入 PIN 了"。
    //
    // 修法:用 BackHandler 吞掉 step 2-6 期间的 back 按键(Welcome 仍允许 back,
    // 因为 Welcome 还有 Skip 按钮是合法的退出路径)。step 2 之后用户已经做了不可逆
    // 的承诺(键入了 8 位 PIN),这时候中断 → 进程退出 → 重新走一遍 wizard 是用户
    // 真正不想要的体验;安全上不会变差(只是不让"看起来在循环"了,实际的 wizard
    // 仍然在内存里跑)。
    BackHandler(enabled = step != SetupStep.Welcome) {
        android.util.Log.d(
            "SetupWizardScreen",
            "BACK 键被吃掉:step=$step 还在 wizard 中,继续等待用户完成",
        )
    }

    // v0.4.3:之前用 LaunchedEffect 把 step 3→4 的 transition 跟 crypto 完成解耦,
    // 想避免"按完最后一位 → 空白 step 4 → 5s 后出词"的卡顿感。但 LaunchedEffect
    // 加上 `step` 作 key 之后,在某些 recomposition 路径下会触发意外的 transition,
    // 用户报告"再次输入 PIN 之后又切换到再次输入 PIN 界面,循环了"。
    //
    // v0.4.4 简化:立即 step 3→4 切换(按完最后一位马上进 step 4),busy spinner
    // 放在 [MnemonicDisplayStep] 上 —— words 空 + isInitializing=true 时显示。
    // 用户看到的是"按完最后一位 → 进 step 4 → spinner → 12 个词出现",路径直接。

    // v0.4.5 诊断日志:用户报告"勾选生物识别 + 再次输入 PIN 后又切换到再次输入 PIN 界面,循环",
    // 每个 step 转换都打 Log.d,让用户能抓 logcat 看到底是哪条路径在跑。
    android.util.Log.d("SetupWizardScreen", "compose: step=$step isInitializing=${state.isInitializing} words.size=${state.mnemonicWords.size}")

    when (step) {
        SetupStep.Welcome -> WelcomeStep(
            onStart = {
                android.util.Log.d("SetupWizardScreen", "step transition: Welcome → PinEntry (onStart)")
                step = SetupStep.PinEntry
            },
            onSkip = viewModel::onSkipSetup,
            modifier = modifier,
        )
        SetupStep.PinEntry -> PinEntryStep(
            onPinAccepted = {
                android.util.Log.d("SetupWizardScreen", "step transition: PinEntry → ConfirmAndBiometric (onPinAccepted)")
                step = SetupStep.ConfirmAndBiometric
            },
            viewModel = viewModel,
            modifier = modifier,
        )
        SetupStep.ConfirmAndBiometric -> ConfirmAndBiometricStep(
            onConfirmed = {
                android.util.Log.d(
                    "SetupWizardScreen",
                    "step transition: ConfirmAndBiometric → MnemonicDisplay (onConfirmed)",
                )
                viewModel.onEnterMnemonicStep()
                // 立即进 step 4;busy spinner 在 MnemonicDisplayStep 里(words 空 + isInitializing=true)
                step = SetupStep.MnemonicDisplay
            },
            onMismatch = {
                android.util.Log.d("SetupWizardScreen", "step transition: ConfirmAndBiometric → PinEntry (onMismatch)")
                step = SetupStep.PinEntry
            },
            viewModel = viewModel,
            modifier = modifier,
        )
        SetupStep.MnemonicDisplay -> MnemonicDisplayStep(
            words = state.mnemonicWords,
            isInitializing = state.isInitializing,
            onAcknowledged = {
                android.util.Log.d("SetupWizardScreen", "step transition: MnemonicDisplay → MnemonicVerify (onAcknowledged)")
                step = SetupStep.MnemonicVerify
            },
            modifier = modifier,
        )
        SetupStep.MnemonicVerify -> MnemonicVerifyStep(
            targetIndex = state.verificationTargetIndex,
            onSubmit = { position, word ->
                val ok = viewModel.onVerificationWordSubmitted(position, word)
                android.util.Log.d("SetupWizardScreen", "MnemonicVerify onSubmit: position=$position ok=$ok")
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
                android.util.Log.d(
                    "ConfirmAndBiometricStep",
                    "onSubmit: raw.length=${pin.size} biometricChecked=$biometricChecked",
                )
                if (viewModel.onPinConfirmed(pin)) {
                    android.util.Log.d(
                        "ConfirmAndBiometricStep",
                        "onPinConfirmed → true,biometricEnabled 设成 $biometricChecked",
                    )
                    mismatchError = false
                    viewModel.onBiometricToggled(biometricChecked)
                    onConfirmed()
                } else {
                    android.util.Log.d("ConfirmAndBiometricStep", "onPinConfirmed → false (mismatch or pin=null)")
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
    isInitializing: Boolean,
    onAcknowledged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    android.util.Log.d(
        "MnemonicDisplayStep",
        "compose: words.size=${words.size} isInitializing=$isInitializing",
    )
    val context = LocalContext.current

    // v0.5.0+ 把 12 词渲染成图保存到相册。点击 → 后台渲染 + 写盘 + Toast
    // 反馈。Android 9 及以下需要 WRITE_EXTERNAL_STORAGE 运行时权限;
    // Android 10+ 走 MediaStore 不需要。
    val storagePermission = Manifest.permission.WRITE_EXTERNAL_STORAGE
    val needsLegacyPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            if (granted) {
                triggerMnemonicSave(context, words)
            } else {
                Toast.makeText(
                    context,
                    context.getString(R.string.wizard_mnemonic_image_permission_needed),
                    Toast.LENGTH_LONG,
                ).show()
            }
        },
    )

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
        when {
            words.isNotEmpty() -> {
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

                // v0.5.0+:把 12 词保存成图片到相册。
                // Android 10+:MediaStore scoped storage,无需权限。
                // Android 9-:走 legacy storage,需要 WRITE_EXTERNAL_STORAGE 运行时权限。
                OutlinedButton(
                    onClick = {
                        if (needsLegacyPermission &&
                            ContextCompat.checkSelfPermission(
                                context, storagePermission,
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            permissionLauncher.launch(storagePermission)
                        } else {
                            triggerMnemonicSave(context, words)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.Default.Image,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.wizard_mnemonic_save_image))
                }

                Spacer(Modifier.height(8.dp))
                Button(onClick = onAcknowledged, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.wizard_mnemonic_ack))
                }
            }
            isInitializing -> {
                // v0.4.3 修复 #48:按完最后一位立即进 step 4;crypto 在 IO 上跑,
                // 期间展示 spinner + "正在准备加密密钥……"。crypto 完成后 _state.update
                // populate words → 跳到上面的 word grid 分支。
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    CircularProgressIndicator()
                    Text(
                        "正在准备加密密钥……",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> {
                // crypto 失败的兜底 —— 理论上不会到这(KeyVault 已兜底所有 keystore
                // 异常 + ViewModel catch 块 reset isInitializing),真到了给个提示。
                // 完整重试 UI 是后续 issue。
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "加密密钥生成失败",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(
                        onClick = onAcknowledged,
                        enabled = false,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.wizard_mnemonic_ack))
                    }
                }
            }
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

// --- helper ---------------------------------------------------------

/**
 * 触发助记词图片保存。
 *
 * IO 操作(Canvas 绘制 + PNG 压缩 + MediaStore 写盘)走 IO 线程,完成切回主线程弹
 * Toast。在主线程直接渲染 + 写 PNG 实测在 emulator 上 ~200ms,不会卡 UI;但 12 词的
 * 字符串拼接 + 字体测量理论上 O(1),所以同步执行也安全,放 IO 是为了防御性。
 */
private fun triggerMnemonicSave(context: Context, words: List<String>) {
    val title = context.getString(R.string.wizard_mnemonic_image_title)
    GlobalScope.launch(Dispatchers.IO) {
        try {
            val displayName = MnemonicImageRenderer.buildDisplayName()
            val bitmap = MnemonicImageRenderer.renderMnemonicBitmap(words, title)
            val saved = MnemonicImageRenderer.saveBitmapToGallery(context, bitmap, displayName)
            bitmap.recycle()
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    context.getString(R.string.wizard_mnemonic_image_saved),
                    Toast.LENGTH_LONG,
                ).show()
                Log.d(
                    "MnemonicDisplayStep",
                    "saved mnemonic image: ${saved.displayPath}",
                )
            }
        } catch (e: SaveToGalleryException) {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    context.getString(
                        R.string.wizard_mnemonic_image_save_failed,
                        e.message ?: "unknown",
                    ),
                    Toast.LENGTH_LONG,
                ).show()
            }
        } catch (e: Throwable) {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    context.getString(
                        R.string.wizard_mnemonic_image_save_failed,
                        e.javaClass.simpleName,
                    ),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
}
