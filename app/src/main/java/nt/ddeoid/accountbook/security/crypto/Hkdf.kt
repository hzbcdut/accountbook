package nt.ddeoid.accountbook.security.crypto

/**
 * HKDF-SHA256(RFC 5869)。
 *
 * 用途:把 BIP39 的 128-bit 熵**扩展**成 SQLCipher 需要的 256-bit 主密钥。
 *
 * 为什么不直接把 16 字节熵喂给 SQLCipher?
 * - SQLCipher 期望 32 字节 raw key,16 字节得先补;
 * - 补零 / 直接复制会让密钥的有效熵只有 128 bit 且分布难看;
 * - HKDF 是标准做法:熵进去、均匀分布的 256 bit 出来,而且 [info] 参数能把
 *   这把密钥**绑定到具体用途**,避免同一份熵在别处派生出可互换的密钥。
 *
 * 纯 JVM,无 Android 依赖,可直接单测。
 */
object Hkdf {

    private const val HASH_LEN = 32 // SHA-256 输出长度

    /**
     * @param ikm 输入密钥材料(这里是 BIP39 熵)
     * @param salt 可选盐;传空数组等价于 RFC 里"未提供盐"
     * @param info 上下文绑定串,**必须**为每个不同用途取不同值
     * @param length 输出字节数,1..255*32
     */
    fun derive(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray {
        require(length in 1..255 * HASH_LEN) { "length 超出 HKDF 上限: $length" }
        require(ikm.isNotEmpty()) { "ikm 不能为空" }

        val prk = extract(salt, ikm)
        return expand(prk, info, length)
    }

    /** RFC 5869 §2.2:PRK = HMAC-Hash(salt, IKM) */
    private fun extract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val effectiveSalt = if (salt.isEmpty()) ByteArray(HASH_LEN) else salt
        return Hashing.hmacSha256(effectiveSalt, ikm)
    }

    /** RFC 5869 §2.3:T(i) = HMAC-Hash(PRK, T(i-1) | info | i) */
    private fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter: Byte = 1
        while (offset < length) {
            val input = previous + info + byteArrayOf(counter)
            previous = Hashing.hmacSha256(prk, input)
            val take = minOf(HASH_LEN, length - offset)
            previous.copyInto(out, destinationOffset = offset, startIndex = 0, endIndex = take)
            offset += take
            counter++
        }
        return out
    }
}
