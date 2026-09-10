package nt.ddeoid.accountbook.ui.screens.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import nt.ddeoid.accountbook.R
import nt.ddeoid.accountbook.data.local.dao.AccountWithTags
import nt.ddeoid.accountbook.data.local.entity.AccountType
import nt.ddeoid.accountbook.ui.screens.home.components.AccountActionMenu
import nt.ddeoid.accountbook.ui.screens.home.components.AccountEditBottomSheet
import nt.ddeoid.accountbook.ui.screens.home.components.AccountGroupCard
import nt.ddeoid.accountbook.ui.screens.home.components.HomeTopBar

/**
 * Phase 2 主界面:
 *
 * ```
 * TopAppBar
 * ┌────────────────────────────┐
 * │ HomeTopBar(搜索 / 标签 / 排序)│
 * ├────────────────────────────┤
 * │ 共 N 条                     │
 * │ AccountGroupCard (平台A)    │
 * │   AccountListItem ...       │
 * │ AccountGroupCard (平台B)    │
 * │   AccountListItem ...       │
 * └────────────────────────────┘
 *                       [FAB +]
 * ```
 *
 * 长按 ListItem 弹出 [AccountActionMenu],点 FAB / 菜单的"编辑"会打开 [AccountEditBottomSheet]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToDetail: (String) -> Unit,
    onNavigateToSettings: () -> Unit,
    pendingEditId: String? = null,
    onPendingEditConsumed: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val platformSuggestions by viewModel.platformSuggestions.collectAsState()
    val context = LocalContext.current

    // 编辑态。null = 没在编辑;非 null = 加载这个 id 的现有记录做编辑。
    var sheetTarget by remember { mutableStateOf<SheetTarget?>(null) }
    var actionMenuTarget by remember { mutableStateOf<String?>(null) }

    // Bug #40:详情页 FAB → 回主页打开编辑 BottomSheet 的桥。
    // pendingEditId 从 null 变非 null 时,触发 sheetTarget;消费后写回 null 让下次再来也能触发。
    LaunchedEffect(pendingEditId) {
        val id = pendingEditId ?: return@LaunchedEffect
        sheetTarget = SheetTarget.Edit(id)
        onPendingEditConsumed()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings_title))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { sheetTarget = SheetTarget.New },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.fab_new_account)) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            HomeTopBar(
                filter = state.filter,
                allTags = state.allTags,
                onSearchChange = viewModel::onSearchChange,
                onToggleTag = viewModel::toggleTag,
                onClearTags = viewModel::clearTagFilter,
                onSortChange = viewModel::setSortOrder,
            )

            // 列表 / 空状态
            if (state.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("…") }
            } else if (state.groups.isEmpty()) {
                EmptyState(
                    hasAnyAccount = state.totalAfterFilter == 0 && state.filter.let {
                        it.searchQuery.isNotBlank() || it.selectedTagIds.isNotEmpty()
                    },
                    totalActive = state.totalAfterFilter,
                )
            } else {
                ListBody(
                    state = state,
                    onItemClick = onNavigateToDetail,
                    onItemLongPress = { id -> actionMenuTarget = id },
                )
            }
        }
    }

    // 编辑 BottomSheet
    sheetTarget?.let { target ->
        val existing: AccountWithTags? = when (target) {
            is SheetTarget.New -> null
            is SheetTarget.Edit -> state.groups
                .flatMap { it.items }
                .firstOrNull { it.account.id == target.existingId }
        }
        AccountEditBottomSheet(
            isEdit = existing != null,
            initialPlatform = existing?.account?.platform.orEmpty(),
            initialAccount = existing?.account?.account.orEmpty(),
            initialAccountType = existing?.account?.accountType ?: AccountType.PHONE,
            initialRegisteredAt = existing?.account?.registeredAt,
            initialNotes = existing?.account?.notes.orEmpty(),
            initialPassword = existing?.account?.password,
            initialTagIds = existing?.tags?.map { it.id }?.toSet().orEmpty(),
            allTags = state.allTags,
            platformSuggestions = platformSuggestions,
            onDismiss = { sheetTarget = null },
            onSubmit = { platform, account, type, registeredAt, notes, password, tagIds ->
                viewModel.submitAccount(
                    id = existing?.account?.id,
                    platform = platform,
                    account = account,
                    accountType = type,
                    registeredAt = registeredAt,
                    notes = notes,
                    password = password,
                    tagIds = tagIds,
                )
                sheetTarget = null
            },
        )
    }

    // 长按菜单
    actionMenuTarget?.let { id ->
        val row = state.groups.flatMap { it.items }.firstOrNull { it.account.id == id }
        if (row != null) {
            AccountActionMenu(
                expanded = true,
                isActive = row.account.isActive,
                platform = row.account.platform,
                account = row.account.account,
                onDismiss = { actionMenuTarget = null },
                onCopy = {
                    copyToClipboard(context, row.account.account)
                    Toast.makeText(
                        context,
                        context.getString(R.string.snackbar_copied),
                        Toast.LENGTH_SHORT,
                    ).show()
                },
                onEdit = {
                    sheetTarget = SheetTarget.Edit(id)
                },
                onToggleActive = {
                    viewModel.setActive(id, !row.account.isActive)
                },
                onDelete = {
                    viewModel.deleteAccount(id)
                },
            )
        } else {
            // 列表里已经没了(其他设备/线程删了),直接清掉菜单
            actionMenuTarget = null
        }
    }
}

private sealed interface SheetTarget {
    data object New : SheetTarget
    data class Edit(val existingId: String) : SheetTarget
}

@Composable
private fun ListBody(
    state: HomeUiState,
    onItemClick: (String) -> Unit,
    onItemLongPress: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.account_count_summary, state.totalAfterFilter),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items = state.groups, key = { it.platform }) { group ->
                AccountGroupCard(
                    platform = group.platform,
                    items = group.items,
                    onItemClick = onItemClick,
                    onItemLongPress = onItemLongPress,
                )
            }
            // 给 FAB 让点底的位置
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun EmptyState(hasAnyAccount: Boolean, totalActive: Int) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(
                    if (totalActive == 0 && !hasAnyAccount) R.string.empty_title
                    else R.string.empty_after_filter_title,
                ),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(
                    if (totalActive == 0 && !hasAnyAccount) R.string.empty_subtitle
                    else R.string.empty_after_filter_subtitle,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    nt.ddeoid.accountbook.security.clipboard.SensitiveClipboard.copy(
        context = context,
        text = text,
        label = "AccountBook account",
    )
}
