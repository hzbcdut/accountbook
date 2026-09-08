package nt.ddeoid.accountbook.security.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * PBKDF2-HMAC-SHA256(RFC 8018 §5.2),自己组装在平台的 HMAC 之上。
 *
 * **为什么不用 `SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")`**:
 * `PBEKeySpec` 收的是 `CharArray`,而"char 怎么变成字节"在各家实现里不一致 ——
 * 经典 SunJCE 是 `(char and 0xFF)` 逐字符截断,JDK 9+ 改过 UTF-8 编码,Android 上跑的
 * 又是 Conscrypt/OpenSSL 的实现。对一个 6 位数字 PIN 来说三种行为结果相同,但只要用户
 * 选了一个带非 ASCII 字符的 passphrase,截断就会**静默削掉熵**,而且不同设备上派生出的
 * 密钥可能不同 —— 那是"换手机后数据解不开"级别的 bug。
 *
 * 自己组装的成本是 30 行,收益是行为在任何 JVM / Android 版本上完全确定。
 * 这不是"自造密码学原语":HMAC-SHA256 仍然来自平台,PBKDF2 只是它上面一层
 * 完全标准化的循环 + 异或。
 *
 * 正确性由 `Pbkdf2Test` 用 OpenSSL(Python `hashlib.pbkdf2_hmac`)的输出做交叉验证。
 */
object Pbkdf2 {

    private const val ALGORITHM = "HmacSHA256"
    private const val H_LEN = 32 // SHA-256 输出长度

    /**
     * @param password 口令字节(调用方负责用完擦除)
     * @param salt 盐,建议 ≥ 16 字节随机
     * @param iterations 迭代次数,必须 > 0
     * @param dkLen 派生密钥长度(字节),必须 > 0
     */
    fun derive(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        dkLen: Int,
    ): ByteArray {
        require(password.isNotEmpty()) { "口令不能为空" }
        require(iterations > 0) { "迭代次数必须为正:$iterations" }
        require(dkLen > 0) { "派生长度必须为正:$dkLen" }
        require(dkLen.toLong() <= (0xFFFFFFFFL) * H_LEN) { "派生长度超出 PBKDF2 上限" }

        val blocks = (dkLen + H_LEN - 1) / H_LEN
        val out = ByteArray(blocks * H_LEN)

        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(password, ALGORITHM))

        for (blockIndex in 1..blocks) {
            computeBlock(mac, salt, iterations, blockIndex)
                .copyInto(out, destinationOffset = (blockIndex - 1) * H_LEN)
        }
        return out.copyOf(dkLen)
    }

    /**
     * F(P, S, c, i) = U1 ^ U2 ^ ... ^ Uc
     * U1 = PRF(P, S || INT_32_BE(i)),U2 = PRF(P, U1),...
     */
    private fun computeBlock(mac: Mac, salt: ByteArray, iterations: Int, blockIndex: Int): ByteArray {
        // U1
        mac.update(salt)
        mac.update(int32Be(blockIndex))
        var u = mac.doFinal() // doFinal 之后 Mac 自动回到初始状态,可以直接复用
        val accumulator = u.copyOf()

        // U2 .. Uc
        for (n in 2..iterations) {
            u = mac.doFinal(u)
            for (j in accumulator.indices) {
                accumulator[j] = (accumulator[j].toInt() xor u[j].toInt()).toByte()
            }
        }
        return accumulator
    }

    private fun int32Be(value: Int): ByteArray = byteArrayOf(
        ((value ushr 24) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )
}
