package nt.ddeoid.accountbook.security.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * [Hkdf] against the RFC 5869 Appendix A test vectors (SHA-256 variants).
 *
 * All **inputs** are generated from byte ranges rather than typed as hex literals: the first
 * version of this file had two transcription errors (a 21-byte IKM where the RFC has 22, and
 * a 160-byte salt where the RFC has 80) which produced three failures that looked exactly
 * like a broken `expand`. Generating the inputs makes that class of mistake impossible and
 * puts the byte counts in the same place as the ranges that produce them.
 *
 * The **expected** values are the RFC's own, cross-checked with OpenSSL before being pasted.
 */
class HkdfTest {

    /** A.1 - basic case; 42 bytes of output spans two hash blocks. */
    @Test
    fun `rfc5869 A1`() {
        val okm = Hkdf.derive(
            ikm = ByteArray(22) { 0x0b },          // 22 bytes of 0x0b
            salt = bytesInRange(0x00..0x0c),        // 13 bytes
            info = bytesInRange(0xf0..0xf9),        // 10 bytes
            length = 42,
        )
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865",
            okm.toHexLower(),
        )
    }

    /** A.2 - three blocks of output, so the block-chaining path in `expand` is exercised. */
    @Test
    fun `rfc5869 A2 multi block`() {
        val okm = Hkdf.derive(
            ikm = bytesInRange(0x00..0x4f),         // 80 bytes
            salt = bytesInRange(0x60..0xaf),        // 80 bytes (RFC A.2 salt ends at 0xaf)
            info = bytesInRange(0xb0..0xff),        // 80 bytes
            length = 82,
        )
        assertEquals(
            "b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c" +
                "59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db71" +
                "cc30c58179ec3e87c14c01d5c1f3434f1d87",
            okm.toHexLower(),
        )
    }

    /** A.3 - empty salt **and** empty info. Omitted salt == HashLen zeroes per RFC. */
    @Test
    fun `rfc5869 A3 empty salt and info`() {
        val okm = Hkdf.derive(
            ikm = ByteArray(22) { 0x0b },
            salt = ByteArray(0),
            info = ByteArray(0),
            length = 42,
        )
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d" +
                "9d201395faa4b61a96c8",
            okm.toHexLower(),
        )
    }

    /**
     * Output exactly one block - the case production actually uses (128-bit BIP39 entropy
     * -> 256-bit master key). A.1/A.2/A.3 all request more than one block, so without this
     * the single-block path would only be covered indirectly by MasterKeyFactoryTest.
     */
    @Test
    fun `single block output is not padded`() {
        val okm = Hkdf.derive(
            ikm = ByteArray(22) { 0x0b },
            salt = bytesInRange(0x00..0x0c),
            info = bytesInRange(0xf0..0xf9),
            length = 32,
        )
        // Must equal the first 32 bytes of the 42-byte A.1 output.
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf",
            okm.toHexLower(),
        )
    }

    /** Different `info` must give a different key - this is what binds a key to its purpose. */
    @Test
    fun `info binds the output to its purpose`() {
        val ikm = ByteArray(16) { 0x42 }
        val salt = ByteArray(16)
        val a = Hkdf.derive(ikm, salt, "accountbook/master-key/v1".toByteArray(), 32)
        val b = Hkdf.derive(ikm, salt, "accountbook/master-key/v2".toByteArray(), 32)
        assertEquals(false, a.contentEquals(b))
    }

    @Test
    fun `empty ikm is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            Hkdf.derive(ByteArray(0), ByteArray(16), "info".toByteArray(), 32)
        }
    }

    @Test
    fun `zero length is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            Hkdf.derive(ByteArray(16), ByteArray(16), "info".toByteArray(), 0)
        }
    }

    @Test
    fun `length beyond 255 hash blocks is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            Hkdf.derive(ByteArray(16), ByteArray(16), "info".toByteArray(), 255 * 32 + 1)
        }
    }

    private fun bytesInRange(range: IntRange): ByteArray =
        ByteArray(range.last - range.first + 1) { i -> (range.first + i).toByte() }
}
