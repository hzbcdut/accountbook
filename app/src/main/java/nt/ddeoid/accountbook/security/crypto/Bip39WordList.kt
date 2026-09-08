package nt.ddeoid.accountbook.security.crypto

/**
 * BIP39 英文词表(Q15=A:只用英文表)。
 *
 * 为什么锁死英文、不做"跟随系统语言":同一份熵在不同词表下编出来的词**完全不同**。
 * 用户换了系统语言、或者换了一台语言设置不同的手机,恢复时词表对不上,数据就永久
 * 锁死了 —— 这是真实的数据丢失路径,不是理论风险。英文表是唯一"任何工具、任何
 * 语言环境都能还原"的选择。
 *
 * 词表随 APK 打包在 `assets/bip39-english.txt`。加载时**强制校验 SHA-256**:
 * 词表要是被截断或篡改,生成出来的助记词就再也还原不回原来的熵。这种错误必须在
 * 启动时就炸掉,而不是等用户某天恢复数据时才发现。
 *
 * 官方表的 SHA-256 是 `2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda`
 * (bitcoin/bips 仓库 `bip-0039/english.txt`,2048 行,首词 abandon、末词 zoo)。
 */
object Bip39WordList {

    const val ASSET_PATH = "bip39-english.txt"
    const val EXPECTED_SIZE = 2048

    /**
     * BIP39 官方英文词表的 SHA-256。
     *
     * ⚠️ 改这个常量 = 换词表 = 让**所有已经写在纸上的助记词失效**。别动。
     */
    const val EXPECTED_SHA256_HEX =
        "2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda"

    /**
     * 校验原始字节并解析成词表。
     *
     * @throws CryptoException.WordListCorrupted 哈希不匹配、词数不对、有重复词、或未按字典序
     */
    fun parseAndVerify(rawBytes: ByteArray): List<String> {
        val actualHex = Hashing.toHex(Hashing.sha256(rawBytes))
        if (actualHex != EXPECTED_SHA256_HEX) {
            throw CryptoException.WordListCorrupted(
                "SHA-256 不匹配:期望 $EXPECTED_SHA256_HEX,实际 $actualHex",
            )
        }
        val words = rawBytes.toString(Charsets.UTF_8)
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (words.size != EXPECTED_SIZE) {
            throw CryptoException.WordListCorrupted("词数应为 $EXPECTED_SIZE,实际 ${words.size}")
        }
        if (words.toSet().size != words.size) {
            throw CryptoException.WordListCorrupted("词表里有重复词")
        }
        // 官方表严格按字典序排列。哈希已经能挡住内容改动,这条主要防的是"有人以为顺序
        // 不重要"而动了排序 —— 索引就是词在表里的位置,顺序一变所有助记词全废。
        if (words.sorted() != words) {
            throw CryptoException.WordListCorrupted("词表未按字典序排列")
        }
        return words
    }
}
