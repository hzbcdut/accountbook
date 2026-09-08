package nt.ddeoid.accountbook.security.crypto

/**
 * PIN / passphrase → 包裹密钥的派生策略。
 *
 * 注意这里派生出来的**不是** master key,而是用来解开 master key 包裹 blob 的
 * AES 密钥(Q1=A:master key 是随机的,PIN 只是解锁它的一条路径)。
 *
 * ## 强度账(必须诚实记下来)
 *
 * 6 位数字 = 10⁶ 组合。60 万次迭代下,单次验证约需 6×10⁵ 次 HMAC-SHA256,
 * 全空间约 6×10¹¹ 次。RTX 4090 级显卡跑 PBKDF2-HMAC-SHA256 约 10 MH/s,
 * 也就是**全空间 ~17 小时、平均 ~8 小时**。
 *
 * 这个强度挡得住"捡到手机的人",挡不住"定向攻击 + 一台好显卡 + 拿到了
 * EncryptedSharedPreferences 文件"。想真正抗压必须走字母数字 passphrase
 * (Q11=C 留的那条路):12 个字符的随机字母数字是 71 bit,同样的迭代次数下
 * 离线爆破就彻底不现实了。
 *
 * 迭代次数取 60 万是 OWASP 2023 对 PBKDF2-HMAC-SHA256 的建议值,同时也是
 * "手机上一次解锁还能忍受"的上限附近 —— 中端机上约 0.3~1.5 秒。生物识别是
 * 主路径,PIN 只在兜底时才输入,所以这个延迟是可以接受的。
 */
object PinKdf {

    /** OWASP 2023 对 PBKDF2-HMAC-SHA256 的建议迭代次数。 */
    const val PRODUCTION_ITERATIONS = 600_000

    const val KEY_LENGTH_BYTES = 32
    const val SALT_LENGTH_BYTES = 16

    /**
     * @param pin 用户输入。**方法内部不清除它** —— 由调用方在确认不再需要后擦除,
     *   因为 UI 层往往还要拿它重试。
     * @param salt 每用户随机盐,必须持久化(丢了 salt 就等于丢了 PIN 路径)
     * @param iterations 单测里会传小值;生产走 [PRODUCTION_ITERATIONS]
     */
    fun derive(
        pin: CharArray,
        salt: ByteArray,
        iterations: Int = PRODUCTION_ITERATIONS,
    ): SecretBytes {
        require(pin.isNotEmpty()) { "PIN 不能为空" }
        require(salt.size >= SALT_LENGTH_BYTES) {
            "盐至少 $SALT_LENGTH_BYTES 字节,实际 ${salt.size}"
        }

        val utf8 = Pbkdf2Chars.toUtf8Bytes(pin)
        try {
            val derived = Pbkdf2.derive(
                password = utf8,
                salt = salt,
                iterations = iterations,
                dkLen = KEY_LENGTH_BYTES,
            )
            return SecretBytes(derived)
        } finally {
            utf8.fill(0)
        }
    }

    fun generateSalt(entropySource: EntropySource = EntropySource()): ByteArray =
        entropySource.nextSalt()
}
