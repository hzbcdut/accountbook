package nt.ddeoid.accountbook.security.crypto

/**
 * Test fixtures shared across the crypto suite.
 *
 * The wordlist here is a copy of `src/main/assets/bip39-english.txt` placed in
 * `src/test/resources/`. [Bip39WordListTest] asserts that the copy is byte-identical to the
 * official BIP39 list by SHA-256, so a drift between the two copies fails the build rather
 * than silently testing against a different table.
 */
internal object TestWordList {

    val rawBytes: ByteArray by lazy {
        val stream = TestWordList::class.java.classLoader!!
            .getResourceAsStream(Bip39WordList.ASSET_PATH)
            ?: error("测试资源里找不到 ${Bip39WordList.ASSET_PATH}")
        stream.use { it.readBytes() }
    }

    val words: List<String> by lazy { Bip39WordList.parseAndVerify(rawBytes) }

    val codec: MnemonicCodec by lazy { MnemonicCodec(words) }
}

internal fun String.hexToBytes(): ByteArray {
    val clean = replace(" ", "")
    require(clean.length % 2 == 0) { "十六进制串长度必须是偶数:$this" }
    return ByteArray(clean.length / 2) { i ->
        clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}

internal fun ByteArray.toHexLower(): String = joinToString("") { "%02x".format(it) }
