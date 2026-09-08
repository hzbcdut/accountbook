package nt.ddeoid.accountbook.security.lock.ui

import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.annotation.StringRes
import nt.ddeoid.accountbook.R
import nt.ddeoid.accountbook.security.crypto.MnemonicException
import nt.ddeoid.accountbook.security.lock.LockException

/**
 * 把应用锁 + 助记词层的异常翻成 UI-friendly 文案。
 *
 * UI 不应该直接 catch [CryptoException] / [MnemonicException] —— 那些文案是**程序员
 * 给程序员看的**,措辞偏底层(像"GCM tag mismatch")。这里把它们重写成用户能行动的
 * 句子("PIN 错误,请重试")。
 *
 * 用 sealed class 而不是 String,是因为:
 * 1. 不同错误可能需要在 UI 上做不同的额外动作(比如 cooldown 要显示倒计时,
 *    DB 损坏要弹"恢复备份"按钮)—— UI 直接 `when (error)` 分发
 * 2. string 资源 id 而不是裸 String,中英文切换走 strings.xml
 * 3. 测试里可以精确地断言"这次应该是 Cooldown" 而不是字符串匹配
 */
sealed class LockError {

    /** @param position 0-indexed 的词位置;UI 显示时 +1。 */
    data class MnemonicUnknownWord(val word: String, val position: Int) : LockError()
    data object MnemonicWrongCount : LockError()
    data object MnemonicChecksumMismatch : LockError()
    data class Cooldown(val retryAfterMs: Long) : LockError()

    /**
     * "PIN 错误,请重试"。
     *
     * 这是默认文案 —— 实际上 [CryptoException.UnwrapFailed] / [BlobCorrupted] 都映射到这里。
     * 故意不区分:泄露"这次是 tag mismatch 还是 blob 坏了"等于泄露给攻击者内部状态。
     */
    data object WrongPin : LockError()

    /**
     * "数据已损坏,请从备份恢复"。
     *
     * SQLCipher 打不开 + 备份是恢复的唯一路径时用。UI 在 LockScreen 上看到这条
     * 应当切到 Phase 5 的 BackupImporter(本任务只暴露状态,不做入口跳转)。
     */
    data object DatabaseCorrupted : LockError()

    /**
     * "生物识别凭据已失效"。
     *
     * Keystore key 因为设备锁屏密码被改 / 全部清除而被永久失效。UI 应当引导用户
     * 改用 PIN / 助记词解锁,然后在 Settings 里重新 enroll 生物识别。
     */
    data object BiometricInvalidated : LockError()

    /** "发生未知错误,详情:X" —— 兜底,接任何没匹配上的 Throwable。 */
    data class Unknown(val detail: String) : LockError()

    companion object {
        /**
         * 把任意 [Throwable] 转成 [LockError]。
         *
         * 优先级(从上往下):
         * - [LockException.InCooldown] → [Cooldown]
         * - [MnemonicException.UnknownWord] → [MnemonicUnknownWord]
         * - [MnemonicException.WrongWordCount] → [MnemonicWrongCount]
         * - [MnemonicException.ChecksumMismatch] → [MnemonicChecksumMismatch]
         * - [KeyPermanentlyInvalidatedException] → [BiometricInvalidated]
         * - [CryptoException] → [WrongPin] (加密层错误不暴露细节)
         * - 其他 → [Unknown]
         */
        fun from(throwable: Throwable): LockError = when (throwable) {
            is LockException.InCooldown -> Cooldown(throwable.retryAfterMs)
            is MnemonicException.UnknownWord -> MnemonicUnknownWord(throwable.word, throwable.position)
            is MnemonicException.WrongWordCount -> MnemonicWrongCount
            is MnemonicException -> MnemonicChecksumMismatch
            is KeyPermanentlyInvalidatedException -> BiometricInvalidated
            // CryptoException 故意只翻成 WrongPin —— 不让外部区分 UnwrapFailed / BlobCorrupted
            is nt.ddeoid.accountbook.security.crypto.CryptoException -> WrongPin
            else -> Unknown(throwable.message ?: throwable.javaClass.name)
        }
    }

    @StringRes
    fun messageRes(): Int = when (this) {
        is MnemonicUnknownWord -> R.string.lock_error_mnemonic_unknown_word
        is MnemonicWrongCount -> R.string.lock_error_mnemonic_wrong_count
        is MnemonicChecksumMismatch -> R.string.lock_error_mnemonic_checksum
        is Cooldown -> R.string.lock_error_cooldown
        is WrongPin -> R.string.lock_error_wrong_pin
        is DatabaseCorrupted -> R.string.lock_error_database_corrupted
        is BiometricInvalidated -> R.string.lock_error_biometric_invalidated
        is Unknown -> R.string.lock_error_unknown
    }
}
