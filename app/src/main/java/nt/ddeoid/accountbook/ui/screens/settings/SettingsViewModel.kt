package nt.ddeoid.accountbook.ui.screens.settings

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nt.ddeoid.accountbook.data.export.BackupExporter
import nt.ddeoid.accountbook.data.export.BackupImporter
import nt.ddeoid.accountbook.data.export.codec.CsvBackupCodec
import nt.ddeoid.accountbook.data.export.codec.JsonBackupCodec
import nt.ddeoid.accountbook.data.export.model.AccountBookBackup
import nt.ddeoid.accountbook.data.export.model.BackupSummary
import javax.inject.Inject

/**
 * 设置页 ViewModel —— 串联"导出 / 导入"两个流程。
 *
 * 流程:
 *
 * ```
 * (用户点按钮)
 *   ├─ 导出 → onRequestExport(format)
 *   │       → 把 snapshot 装进 state,显示确认 dialog
 *   │       → 用户点确认 → onConfirmExport(uri)
 *   │                       → ContentResolver 写文件 → snackbar
 *   │
 *   └─ 导入 → onRequestImport(uri, isCsv)
 *           → 读 uri + 解析 → 显示确认 dialog
 *           → 用户点确认 → onConfirmImport()
 *                          → BackupImporter.import → snackbar
 * ```
 *
 * 这里不直接调 SAF;ViewModel 只接收 URI + ContentResolver,SAF ActivityResult
 * 由 Composable 层 rememberLauncherForActivityResult 触发。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val backupExporter: BackupExporter,
    private val backupImporter: BackupImporter,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    // --- 导出 ---------------------------------------------------------------

    fun onRequestExport(format: ExportFormat) {
        viewModelScope.launch {
            try {
                val snapshot = backupExporter.snapshot()
                if (snapshot.accounts.isEmpty()) {
                    _state.update {
                        it.copy(
                            pendingExport = null,
                            transientMessage = TransientMessage.NoData,
                        )
                    }
                    return@launch
                }
                _state.update {
                    it.copy(
                        pendingExport = PendingExport(format, snapshot),
                        transientMessage = null,
                    )
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(transientMessage = TransientMessage.ExportFailed(t.message ?: t::class.simpleName.orEmpty()))
                }
            }
        }
    }

    fun onConfirmExport(resolver: ContentResolver, uri: Uri, format: ExportFormat) {
        val snapshot = _state.value.pendingExport?.snapshot ?: return
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.Default) {
                    when (format) {
                        ExportFormat.JSON -> JsonBackupCodec.encode(snapshot)
                        ExportFormat.CSV -> CsvBackupCodec.encode(snapshot)
                    }
                }
                withContext(Dispatchers.IO) {
                    resolver.openOutputStream(uri, "w")?.use { os ->
                        os.write(text.toByteArray(Charsets.UTF_8))
                        os.flush()
                    } ?: error("无法打开输出流")
                }
                _state.update {
                    it.copy(
                        pendingExport = null,
                        transientMessage = TransientMessage.ExportDone(snapshot.accounts.size),
                    )
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        pendingExport = null,
                        transientMessage = TransientMessage.ExportFailed(t.message ?: t::class.simpleName.orEmpty()),
                    )
                }
            }
        }
    }

    fun dismissExportDialog() {
        _state.update { it.copy(pendingExport = null) }
    }

    // --- 导入 ---------------------------------------------------------------

    fun onRequestImport(resolver: ContentResolver, uri: Uri, format: ExportFormat) {
        viewModelScope.launch {
            try {
                val (backup, isCsv) = withContext(Dispatchers.IO) {
                    val text = resolver.openInputStream(uri)?.use { input ->
                        input.readBytes().toString(Charsets.UTF_8)
                    } ?: error("无法打开输入流")
                    when (format) {
                        ExportFormat.JSON -> JsonBackupCodec.decode(text) to false
                        ExportFormat.CSV -> CsvBackupCodec.decode(text) to true
                    }
                }
                if (backup.accounts.isEmpty()) {
                    _state.update {
                        it.copy(transientMessage = TransientMessage.ImportFailed("备份里没有账号"))
                    }
                    return@launch
                }
                _state.update {
                    it.copy(
                        pendingImport = PendingImport(backup, isCsv),
                        transientMessage = null,
                    )
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(transientMessage = TransientMessage.ImportFailed(t.message ?: t::class.simpleName.orEmpty()))
                }
            }
        }
    }

    fun onConfirmImport() {
        val pending = _state.value.pendingImport ?: return
        viewModelScope.launch {
            try {
                val summary: BackupSummary = backupImporter.import(pending.backup, isCsv = pending.isCsv)
                _state.update {
                    it.copy(
                        pendingImport = null,
                        transientMessage = TransientMessage.ImportDone(summary),
                    )
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        pendingImport = null,
                        transientMessage = TransientMessage.ImportFailed(t.message ?: t::class.simpleName.orEmpty()),
                    )
                }
            }
        }
    }

    fun dismissImportDialog() {
        _state.update { it.copy(pendingImport = null) }
    }

    fun consumeTransientMessage() {
        _state.update { it.copy(transientMessage = null) }
    }
}

// --- UI 状态 -------------------------------------------------------------

data class SettingsUiState(
    val pendingExport: PendingExport? = null,
    val pendingImport: PendingImport? = null,
    val transientMessage: TransientMessage? = null,
)

data class PendingExport(
    val format: ExportFormat,
    val snapshot: AccountBookBackup,
)

data class PendingImport(
    val backup: AccountBookBackup,
    val isCsv: Boolean,
)

enum class ExportFormat(val mime: String, val ext: String) {
    JSON(JsonBackupCodec.MIME_TYPE, JsonBackupCodec.FILE_EXTENSION),
    CSV(CsvBackupCodec.MIME_TYPE, CsvBackupCodec.FILE_EXTENSION),
}

sealed interface TransientMessage {
    data class ExportDone(val count: Int) : TransientMessage
    data class ImportDone(val summary: BackupSummary) : TransientMessage
    data class ExportFailed(val error: String) : TransientMessage
    data class ImportFailed(val error: String) : TransientMessage
    data object NoData : TransientMessage
}
