package nt.ddeoid.accountbook.ui.screens.detail

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import nt.ddeoid.accountbook.security.clipboard.SensitiveClipboard
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import nt.ddeoid.accountbook.R
import nt.ddeoid.accountbook.data.local.dao.AccountWithTags
import nt.ddeoid.accountbook.data.local.entity.AccountType
import nt.ddeoid.accountbook.data.local.entity.TagEntity
import nt.ddeoid.accountbook.util.formatTimestamp

/**
 * 单条账号的详情页。
 *
 * - TopAppBar:返回 + 标题。
 * - 主区:平台 / 账号 / 类型 / 注册时间 / 备注 / 标签 / 创建 / 更新 / 状态。
 * - FAB:编辑。Phase 2 实现方式:点 FAB 把 accountId 传给 NavHost,NavHost 回 Home
 *   并把 AccountEditBottomSheet 以编辑模式打开。Phase 3+ 可单独开编辑页路由。
 *
 * 注意:Phase 2 没单独做编辑页;实际编辑入口仍然走 HomeScreen 的 BottomSheet,
 * 详情页先给一个 Read-only 视图 + 复制账号按钮。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountDetailScreen(
    onBack: () -> Unit,
    onEditRequested: (String) -> Unit = {},
    viewModel: AccountDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        floatingActionButton = {
            if (state is AccountDetailUiState.Loaded) {
                FloatingActionButton(onClick = { onEditRequested(viewModel.accountId) }) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.action_edit))
                }
            }
        },
    ) { padding ->
        when (val current = state) {
            AccountDetailUiState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) { Text("…", style = MaterialTheme.typography.bodyLarge) }
            }
            AccountDetailUiState.Deleted -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.detail_deleted),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            is AccountDetailUiState.Loaded -> {
                DetailContent(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    row = current.row,
                    onCopyAccount = {
                        copyToClipboard(context, current.row.account.account)
                        Toast.makeText(
                            context,
                            context.getString(R.string.snackbar_copied),
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                    onCopyPassword = { plain ->
                        SensitiveClipboard.copy(
                            context = context,
                            text = plain,
                            label = "AccountBook password",
                        )
                        Toast.makeText(
                            context,
                            context.getString(R.string.snackbar_password_copied),
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                    onDeleteClick = { showDeleteConfirm = true },
                )
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.dialog_delete_title)) },
            text = {
                val row = (state as? AccountDetailUiState.Loaded)?.row
                if (row != null) {
                    Text(
                        stringResource(
                            R.string.dialog_delete_message,
                            row.account.platform,
                            row.account.account,
                        ),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    viewModel.delete()
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun DetailContent(
    modifier: Modifier,
    row: AccountWithTags,
    onCopyAccount: () -> Unit,
    onCopyPassword: (String) -> Unit,
    onDeleteClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 平台(头部大标题)
        Text(
            text = row.account.platform,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        )

        // 账号 + 类型图标 + 复制
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer,
                            RoundedCornerShape(20.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = when (row.account.accountType) {
                            AccountType.PHONE -> Icons.Default.Phone
                            AccountType.EMAIL -> Icons.Default.Email
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Spacer(Modifier.size(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.account.account,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(
                            when (row.account.accountType) {
                                AccountType.PHONE -> R.string.field_account_type_phone
                                AccountType.EMAIL -> R.string.field_account_type_email
                            },
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onCopyAccount) {
                    Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.menu_copy_account))
                }
            }
        }

        // 密码 Card(v0.5.0 新增)。只在 password 不为 null 时渲染;默认掩码,
        // 👁 切换明文 / 📋 走 SensitiveClipboard.copy()(60s 自动清空 +
        // Android 13+ EXTRA_IS_SENSITIVE)。
        row.account.password?.let { password ->
            var passwordVisible by remember { mutableStateOf(false) }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                MaterialTheme.colorScheme.tertiaryContainer,
                                RoundedCornerShape(20.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                    Spacer(Modifier.size(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (passwordVisible) {
                                password
                            } else {
                                // 8 个点 —— 常用密码掩码视觉长度。
                                "••••••••"
                            },
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(R.string.detail_field_password),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
                    IconButton(onClick = { onCopyPassword(password) }) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.action_copy_password),
                        )
                    }
                }
            }
        }

        DetailRow(
            label = stringResource(R.string.field_registered_at),
            value = row.account.registeredAt
                ?: stringResource(R.string.field_registered_at_unknown),
        )
        if (row.account.notes.isNotBlank()) {
            DetailRow(label = stringResource(R.string.field_notes), value = row.account.notes)
        }

        // 标签
        if (row.tags.isNotEmpty()) {
            Text(
                text = stringResource(R.string.detail_field_tags),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                row.tags.forEach { tag ->
                    TagChip(tag)
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        DetailRow(
            label = stringResource(R.string.detail_field_created_at),
            value = formatTimestamp(row.account.createdAt),
        )
        DetailRow(
            label = stringResource(R.string.detail_field_updated_at),
            value = formatTimestamp(row.account.updatedAt),
        )
        DetailRow(
            label = stringResource(R.string.detail_field_status),
            value = stringResource(
                if (row.account.isActive) R.string.status_active else R.string.status_inactive,
            ),
        )

        Spacer(Modifier.height(8.dp))

        TextButton(
            onClick = onDeleteClick,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.action_delete)) }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            modifier = Modifier
                .weight(0.4f)
                .padding(end = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            modifier = Modifier.weight(0.6f),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun TagChip(tag: TagEntity) {
    val parsedColor = runCatching {
        Color(android.graphics.Color.parseColor(tag.color))
    }.getOrDefault(MaterialTheme.colorScheme.primary)
    AssistChip(
        onClick = { /* no-op */ },
        label = { Text(tag.name, style = MaterialTheme.typography.labelSmall) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = parsedColor.copy(alpha = 0.2f),
            labelColor = parsedColor,
        ),
    )
}

private fun copyToClipboard(context: Context, text: String) {
    nt.ddeoid.accountbook.security.clipboard.SensitiveClipboard.copy(
        context = context,
        text = text,
        label = "AccountBook account",
    )
}
