package nt.ddeoid.accountbook.security.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.SecureRandom

/**
 * [MnemonicCodec] against the canonical BIP39 test vectors.
 *
 * The expected phrases below are the published BIP-0039 vectors (the same ones
 * trezor/python-mnemonic ships), independently recomputed and verified before being
 * pasted here. Getting these right is what makes a mnemonic written on paper today
 * restorable by any other BIP39 tool later.
 */
class MnemonicCodecTest {

    private val codec = TestWordList.codec

    // --- 官方向量 -----------------------------------------------------------

    @Test
    fun `128 bit all zero`() = assertVector(
        "00000000000000000000000000000000",
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
    )

    @Test
    fun `128 bit all 7f`() = assertVector(
        "7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f",
        "legal winner thank year wave sausage worth useful legal winner thank yellow",
    )

    @Test
    fun `128 bit all 80`() = assertVector(
        "80808080808080808080808080808080",
        "letter advice cage absurd amount doctor acoustic avoid letter advice cage above",
    )

    @Test
    fun `128 bit all ff`() = assertVector(
        "ffffffffffffffffffffffffffffffff",
        "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong",
    )

    /** A non-pattern entropy, to make sure the bit packing is not accidentally aligned. */
    @Test
    fun `128 bit arbitrary`() = assertVector(
        "18ab19a9f54a9274f03e5209a2ac8a91",
        "board flee heavy tunnel powder denial science ski answer betray cargo cat",
    )

    @Test
    fun `256 bit all zero`() = assertVector(
        "0000000000000000000000000000000000000000000000000000000000000000",
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon art",
    )

    @Test
    fun `256 bit all ff`() = assertVector(
        "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
        List(23) { "zoo" }.joinToString(" ") + " vote",
    )

    // --- 往返 ---------------------------------------------------------------

    @Test
    fun `round trip over 500 random 128 bit entropies`() {
        val random = SecureRandom()
        repeat(500) {
            val entropy = ByteArray(16).also(random::nextBytes)
            val words = codec.encode(entropy)
            assertEquals(12, words.size)
            assertEquals(entropy.toHexLower(), codec.decode(words).toHexLower())
        }
    }

    @Test
    fun `round trip over 200 random 256 bit entropies`() {
        val random = SecureRandom()
        repeat(200) {
            val entropy = ByteArray(32).also(random::nextBytes)
            val words = codec.encode(entropy)
            assertEquals(24, words.size)
            assertEquals(entropy.toHexLower(), codec.decode(words).toHexLower())
        }
    }

    // --- 拒绝路径 -----------------------------------------------------------

    @Test
    fun `entropy of a disallowed size is rejected`() {
        // 15 bytes = 120 bits is not one of BIP39's five allowed lengths.
        val bad = assertThrows(MnemonicException.WrongEntropySize::class.java) {
            codec.encode(ByteArray(15))
        }
        assertEquals(15, bad.byteSize)
    }

    @Test
    fun `word count outside the allowed set is rejected`() {
        val eleven = TestWordList.words.take(11)
        val bad = assertThrows(MnemonicException.WrongWordCount::class.java) { codec.decode(eleven) }
        assertEquals(11, bad.count)

        assertThrows(MnemonicException.WrongWordCount::class.java) {
            codec.decode(TestWordList.words.take(13))
        }
    }

    @Test
    fun `a word outside the wordlist is reported with its position`() {
        val base = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon"
        // Sanity: the untampered phrase decodes to 16 bytes of entropy.
        assertEquals(16, codec.decode(codec.normalize("$base about")).size)

        val typo = codec.normalize("$base aboot")
        val bad = assertThrows(MnemonicException.UnknownWord::class.java) { codec.decode(typo) }
        assertEquals(11, bad.position) // 0-based: the 12th word
        assertEquals("aboot", bad.word)
    }

