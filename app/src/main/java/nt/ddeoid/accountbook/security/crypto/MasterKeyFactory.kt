package nt.ddeoid.accountbook.security.crypto

/**
 * 主密钥派生:BIP39 熵 → SQLCipher 用的 256-bit master key。
 *
 * 这是 Q10=A 的核心等式:**助记词就是主密钥**,纸上那 12 个词能完整还原出开库的钥匙。
 *
 * ```
 * 12 词 ──decode──> 128-bit 熵 ──HKDF-SHA256──> 256-bit master key ──> SQLCipher
 * ```
 *
 * 为什么中间要过一道 HKDF,而不是把 16 字节熵直接补零成 32 字节:
 * - 补零会让密钥后 128 bit 恒定,有效熵只有 128 bit 且分布难看;
 * - HKDF 把 128 bit 熵**均匀展开**成 256 bit,是 RFC 5869 的标准做法;
 * - [INFO] 把这把密钥绑定到"AccountBook 主密钥 v1"这个具体用途,别的上下文
 *   拿同一份熵派生出来的密钥跟它不可互换。
 *
 * ⚠️ [INFO] 和 [SALT] 一旦发布就**永远不能改** —— 改了之后所有用户纸上的 12 个词
 * 都会派生出不同的密钥,数据当场变成噪声。要换只能走一次显式的迁移流程。
 *
 * 熵本身是 128 bit,所以 master key 的有效强度也是 128 bit。这对"离线爆破"是足够的
 * (2¹²⁸ 没有任何现实攻击路径);真正的弱点在 PIN 那条路径,见 [PinKdf]。
 */
object MasterKeyFactory {

    const val LENGTH_BYTES = 32

    private val SALT = "accountbook/bip39-entropy/v1".toByteArray(Charsets.UTF_8)
    private val INFO = "accountbook/master-key/v1".toByteArray(Charsets.UTF_8)

    fun fromEntropy(entropy: ByteArray): SecretBytes {
        require(entropy.isNotEmpty()) { "熵不能为空" }
        return SecretBytes(
            Hkdf.derive(
                ikm = entropy,
                salt = SALT,
                info = INFO,
                length = LENGTH_BYTES,
            ),
        )
    }

    /**
     * 恢复路径:12 词 → master key。
     *
     * 把 decode 和派生绑在一个方法里,是为了保证"生成"和"恢复"永远走同一条
     * 派生路径 —— 两边各写一次是最容易出现"能生成但恢复不出来"这种致命 bug 的地方。
     *
     * @throws MnemonicException 词数/词表/校验位有问题
     */
    fun fromMnemonic(codec: MnemonicCodec, words: List<String>): SecretBytes =
        fromEntropy(codec.decode(words))

    /**
     * 生成路径:新熵 → (master key, 助记词)。
     *
     * 一次返回两样东西,避免调用方自己拼的时候用错熵。
     * 返回的 [Generated.mnemonic] 只用于展示给用户抄写,**不落盘**。
     */
    fun generate(codec: MnemonicCodec, entropySource: EntropySource = EntropySource()): Generated {
        val entropy = entropySource.nextMnemonicEntropy()
        try {
            val masterKey = fromEntropy(entropy)
            val mnemonic = codec.encode(entropy)
            // 持久化路径要走熵(Q10=A:熵就是助记词背后的种子),所以把熵也一起带出去。
            // 调用方负责擦;留到这里 wipe 会让 masterKey 派生完也跟着没,顺序错。
            return Generated(masterKey = masterKey, mnemonic = mnemonic, entropy = entropy)
        } finally {
            // 注意:不在这里擦 entropy。Generated 已经持有引用,调用方在用完时擦。
        }
    }

    /**
     * @param masterKey 调用方负责 [SecretBytes.wipe]
     * @param mnemonic 12 个词,仅用于展示;展示完应尽快从内存丢掉
     * @param entropy 16 字节 BIP39 熵,持久化用;**调用方负责 [ByteArray.fill]** 清除
     */
    class Generated(
        val masterKey: SecretBytes,
        val mnemonic: List<String>,
        val entropy: ByteArray,
    )
}
