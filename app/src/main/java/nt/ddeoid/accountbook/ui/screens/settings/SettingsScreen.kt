package nt.ddeoid.accountbook.ui.screens.settings

import android.content.ContentResolver
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import nt.ddeoid.accountbook.BuildConfig
import nt.ddeoid.accountbook.R

/**
 * 设置页 —— 三个区块:
 *
 * - **数据**:导出 JSON / CSV、导入 JSON / CSV。
 * - **外观**:主题占位(Phase 5 接 DataStore 后再实装)。
 * - **关于**:版本号 + 一句话简介。
 *
 * 导出/导入用 Storage Access Framework:
 * - CreateDocument 拿用户指定的输出位置 → [SettingsViewModel.onConfirmExport]。
 * - OpenDocument 拿用户选择的输入文件 → [SettingsViewModel.onRequestImport]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val resolver = context.contentResolver
    val snackbarHostState = remember { SnackbarHostState() }

    // 等待 confirm 后真正写文件的 launcher:每次 confirm 都临时记下要写的 format
    var pendingExportUri by remember { androidx.compose.runtime.mutableStateOf<Pair<android.net.Uri, ExportFormat>?>(null) }
    var pendingImportUri by remember { androidx.compose.runtime.mutableStateOf<Pair<android.net.Uri, ExportFormat>?>(null) }
    // 记录用户在按钮里选了哪种格式的导入,等 SAF 回来用
    var requestedImportFormat by remember { androidx.compose.runtime.mutableStateOf<ExportFormat?>(null) }

    val createJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ExportFormat.JSON.mime),
    ) { uri ->
        if (uri != null) pendingExportUri = uri to ExportFormat.JSON
    }
    val createCsvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ExportFormat.CSV.mime),
    ) { uri ->
        if (uri != null) pendingExportUri = uri to ExportFormat.CSV
    }
    val openAnyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null && pendingImportUri == null) {
            pendingImportUri = uri to (requestedImportFormat ?: ExportFormat.JSON)
        }
    }

    // 拿到 create-launcher 的 URI 后,真正调 ViewModel 写文件
    LaunchedEffect(pendingExportUri) {
        pendingExportUri?.let { (uri, fmt) ->
            viewModel.onConfirmExport(resolver, uri, fmt)
            pendingExportUri = null
        }
    }
    // 拿到 open-launcher 的 URI 后,按用户刚才选的格式解析
    LaunchedEffect(pendingImportUri) {
        pendingImportUri?.let { (uri, fmt) ->
            viewModel.onRequestImport(resolver, uri, fmt)
            pendingImportUri = null
            requestedImportFormat = null
        }
    }

    // snackbar
    LaunchedEffect(state.transientMessage) {
        val msg = state.transientMessage ?: return@LaunchedEffect
        val text = when (msg) {
            is TransientMessage.ExportDone -> context.getString(R.string.snackbar_export_done, msg.count)
            is TransientMessage.ImportDone -> context.getString(
                R.string.snackbar_import_done,
                msg.summary.accountsAdded,
                msg.summary.accountsSkipped,
            )
            is TransientMessage.ExportFailed -> context.getString(R.string.snackbar_export_failed, msg.error)
            is TransientMessage.ImportFailed -> context.getString(R.string.snackbar_import_failed, msg.error)
            TransientMessage.NoData -> context.getString(R.string.dialog_export_no_data)
        }
        snackbarHostState.showSnackbar(text)
        viewModel.consumeTransientMessage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            DataSection(
                onExportJson = { viewModel.onRequestExport(ExportFormat.JSON) },
                onExportCsv = { viewModel.onRequestExport(ExportFormat.CSV) },
                onImportJson = {
                    requestedImportFormat = ExportFormat.JSON
                    openAnyLauncher.launch(arrayOf(ExportFormat.JSON.mime, ExportFormat.CSV.mime, "*/*"))
                },
                onImportCsv = {
                    requestedImportFormat = ExportFormat.CSV
                    openAnyLauncher.launch(arrayOf(ExportFormat.CSV.mime, ExportFormat.JSON.mime, "*/*"))
                },
            )
            AppearanceSection()
            AboutSection()
        }
    }

    // 导出确认 dialog
    state.pendingExport?.let { pending ->
        AlertDialog(
            onDismissRequest = viewModel::dismissExportDialog,
            title = { Text(stringResource(R.string.dialog_export_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.dialog_export_message,
                        pending.snapshot.accounts.size,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val format = pending.format
                    // 注意:这里不要 dismissExportDialog,SAF 返回 URI 后 ViewModel
                    // 会从 state 里取 snapshot,完了再 dismiss + 发 snackbar。
                    when (format) {
                        ExportFormat.JSON -> {
                            val suggested = context.getString(R.string.settings_export_filename_json)
                            createJsonLauncher.launch(suggested)
                        }
                        ExportFormat.CSV -> {
                            val suggested = context.getString(R.string.settings_export_filename_csv)
                            createCsvLauncher.launch(suggested)
                        }
                    }
                }) { Text(stringResource(R.string.dialog_export_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissExportDialog) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    // 导入确认 dialog
    state.pendingImport?.let { pending ->
        AlertDialog(
            onDismissRequest = viewModel::dismissImportDialog,
            title = { Text(stringResource(R.string.dialog_import_title)) },
            text = {
                if (pending.isCsv) {
                    Text(
                        stringResource(
                            R.string.dialog_import_message_csv,
                            pending.backup.accounts.size,
                        ),
                    )
                } else {
                    Text(
                        stringResource(
                            R.string.dialog_import_message_json,
                            pending.backup.accounts.size,
                            pending.backup.tags.size,
                            pending.backup.platforms.size,
                        ),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::onConfirmImport) {
                    Text(stringResource(R.string.dialog_import_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissImportDialog) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun DataSection(
    onExportJson: () -> Unit,
    onExportCsv: () -> Unit,
    onImportJson: () -> Unit,
    onImportCsv: () -> Unit,
) {
    SectionCard(title = stringResource(R.string.settings_section_data)) {
        ActionRow(
            icon = Icons.Default.Download,
            label = stringResource(R.string.settings_export_json),
            onClick = onExportJson,
        )
        HorizontalDivider()
        ActionRow(
            icon = Icons.Default.Download,
            label = stringResource(R.string.settings_export_csv),
            onClick = onExportCsv,
        )
        HorizontalDivider()
        ActionRow(
            icon = Icons.Default.Upload,
            label = stringResource(R.string.settings_import_json),
            onClick = onImportJson,
        )
        HorizontalDivider()
        ActionRow(
            icon = Icons.Default.Upload,
            label = stringResource(R.string.settings_import_csv),
            onClick = onImportCsv,
        )
    }
}

@Composable
private fun AppearanceSection() {
    SectionCard(title = stringResource(R.string.settings_section_appearance)) {
        Text(
            text = stringResource(R.string.theme_system),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun AboutSection() {
    SectionCard(title = stringResource(R.string.settings_section_about)) {
        Text(
            text = stringResource(R.string.settings_about_summary, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
            )
            content()
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.padding(end = 12.dp))
        Text(label)
    }
}

@Suppress("unused")
private fun keepResolverImport(r: ContentResolver) = r // 占位避免 ContentResolver 被认为 unused
