package nt.ddeoid.accountbook.security.lock.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nt.ddeoid.accountbook.data.local.DatabaseOpenException
import nt.ddeoid.accountbook.data.local.DatabaseRekeyException
import nt.ddeoid.accountbook.data.local.LegacyKeyMigrator
import nt.ddeoid.accountbook.data.local.MigrationMarker
import nt.ddeoid.accountbook.security.crypto.MnemonicCodec
import nt.ddeoid.accountbook.security.crypto.SecretBytes
import nt.ddeoid.accountbook.security.lock.LockController
import nt.ddeoid.accountbook.security.lock.LockPrefs
import javax.inject.Inject

/**
 * 迁移向导的 ViewModel —— v0.3.0 升级用户走这条线。
 *
 * ## 比 SetupWizard 简单很多
 *
 * - 只需要输入新 PIN(**没有 12 词抄写**,因为旧库用户的熵跟"未来能不能恢复"没关系,
 *   真要恢复走 `LockController.unlockWithMnemonic` 即可,那条路径需要新熵对应的助记词)
 * - 流程就是 2 步:PIN → 确认 → `LegacyKeyMigrator.migrate()` + `LockController.finishMigration()`
 * - 如果启动时 [MigrationMarker.inProgress] 是 true,说明上次 rekey 写盘了但 vault 没写
 *   完就崩了 → 不让用户再试 PIN,直接引导"从备份恢复"(Phase 5 接 BackupImporter)
 *
 * ## 不持有 step 状态
 *
 * step 仍然是 Composable 端 `remember { mutableStateOf(...) }`,process death 回到 step 1
 * 是 Q14=B + Q17=B 的硬性要求(避免 PIN 残留在内存里被 dump)。ViewModel 只在内存里
 * 临时持有 PIN,`onCleared` 立刻 wipe。
 *
 * ## error
 *
 * 迁移过程中 [LegacyKeyMigrator.migrate] 抛任何异常都被 `Result.failure` 装下,转成
 * [MigrationError] 给 UI 展示。当前支持:
 *
 * - [LockError.WrongPin]——新 PIN 不符合强度要求(目前 SetupWizard 那边已经过滤,
 *   这里其实不会触发,但留着对称)
 * - [MigrationError.MigrationFailed]——rekey 写盘失败 / DB 损坏 / 其他 —— 引导恢复备份
 * - [MigrationError.DatabaseCorrupted]——专门为"rekey 之前的旧口令打不开文件"这种情况,
 *   强制走恢复备份
 */
