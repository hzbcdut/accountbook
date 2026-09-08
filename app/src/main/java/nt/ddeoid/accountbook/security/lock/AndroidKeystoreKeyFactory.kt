package nt.ddeoid.accountbook.security.lock

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import nt.ddeoid.accountbook.security.crypto.CryptoException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * 在 Android Keystore 里创建"受生物识别 / 设备凭证保护"的 AES-256-GCM 密钥。
 *
 * 单独抽这个对象,而不直接在 [KeystoreAccess.Default] 里 new,是为了把"KeyGenParameterSpec
 * 这套 Android 专属 API"集中在一处 —— 单测里不需要碰它。
 *
 * ## Q12=B 的设定
 *
 * - `setUserAuthenticationRequired(true)` —— 任何使用都必须先经 BiometricPrompt 认证
 * - `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG or AUTH_DEVICE_CREDENTIAL)`
 *   —— 允许**生物识别 (STRONG) 或 PIN/图案/密码**任一通过(API 30+;旧版本走老 API)
 * - 不调 `setInvalidatedByBiometricEnrollment(true)` —— 用户新录指纹后密钥**不会**自动作废,
 *   这是 Q12=B 的明示选择,否则用户每录一个新指纹就要重做一次生物识别 enroll
 *
 * ## 失败模式
 *
 * KeyGenerator 会因为设备状态(keystore 损坏 / 无 secure lock screen)抛 ProviderException。
 * 把它翻译成 [CryptoException],让 [KeyVault] 那一层只关心"能不能建出密钥"。
 */
internal object AndroidKeystoreKeyFactory {

    /**
     * @throws CryptoException Keystore 不可用或生成失败
     */
    fun create(alias: String): SecretKey {
        return try {
            val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = buildSpec(alias)
            kg.init(spec)
            kg.generateKey()
        } catch (t: Throwable) {
            throw CryptoException.BlobCorrupted("无法创建 Keystore 密钥 $alias: ${t.message}", t)
        }
    }

    private fun buildSpec(alias: String): KeyGenParameterSpec {
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true)

        // API 30+ 支持指定"认证类型 + 超时"。我们用 timeout=0 表示每次都要认证,
        // authTypes = BIOMETRIC_STRONG | DEVICE_CREDENTIAL(Q12=B)。
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(
                0,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
            )
        }
        return builder.build()
    }

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_SIZE_BITS = 256
}
