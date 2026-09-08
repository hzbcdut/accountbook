package nt.ddeoid.accountbook.security.crypto

import nt.ddeoid.accountbook.security.crypto.MnemonicException.ChecksumMismatch
import nt.ddeoid.accountbook.security.crypto.MnemonicException.UnknownWord
import nt.ddeoid.accountbook.security.crypto.MnemonicException.WrongEntropySize
import nt.ddeoid.accountbook.security.crypto.MnemonicException.WrongWordCount

/**
 * BIP39 熵 ↔ 助记词 编解码。
 *
 * 算法(BIP-0039):
 * ```
 * 熵 ENT bit  →  CS = ENT/32 bit 校验位(取 SHA-256(ENT) 的前 CS bit)
 *              →  拼成 ENT+CS bit(必为 11 的倍数)
 *              →  每 11 bit 一个索引 → 词表取词
 * ```
 * 12 词 = 128 bit 熵 + 4 bit 校验 = 132 bit = 12 × 11。
 *
 * **纯 JVM,无 Android 依赖**:词表通过构造参数注入,所以能在单元测试里直接跑
 * 真实的 2048 词表。Android 侧由 `Bip39WordListProvider` 从 assets 加载后交给 Hilt。
 *
 * 这一层是 Q10=A("助记词就是 master key 的可读编码")的核心:词 ↔ 熵是**双射**,
 * 所以用户纸上那 12 个词能完整还原出主密钥,不需要设备上再存任何东西。
 */
class MnemonicCodec(wordList: List<String>) {

    private val words: List<String> = wordList.toList()
    private val indexOf: Map<String, Int> = words.withIndex().associate { (i, w) -> w to i }

    init {
        require(words.size == Bip39WordList.EXPECTED_SIZE) {
            "词表必须是 ${Bip39WordList.EXPECTED_SIZE} 个词,实际 ${words.size}"
        }
    }

    /** 熵 → 词。128 bit 熵出 12 个词。 */
    fun encode(entropy: ByteArray): List<String> {
        val entBits = entropy.size * BITS_PER_BYTE
        if (entBits !in VALID_ENTROPY_BITS) throw WrongEntropySize(entropy.size)

        val checksumBits = entBits / CHECKSUM_DIVISOR
        val hash = Hashing.sha256(entropy)
        val totalBits = entBits + checksumBits

        val result = ArrayList<String>(totalBits / BITS_PER_WORD)
        for (start in 0 until totalBits step BITS_PER_WORD) {
            var index = 0
            for (i in 0 until BITS_PER_WORD) {
                val pos = start + i
                val bit = if (pos < entBits) {
                    bitAt(entropy, pos)
                } else {
                    bitAt(hash, pos - entBits)
                }
                index = (index shl 1) or bit
            }
            result += words[index]
        }
        return result
    }

    /** 词 → 熵。校验位不对就抛 [ChecksumMismatch]。 */
    fun decode(words: List<String>): ByteArray {
        if (words.size !in VALID_WORD_COUNTS) throw WrongWordCount(words.size)

        val totalBits = words.size * BITS_PER_WORD
        val checksumBits = totalBits / TOTAL_DIVISOR
        val entBits = totalBits - checksumBits

        val bits = IntArray(totalBits)
        words.forEachIndexed { position, word ->
            val index = indexOf[word] ?: throw UnknownWord(word, position)
            for (i in 0 until BITS_PER_WORD) {
                bits[position * BITS_PER_WORD + i] = (index ushr (BITS_PER_WORD - 1 - i)) and 1
            }
        }

        val entropy = ByteArray(entBits / BITS_PER_BYTE)
        for (i in 0 until entBits) {
            if (bits[i] == 1) {
                entropy[i / BITS_PER_BYTE] =
                    (entropy[i / BITS_PER_BYTE].toInt() or (1 shl (7 - i % BITS_PER_BYTE))).toByte()
            }
        }

        val hash = Hashing.sha256(entropy)
        for (i in 0 until checksumBits) {
            val expected = bitAt(hash, i)
            if (bits[entBits + i] != expected) throw ChecksumMismatch
        }
        return entropy
    }

    /**
     * 用户输入 → 规范化的词序列。
     *
     * 宽容处理:大小写、首尾空白、连续空格、逗号/顿号分隔、行分隔、以及有人习惯性
     * 加的序号("1. abandon")。BIP39 正式规范要求 NFKD 规范化,英文表全是 ASCII
     * 小写字母,NFKD 是恒等变换,所以这里不需要额外做。
     *
     * **不**宽容处理:拼错的词。那是 [decode] 里 [UnknownWord] 的职责。
     */
    fun normalize(rawInput: String): List<String> = rawInput
        .lowercase()
        // 先把 "1." / "1)" / "1、" 这种序号整个吃掉,否则分隔后会残留一个假的 "1" 词
        .replace(NUMBERING, " ")
        .split(SEPARATORS)
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    /** 词数 → 熵字节数;词数非法返回 null。给 UI 显示"你输入的是 12 词"用。 */
    fun entropyBytesFor(wordCount: Int): Int? {
        if (wordCount !in VALID_WORD_COUNTS) return null
        val totalBits = wordCount * BITS_PER_WORD
        val checksumBits = totalBits / TOTAL_DIVISOR
        return (totalBits - checksumBits) / BITS_PER_BYTE
    }

    private fun bitAt(bytes: ByteArray, position: Int): Int =
        (bytes[position / BITS_PER_BYTE].toInt() ushr (7 - position % BITS_PER_BYTE)) and 1

    private companion object {
        const val BITS_PER_BYTE = 8
        const val BITS_PER_WORD = 11
        const val CHECKSUM_DIVISOR = 32 // CS = ENT / 32
        const val TOTAL_DIVISOR = 33    // 反推:CS = (11 × 词数) / 33

        val VALID_ENTROPY_BITS = setOf(128, 160, 192, 224, 256)
        val VALID_WORD_COUNTS = setOf(12, 15, 18, 21, 24)

        val SEPARATORS = Regex("[\\s,、;；]+")
        val NUMBERING = Regex("""\d+\s*[.)。:：、]\s*""")
    }
}
