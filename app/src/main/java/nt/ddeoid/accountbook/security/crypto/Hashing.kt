package nt.ddeoid.accountbook.security.crypto

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 平台内建的哈希原语封装。
 *
 * 全部走 `java.security` / `javax.crypto`,**不引第三方密码学库** —— 这是项目
 * "严格离线 + 无第三方 SDK"原则的一部分,也是少一处供应链风险。
 *
 * 纯 JVM 实现,没有任何 Android 依赖,所以能在单元测试里直接跑。
 */
internal object Hashing {

    const val SHA_256 = "SHA-256"
    const val HMAC_SHA_256 = "HmacSHA256"

    fun sha256(input: ByteArray): ByteArray =
        MessageDigest.getInstance(SHA_256).digest(input)

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_SHA_256)
        mac.init(SecretKeySpec(key, HMAC_SHA_256))
        return mac.doFinal(data)
    }

    /** 定长比较,避免用 `==` 逐字节短路造成的计时侧信道。 */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean =
        MessageDigest.isEqual(a, b)

    fun toHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}
