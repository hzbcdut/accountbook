package nt.ddeoid.accountbook.ui.screens.home.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import nt.ddeoid.accountbook.R
import nt.ddeoid.accountbook.data.local.entity.AccountType
import nt.ddeoid.accountbook.data.local.entity.PlatformCatalogEntity
import nt.ddeoid.accountbook.data.local.entity.TagEntity
import nt.ddeoid.accountbook.util.formatRegisteredDate
import nt.ddeoid.accountbook.util.parseRegisteredDate

/**
 * 新增 / 编辑账号的底部表单。
 *
 * Phase 2:全部字段一次性提交,不做内联校验动画,只做"保存时检验"。
 *
 * ## v0.5.0 新增 `password` 字段
 *
 * - 初始值从 [initialPassword] 拿(可为 null / 空);提交时 `password.takeIf { it.isNotBlank() }`
 *   转成 nullable String,空串也视作"未填"。
 * - UI 默认 [PasswordVisualTransformation] 掩码,trailingIcon 是 👁 / 🙈 toggle。
 * - 不参与错误校验 —— password 是可选字段。
 * - **不**进 [HomeUiState],拿到密码后立刻通过 [onSubmit] 转给 Repository,**不**进
 *   StateFlow 避免进 SavedStateHandle(虽然 backup 已禁,但 defense in depth)。
 *
 * @param isEdit true = 编辑模式(标题 + 初始值由 [initialPlatform] 等提供),false = 新增。
 * @param platformSuggestions 平台自动补全候选,来自 [PlatformCatalogDao.observeAll] 的最新快照。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountEditBottomSheet(
    isEdit: Boolean,
    initialPlatform: String,
    initialAccount: String,
    initialAccountType: AccountType,
    initialRegisteredAt: String?,
    initialNotes: String,
    initialPassword: String?,
    initialTagIds: Set<String>,
    allTags: List<TagEntity>,
    platformSuggestions: List<PlatformCatalogEntity>,
    onDismiss: () -> Unit,
    onSubmit: (
        platform: String,
        account: String,
        accountType: AccountType,
        registeredAt: String?,
        notes: String,
        password: String?,
        tagIds: List<String>,
    ) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scrollState = rememberScrollState()

    var platform by remember { mutableStateOf(initialPlatform) }
    var account by remember { mutableStateOf(initialAccount) }
    var accountType by remember { mutableStateOf(initialAccountType) }
    var registeredAt by remember { mutableStateOf(initialRegisteredAt.orEmpty()) }
    var notes by remember { mutableStateOf(initialNotes) }
    var password by remember { mutableStateOf(initialPassword.orEmpty()) }
    var passwordVisible by remember { mutableStateOf(false) }
    var selectedTagIds by remember { mutableStateOf(initialTagIds) }

    var platformDropdownOpen by remember { mutableStateOf(false) }
    var datePickerOpen by remember { mutableStateOf(false) }

    var platformError by remember { mutableStateOf<String?>(null) }
    var accountError by remember { mutableStateOf<String?>(null) }

    // 自动根据当前输入过滤平台建议
    val filteredSuggestions by remember(platformSuggestions) {
        derivedStateOf {
            if (platform.isBlank()) platformSuggestions
            else platformSuggestions.filter { it.name.contains(platform.trim(), ignoreCase = true) }
        }
    }

    val keyboard = LocalSoftwareKeyboardController.current
    val platformRequiredError = stringResource(R.string.error_platform_required)
    val accountRequiredError = stringResource(R.string.error_account_required)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(
                    if (isEdit) R.string.sheet_edit_title else R.string.sheet_new_title,
                ),
                style = MaterialTheme.typography.titleLarge,
            )

            // 平台:ExposedDropdownMenuBox
            ExposedDropdownMenuBox(
                expanded = platformDropdownOpen,
                onExpandedChange = { platformDropdownOpen = it },
            ) {
                OutlinedTextField(
                    value = platform,
                    onValueChange = {
                        platform = it
                        platformError = null
                    },
                    label = { Text(stringResource(R.string.field_platform)) },
                    placeholder = { Text(stringResource(R.string.hint_platform)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = platformDropdownOpen) },
                    isError = platformError != null,
                    supportingText = platformError?.let { { Text(it) } },
                    singleLine = true,
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = platformDropdownOpen,
                    onDismissRequest = { platformDropdownOpen = false },
                ) {
                    if (filteredSuggestions.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text(platform.trim().ifBlank { "(空)" }) },
                            onClick = {
                                platformDropdownOpen = false
                                // 自定义值已经写在 TextField 里,这里只关闭菜单。
                            },
                        )
                    } else {
                        filteredSuggestions.take(8).forEach { suggestion ->
                            DropdownMenuItem(
                                text = { Text(suggestion.name) },
                                onClick = {
                                    platform = suggestion.name
                                    platformError = null
                                    platformDropdownOpen = false
                                },
                            )
                        }
                    }
                }
            }

            // 账号
            OutlinedTextField(
                value = account,
                onValueChange = {
                    account = it
                    accountError = null
                },
                label = { Text(stringResource(R.string.field_account)) },
                placeholder = { Text(stringResource(R.string.hint_account)) },
                isError = accountError != null,
                supportingText = accountError?.let { { Text(it) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = when (accountType) {
                        AccountType.PHONE -> KeyboardType.Phone
                        AccountType.EMAIL -> KeyboardType.Email
                    },
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            // 账号类型 segmented
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                AccountType.entries.forEachIndexed { index, type ->
                    SegmentedButton(
                        selected = accountType == type,
                        onClick = { accountType = type },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = AccountType.entries.size),
                        label = {
                            Text(
                                stringResource(
                                    when (type) {
                                        AccountType.PHONE -> R.string.field_account_type_phone
                                        AccountType.EMAIL -> R.string.field_account_type_email
                                    },
                                ),
                            )
                        },
                    )
                }
            }

            // 备注
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text(stringResource(R.string.field_notes)) },
                placeholder = { Text(stringResource(R.string.hint_notes)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp),
                minLines = 2,
            )

            // 注册时间
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = registeredAt,
                    onValueChange = { new ->
                        registeredAt = new
                    },
                    label = { Text(stringResource(R.string.field_registered_at)) },
                    placeholder = { Text(stringResource(R.string.hint_registered_at)) },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = { datePickerOpen = true }) {
                            Icon(Icons.Default.CalendarToday, contentDescription = stringResource(R.string.action_pick_date))
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                if (registeredAt.isNotEmpty()) {
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    TextButton(onClick = { registeredAt = "" }) {
                        Text(stringResource(R.string.action_clear_date))
                    }
                }
            }

            // 密码(v0.5.0 新增)。可选,默认掩码,trailingIcon 切显示/隐藏。
            // 不做强度校验 —— 第一版保持简单。
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.field_password)) },
                placeholder = { Text(stringResource(R.string.hint_password)) },
                singleLine = true,
                visualTransformation = if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) {
                                Icons.Default.VisibilityOff
                            } else {
                                Icons.Default.Visibility
                            },
                            contentDescription = stringResource(
                                if (passwordVisible) {
                                    R.string.action_hide_password
                                } else {
                                    R.string.action_reveal_password
                                },
                            ),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            // 标签
            if (allTags.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.field_tags),
                    style = MaterialTheme.typography.titleSmall,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    allTags.forEach { tag ->
                        val selected = tag.id in selectedTagIds
                        FilterChip(
                            selected = selected,
                            onClick = {
                                selectedTagIds = selectedTagIds.toMutableSet().apply {
                                    if (!add(tag.id)) remove(tag.id)
                                }
                            },
                            label = { Text(tag.name) },
                        )
                    }
                }
            }

            // 操作
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, androidx.compose.ui.Alignment.End),
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
                Button(
                    onClick = {
                        keyboard?.hide()
                        val trimmedPlatform = platform.trim()
                        val trimmedAccount = account.trim()
                        var hasError = false
                        if (trimmedPlatform.isEmpty()) {
                            platformError = platformRequiredError
                            hasError = true
                        }
                        if (trimmedAccount.isEmpty()) {
                            accountError = accountRequiredError
                            hasError = true
                        }
                        if (!hasError) {
                            onSubmit(
                                trimmedPlatform,
                                trimmedAccount,
                                accountType,
                                registeredAt.takeIf { it.isNotBlank() },
                                notes.trim(),
                                password.takeIf { it.isNotBlank() },
                                selectedTagIds.toList(),
                            )
                        }
                    },
                ) { Text(stringResource(R.string.action_save)) }
            }
        }
    }

    if (datePickerOpen) {
        val initialMillis = parseRegisteredDate(registeredAt) ?: System.currentTimeMillis()
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { datePickerOpen = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        registeredAt = formatRegisteredDate(millis)
                    }
                    datePickerOpen = false
                }) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { datePickerOpen = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/**
 * 在 Composable 内取带格式化参数的字符串。
 * 这里只为 [AccountEditBottomSheet] 服务,简单包装避免传一堆 Context。
 */
@Composable
@Suppress("unused")
private fun contextString(@androidx.annotation.StringRes id: Int): String =
    stringResource(id)
