package nt.ddeoid.accountbook.security.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Integrity of the bundled BIP39 wordlist.
 *
 * A wrong wordlist is the single most damaging bug this package could ship: every mnemonic
 * generated with it would be unrecoverable, and the failure would only surface years later
 * when someone tries to restore. So the list is pinned by SHA-256 and checked here.
 */
class Bip39WordListTest {

    @Test
    fun `test resource copy is the official list`() {
        val words = Bip39WordList.parseAndVerify(TestWordList.rawBytes)
        assertEquals(Bip39WordList.EXPECTED_SIZE, words.size)
        assertEquals("abandon", words.first())
        assertEquals("zoo", words.last())
    }

    @Test
    fun `official sha256 constant matches the bundled bytes`() {
        val actual = Hashing.toHex(Hashing.sha256(TestWordList.rawBytes))
        assertEquals(Bip39WordList.EXPECTED_SHA256_HEX, actual)
    }

    /**
     * The shipped asset and the test fixture must not drift apart.
     *
     * If they did, this suite would be proving the correctness of a wordlist that the app
     * does not actually ship. The comparison is by hash so a single changed byte fails.
     */
    @Test
    fun `main asset is byte identical to the test fixture`() {
        val asset = File("src/main/assets/${Bip39WordList.ASSET_PATH}")
        assertTrue("找不到 ${asset.path};单元测试的工作目录应为 app/ 模块根目录", asset.exists())
        val assetHash = Hashing.toHex(Hashing.sha256(asset.readBytes()))
        val fixtureHash = Hashing.toHex(Hashing.sha256(TestWordList.rawBytes))
        assertEquals(fixtureHash, assetHash)
        assertEquals(Bip39WordList.EXPECTED_SHA256_HEX, assetHash)
    }

    /**
     * Every 4-letter prefix is unique in the official list (2048 distinct prefixes for 2048
     * words). The recovery UI leans on this for autocomplete, so it is asserted rather than
     * assumed - a future wordlist swap could quietly break it.
     */
    @Test
    fun `four letter prefixes are unique`() {
        val words = TestWordList.words
        assertEquals(words.size, words.map { it.take(4) }.toSet().size)
    }

    @Test
    fun `truncated list is rejected`() {
        val truncated = TestWordList.rawBytes.copyOf(TestWordList.rawBytes.size - 100)
        assertThrows(CryptoException.WordListCorrupted::class.java) {
            Bip39WordList.parseAndVerify(truncated)
        }
    }

    @Test
    fun `single tampered byte is rejected`() {
        val tampered = TestWordList.rawBytes.copyOf()
        // Flip one byte inside the body, keeping the length identical.
        tampered[500] = (tampered[500].toInt() xor 0x01).toByte()
        assertThrows(CryptoException.WordListCorrupted::class.java) {
            Bip39WordList.parseAndVerify(tampered)
        }
    }

    @Test
    fun `empty input is rejected`() {
        assertThrows(CryptoException.WordListCorrupted::class.java) {
            Bip39WordList.parseAndVerify(ByteArray(0))
        }
    }
}
