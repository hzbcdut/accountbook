package nt.ddeoid.accountbook.security.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * [MasterKeyFactory] - the equation the whole design rests on.
 *
 * Q10=A says the mnemonic **is** the master key: 12 words on paper must reproduce, byte for
 * byte, the key that opens the database. Every test here is a variation on "generate and
 * recover agree".
 *
 * The pinned hex values were computed independently with Python's HMAC/hashlib, so they catch
 * a change to the HKDF salt/info constants in [MasterKeyFactory]. Such a change would be
 * catastrophic in production - every mnemonic already written down would derive a different
 * key - so it should never happen silently.
 */
class MasterKeyFactoryTest {

    private val codec = TestWordList.codec

    /** The full chain, pinned end to end: words -> entropy -> HKDF -> 256-bit key. */
    @Test
    fun `a known mnemonic derives a known master key`() {
        val words = codec.normalize(
            "board flee heavy tunnel powder denial science ski answer betray cargo cat",
        )
        MasterKeyFactory.fromMnemonic(codec, words).use { key ->
            assertEquals(32, key.bytes.size)
            assertEquals(
                "6071b9a1bc24f5fd15cce45d4e680705e2251dd3ad979cca8141977b9e67b623",
                key.bytes.toHexLower(),
            )
        }
    }

    /** Pins the HKDF constants independently of the mnemonic path. */
    @Test
    fun `all zero entropy derives the pinned master key`() {
        MasterKeyFactory.fromEntropy(ByteArray(16)).use { key ->
            assertEquals(
                "14f1caf72ded0bd630c81c349db839795988a97827539fef4f97580b8b73eb1a",
                key.bytes.toHexLower(),
            )
        }
    }

    /**
     * The invariant that must never break: whatever `generate` hands out, `fromMnemonic`
     * on the displayed words must reproduce.
     */
    @Test
    fun `generate then recover from the displayed words yields the same key`() {
        repeat(50) {
            val generated = MasterKeyFactory.generate(codec)
            try {
                assertEquals(12, generated.mnemonic.size)
                assertEquals(32, generated.masterKey.bytes.size)

                MasterKeyFactory.fromMnemonic(codec, generated.mnemonic).use { recovered ->
                    assertEquals(
                        "第 $it 次:从助记词恢复出来的主密钥与生成的不一致",
                        generated.masterKey.bytes.toHexLower(),
                        recovered.bytes.toHexLower(),
                    )
                }
            } finally {
                generated.masterKey.wipe()
            }
        }
    }

    /** Recovery must tolerate the whitespace/case/noise a human typing from paper produces. */
    @Test
    fun `recovery survives messy human input`() {
        val generated = MasterKeyFactory.generate(codec)
        try {
            val messy = generated.mnemonic
                .mapIndexed { i, w -> if (i % 3 == 0) w.uppercase() else w }
                .joinToString(", ")
            MasterKeyFactory.fromMnemonic(codec, codec.normalize(messy)).use { recovered ->
                assertEquals(
                    generated.masterKey.bytes.toHexLower(),
                    recovered.bytes.toHexLower(),
                )
            }
        } finally {
            generated.masterKey.wipe()
        }
    }

    @Test
    fun `two generations produce different keys`() {
        val a = MasterKeyFactory.generate(codec)
        val b = MasterKeyFactory.generate(codec)
        try {
            assertNotEquals(a.masterKey.bytes.toHexLower(), b.masterKey.bytes.toHexLower())
            assertNotEquals(a.mnemonic, b.mnemonic)
        } finally {
            a.masterKey.wipe(); b.masterKey.wipe()
        }
    }

    @Test
    fun `fromEntropy is deterministic`() {
        val entropy = "18ab19a9f54a9274f03e5209a2ac8a91".hexToBytes()
        val a = MasterKeyFactory.fromEntropy(entropy)
        val b = MasterKeyFactory.fromEntropy(entropy)
        assertEquals(a.bytes.toHexLower(), b.bytes.toHexLower())
        a.wipe(); b.wipe()
    }

    @Test
    fun `different entropy gives different keys`() {
        val a = MasterKeyFactory.fromEntropy(ByteArray(16))
        val b = MasterKeyFactory.fromEntropy(ByteArray(16) { 1 })
        assertNotEquals(a.bytes.toHexLower(), b.bytes.toHexLower())
        a.wipe(); b.wipe()
    }

    @Test
    fun `master key length matches what SQLCipher expects`() {
        assertEquals(32, MasterKeyFactory.LENGTH_BYTES)
        MasterKeyFactory.fromEntropy(ByteArray(16)).use { assertEquals(32, it.bytes.size) }
    }

    @Test
    fun `empty entropy is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            MasterKeyFactory.fromEntropy(ByteArray(0))
        }
    }

    /**
     * Every word here is in the wordlist; only the combination is impossible.
     * (Verified: abandon x 12 fails the checksum, abandon x 11 + "about" passes.)
     */
    @Test
    fun `an invalid mnemonic surfaces ChecksumMismatch not a generic failure`() {
        val bad = List(12) { "abandon" }
        assertThrows(MnemonicException.ChecksumMismatch::class.java) {
            MasterKeyFactory.fromMnemonic(codec, bad)
        }
    }
}