    /**
     * The checksum is what catches a transposition or a dropped word.
     * With 12 words a random guess passes only 1 time in 16.
     */
    @Test
    fun `swapping the last word breaks the checksum`() {
        val valid = codec.normalize(
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
        )
        assertEquals(16, codec.decode(valid).size)

        val tampered = valid.toMutableList().also { it[11] = "ability" }
        assertThrows(MnemonicException.ChecksumMismatch::class.java) { codec.decode(tampered) }
    }

    @Test
    fun `reordering two words breaks the checksum`() {
        val valid = codec.normalize(
            "legal winner thank year wave sausage worth useful legal winner thank yellow",
        )
        val swapped = valid.toMutableList().also {
            val tmp = it[0]; it[0] = it[1]; it[1] = tmp
        }
        assertThrows(MnemonicException.ChecksumMismatch::class.java) { codec.decode(swapped) }
    }

    /** Unknown-word detection must win over the checksum, or the error message is useless. */
    @Test
    fun `unknown word is reported before checksum`() {
        val garbage = List(12) { "zzzzzz" }
        assertThrows(MnemonicException.UnknownWord::class.java) { codec.decode(garbage) }
    }

    // --- normalize ----------------------------------------------------------

    @Test
    fun `normalize tolerates case whitespace and separators`() {
        val expected = listOf("abandon", "ability", "able")
        assertEquals(expected, codec.normalize("  Abandon   ABILITY\table  "))
        assertEquals(expected, codec.normalize("abandon, ability, able"))
        assertEquals(expected, codec.normalize("abandon、ability、able"))
        assertEquals(expected, codec.normalize("abandon\nability\nable"))
        assertEquals(expected, codec.normalize("abandon;ability；able"))
    }

    /** A numbered list must not leave the numbers behind as phantom words. */
    @Test
    fun `normalize strips list numbering`() {
        val expected = listOf("abandon", "ability", "able")
        assertEquals(expected, codec.normalize("1. abandon 2. ability 3. able"))
        assertEquals(expected, codec.normalize("1) abandon 2) ability 3) able"))
        assertEquals(expected, codec.normalize("1、abandon 2、ability 3、able"))
    }

    @Test
    fun `normalize of blank input is empty`() {
        assertEquals(emptyList<String>(), codec.normalize(""))
        assertEquals(emptyList<String>(), codec.normalize("   \n\t "))
    }

    @Test
    fun `normalize then decode round trips a full 12 word phrase`() {
        val raw = "  Board, FLEE\theavy  tunnel\npowder denial science ski answer betray cargo cat  "
        val words = codec.normalize(raw)
        assertEquals(12, words.size)
        assertEquals("18ab19a9f54a9274f03e5209a2ac8a91", codec.decode(words).toHexLower())
    }

    // --- 杂项 ---------------------------------------------------------------

    @Test
    fun `entropyBytesFor maps word counts correctly`() {
        assertEquals(16, codec.entropyBytesFor(12))
        assertEquals(20, codec.entropyBytesFor(15))
        assertEquals(24, codec.entropyBytesFor(18))
        assertEquals(28, codec.entropyBytesFor(21))
        assertEquals(32, codec.entropyBytesFor(24))
        assertEquals(null, codec.entropyBytesFor(11))
        assertEquals(null, codec.entropyBytesFor(13))
    }

    @Test
    fun `codec refuses a wordlist of the wrong size`() {
        assertThrows(IllegalArgumentException::class.java) {
            MnemonicCodec(List(2047) { "word$it" })
        }
    }

    private fun assertVector(entropyHex: String, expectedPhrase: String) {
        val entropy = entropyHex.hexToBytes()
        val expected = expectedPhrase.split(" ").filter { it.isNotEmpty() }

        val encoded = codec.encode(entropy)
        assertEquals(expected, encoded)
        // And back again.
        assertEquals(entropyHex, codec.decode(encoded).toHexLower())
    }
}
