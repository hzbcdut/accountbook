package nt.ddeoid.accountbook.security.crypto

import java.security.SecureRandom

/**
 * 随机熵来源。
 *
 * 只用 [SecureRandom](平台 CSPRNG,Android 上底层是 `/dev/urandom` + 硬件熵)。
 * **不接受任何自定义 Random** —— 助记词的全部安全性都压在这 16 个字节上,
 * 一个可预测的 RNG 等于把主密钥写在脸上。
 */
class EntropySource(private val random: SecureRandom = SecureRandom()) {

    /** 128 bit 熵 → 12 个 BIP39 词。这是本 app 的默认档位。 */
    fun nextMnemonicEntropy(): ByteArray = nextBytes(MNEMONIC_ENTROPY_BYTES)

    /** 16 字节随机盐,给 [PinKdf] 用。 */
    fun nextSalt(): ByteArray = nextBytes(SALT_BYTES)

    /** 12 字节 GCM IV,给 [KeyWrapper] 用。 */
    fun nextIv(): ByteArray = nextBytes(GCM_IV_BYTES)

    fun nextBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    companion object {
        /** 128 bit = 16 字节 = 12 个 BIP39 词(Q10=A 决议的档位)。 */
        const val MNEMONIC_ENTROPY_BYTES = 16

        const val SALT_BYTES = 16
        const val GCM_IV_BYTES = 12
    }
}
