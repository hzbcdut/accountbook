package nt.ddeoid.accountbook.security.crypto

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 用 AES-256-GCM 包裹 / 解包 master key。
 *
 * Q1=A 意味着 master key 是随机的,并且有**多条互相独立**的解锁路径。每条路径存一个
 * 这里产出的 blob:
 *
 * ```
 * PIN 路径      KDF(PIN, salt) → 软件 AES 密钥 → 解包 → master key
 * 生物识别路径   Keystore AES 密钥(要求用户认证)→ 解包 → master key
 * ```
 *
 * GCM 提供认证加密,所以被篡改或截断的 blob 会**明确失败**,而不是解出一坨看起来像
 * 那么回事的垃圾。
 *
 * [WrapContext] 以 AAD 的形式绑进加密过程,这意味着生物识别的 blob 喂给 PIN 路径
 * (或反过来)一定失败。没有 AAD 的话,只要两条路径的包裹密钥碰巧相同,两个 blob 就是
 * 可互换的 —— 而"可互换"这种含糊正是会变成静默安全漏洞的东西。
 *
 * `java.util.Base64` 需要 API 26;`minSdk` 正好是 26,所以可用。而且它是纯 JVM 的,
 * 这让本类不需要 Robolectric 就能单测。
 */
@Singleton
class KeyWrapper @Inject constructor(
    private val entropySource: EntropySource,
) {

    /** 用原始软件密钥(PIN 路径)包裹 [plaintext]。 */
    fun wrap(rawKey: ByteArray, plaintext: SecretBytes, context: WrapContext): String =
        wrapCore(SecretKeySpec(rawKey, AES), plaintext, context)

    /** 用 [SecretKey] 包裹 [plaintext],通常是硬件后端的 Keystore 密钥。 */
    fun wrap(key: SecretKey, plaintext: SecretBytes, context: WrapContext): String =
        wrapCore(key, plaintext, context)

    /** 解开用原始软件密钥包裹的 blob。**擦除返回值是调用方的责任**。 */
    fun unwrap(rawKey: ByteArray, encoded: String, context: WrapContext): SecretBytes =
        unwrapCore(SecretKeySpec(rawKey, AES), encoded, context)

    /** 解开用 Keystore 密钥包裹的 blob。**擦除返回值是调用方的责任**。 */
    fun unwrap(key: SecretKey, encoded: String, context: WrapContext): SecretBytes =
        unwrapCore(key, encoded, context)

    private fun wrapCore(key: SecretKey, plaintext: SecretBytes, context: WrapContext): String {
        val iv = entropySource.nextIv()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
            updateAAD(context.aad)
        }
        val ciphertext = cipher.doFinal(plaintext.bytes)
        // 布局:iv (12) || ciphertext+tag。一个 blob 一个 Base64 串,没有对齐出错的空间。
        val blob = ByteArray(iv.size + ciphertext.size)
        iv.copyInto(blob, destinationOffset = 0)
        ciphertext.copyInto(blob, destinationOffset = iv.size)
        return Base64.getEncoder().encodeToString(blob)
    }

    private fun unwrapCore(key: SecretKey, encoded: String, context: WrapContext): SecretBytes {
        val blob = try {
            Base64.getDecoder().decode(encoded)
        } catch (e: IllegalArgumentException) {
            throw CryptoException.BlobCorrupted("blob 不是合法 Base64", e)
        }
        if (blob.size < IV_BYTES + TAG_LENGTH_BITS / 8) {
            throw CryptoException.BlobCorrupted(
                "blob 太短:${blob.size} 字节,至少要 ${IV_BYTES + TAG_LENGTH_BITS / 8}",
            )
        }
        val iv = blob.copyOfRange(0, IV_BYTES)
        val ciphertext = blob.copyOfRange(IV_BYTES, blob.size)

        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
            updateAAD(context.aad)
        }
        val plaintext = try {
            cipher.doFinal(ciphertext)
        } catch (e: javax.crypto.AEADBadTagException) {
            // 密钥不对、blob 被改过、或者 WrapContext 用错了 —— 这三种在这一层必须长得
            // 一模一样,区分它们就等于泄露"是哪一种"。
            throw CryptoException.UnwrapFailed(e)
        }
        return SecretBytes(plaintext)
    }

    /**
     * 通过 GCM AAD 把 blob 绑定到产出它的那条路径。
     *
     * `/v1` 后缀也是绑定的一部分:哪天包裹方案变了就把它递增,让旧 blob **干净地失败**,
     * 而不是被静默地按新方案重新解释。
     */
    enum class WrapContext(binding: String) {
        PIN("accountbook/wrap/pin/v1"),
        BIOMETRIC("accountbook/wrap/biometric/v1"),
        ;

        val aad: ByteArray = binding.toByteArray(Charsets.UTF_8)
    }

    private companion object {
        const val AES = "AES"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_LENGTH_BITS = 128
        const val IV_BYTES = 12
    }
}
