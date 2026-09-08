package nt.ddeoid.accountbook.security.crypto

/**
 * 密钥层的失败。
 *
 * 做成一个小 sealed 层级而不是直接抛 `IllegalStateException`,是为了让调用方能精确地
 * 只捕获密钥层错误、不会顺手吞掉无关的 bug,同时也让这个包**所有可能的失败模式**
 * 集中列在一个地方。
 *
 * 命名上刻意避开 `java.security.GeneralSecurityException` —— 那是另一个东西,
 * 同名会在本包内造成遮蔽。
 */
sealed class CryptoException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /**
     * 包裹密钥不匹配,或者 blob 被篡改过。
     *
     * 这两种情况**故意不做区分**。GCM 本身就不告诉我们是哪一种,在这一层保持这个
     * 性质,意味着任何调用方都不可能通过"报错文案不同"或"重试提示不同"把它泄露出去。
     */
    class UnwrapFailed(cause: Throwable) :
        CryptoException("解锁失败:密钥不匹配或数据被篡改", cause)

    /** 存储的 blob 结构上就是坏的 —— 不是合法 Base64,或者被截断到不足 IV+tag。 */
    class BlobCorrupted(message: String, cause: Throwable? = null) : CryptoException(message, cause)

    /**
     * 打包进 APK 的 BIP39 词表没通过完整性校验。
     *
     * 致命错误:词表不对,生成出来的助记词就还原不回原来的熵,必须启动时就炸掉,
     * 不能等用户某天恢复数据时才发现。
     */
    class WordListCorrupted(message: String) : CryptoException(message)
}