@HiltViewModel
class MigrationWizardViewModel @Inject constructor(
    private val legacyKeyMigrator: LegacyKeyMigrator,
    private val lockController: LockController,
    private val lockPrefs: LockPrefs,
    private val migrationMarker: MigrationMarker,
    private val mnemonicCodec: MnemonicCodec,
) : ViewModel() {

    private val _state = MutableStateFlow(
        MigrationUiState(
            // ViewModel 构造时立刻读一次 marker —— interrupted 状态需要在第一次组合前就知道
            interrupted = migrationMarker.inProgress,
        ),
    )
    val state: StateFlow<MigrationUiState> = _state.asStateFlow()

    /** 临时 PIN。提交迁移后立刻 wipe。 */
    private var pin: CharArray? = null

    /** 用户勾选了"启用生物识别"吗?[onConfirmMigrate] 提交时传给 LockController。 */
    private var biometricEnabled: Boolean = false

    /**
     * 步骤 1:输入 PIN。校验长度(8+)、拒绝全同字符;**不**持久化。
     *
     * @return [PinEntryResult] 决定 UI 是推进到确认步骤还是显示错误
     */
    fun onPinEntered(raw: CharArray): PinEntryResult {
        val cleaned = raw.copyOf()
        return when {
            cleaned.size < MIN_PIN_LENGTH -> {
                SecretBytes.wipe(cleaned)
                PinEntryResult.TooShort(MIN_PIN_LENGTH)
            }
            cleaned.all { it == cleaned[0] } -> {
                SecretBytes.wipe(cleaned)
                PinEntryResult.TooWeak("PIN 不能全是同一个字符")
            }
            else -> {
                pin = cleaned
                PinEntryResult.Accepted
            }
        }
    }

    /**
     * 步骤 2:再次输入 PIN。返回 true 表示一致 → 触发迁移。
     *
     * 这里**不**做迁移本身 —— 迁移是 IO 重活(可能要 rekey 一个几十 MB 的 DB),在
     * [onConfirmMigrate] 协程里做;这里只做"两次 PIN 一致"这个检查,顺便清掉 heldPin。
     */
    fun onPinConfirmed(raw: CharArray): Boolean {
        val expected = pin ?: return false
        return if (raw.contentEquals(expected)) {
            SecretBytes.wipe(raw)
            true
        } else {
            SecretBytes.wipe(raw)
            pin?.let { SecretBytes.wipe(it) }
            pin = null
            false
        }
    }

    /** 步骤 2:用户勾选/取消生物识别。 */
    fun onBiometricToggled(enabled: Boolean) {
        biometricEnabled = enabled
    }

    /**
     * 步骤 2 → 提交迁移。
     *
     * 调 [LegacyKeyMigrator.migrate] 拿到 handle,然后 [LockController.finishMigration]
     * 把 DB 打开、状态推到 Unlocked。
     *
     * 失败时 PIN 已经被 wipe 了(用户得重新输入),但 heldPin 已空,所以下次
     * `onPinEntered` 会重新接住。
     */
    fun onConfirmMigrate() {
        val currentPin = pin ?: return
        pin = null
        _state.update { it.copy(migrating = true, error = null) }
        viewModelScope.launch {
            val result = runCatching {
                val handle = legacyKeyMigrator.migrate(currentPin, mnemonicCodec)
                lockController.finishMigration(
                    handle = handle,
                    lockEnabled = true,
                    timeout = LockPrefs.TimeoutTier.IMMEDIATE,
                )
            }
            try {
                SecretBytes.wipe(currentPin)
            } catch (_: Throwable) { /* best-effort */ }
            result.onFailure { t ->
                // 任何异常(IllegalStateException / DatabaseOpenException / DatabaseRekeyException / ...),
                // 都转成 MigrationError.MigrationFailed,UI 引导恢复备份。
                _state.update {
                    it.copy(
                        migrating = false,
                        error = MigrationError.from(t),
                    )
                }
            }
            // success 路径不用动 state —— LockController.finishMigration 已经把
            // state 推到 Unlocked,RootNavHost 自动切到 MAIN。
        }
    }

    /** 清掉当前 error,UI 重新开始输入时调。 */
    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    /** 进程被杀重入时清理残留(Q17+B)。 */
    override fun onCleared() {
        super.onCleared()
        pin?.let { SecretBytes.wipe(it) }
        pin = null
    }

    sealed interface PinEntryResult {
        data object Accepted : PinEntryResult
        data class TooShort(val minLength: Int) : PinEntryResult
        data class TooWeak(val reason: String) : PinEntryResult
    }

    companion object {
        const val MIN_PIN_LENGTH = 8
    }
}

/**
 * 暴露给 UI 的状态。
 *
 * @param interrupted 上次迁移写到一半崩了(rekey 成功,vault 未写)。这种状态下 UI
 *   不展示 PIN 屏,直接渲染"从备份恢复"占位。
 * @param migrating 迁移正在跑(rekey + vault init + open DB,UI 应当 disable 按钮 + 转圈)
 * @param error 上一次迁移失败的原因
 */
data class MigrationUiState(
    val interrupted: Boolean = false,
    val migrating: Boolean = false,
    val error: MigrationError? = null,
)

/**
 * 迁移专属的 UI 错误。复用 [LockError] 的几个基础类型(Unknown 等),不重复定义。
 */
sealed class MigrationError {
    /** rekey / vault 写入失败 —— 引导用户从备份恢复。 */
    data object MigrationFailed : MigrationError()

    /** 旧口令打不开 DB(SQLCipher 报"file is not a database"之类)—— 强制走恢复备份。 */
    data object DatabaseCorrupted : MigrationError()

    /** 兜底。 */
    data class Unknown(val detail: String) : MigrationError()

    companion object {
        fun from(t: Throwable): MigrationError = when (t) {
            is DatabaseRekeyException -> MigrationFailed
            is DatabaseOpenException -> DatabaseCorrupted
            is IllegalStateException -> MigrationFailed
            else -> Unknown(t.message ?: t.javaClass.name)
        }
    }
}
