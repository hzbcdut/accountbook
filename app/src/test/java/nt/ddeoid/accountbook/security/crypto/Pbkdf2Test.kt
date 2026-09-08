package nt.ddeoid.accountbook.security.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * [Pbkdf2] cross-checked against OpenSSL.
 *
 * Every expected value below was produced by Python's `hashlib.pbkdf2_hmac('sha256', ...)`
 * (OpenSSL-backed), i.e. by an implementation that shares no code with ours. That is the
 * whole point of this file: without it the suite would only prove that our PBKDF2 agrees
 * with itself.
 *
 * The three short vectors at the top are the published PBKDF2-HMAC-SHA256 reference values
 * and match what every other implementation prints for them.
 */
class Pbkdf2Test {

    @Test
    fun `iteration 1`() = assertVector("password", "salt", 1, 32,
        "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b")

    @Test
    fun `iteration 2`() = assertVector("password", "salt", 2, 32,
        "ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43")

    @Test
    fun `iteration 4096`() = assertVector("password", "salt", 4096, 32,
        "c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a")

    /** Binary salt, PIN-shaped password, and a high iteration count. */
    @Test
    fun `binary salt at 60000 iterations`() {
        val actual = Pbkdf2.derive(
            password = "123456".toByteArray(Charsets.UTF_8),
            salt = ByteArray(16) { it.toByte() },
            iterations = 60_000,
            dkLen = 32,
        )
        assertEquals(
            "407cf2510c0ab6009daf6b18f2edfccf545287211d428264da036fb73d5361a7",
            actual.toHexLower(),
        )
    }

    /**
     * Output longer than one HMAC block, and **not** a multiple of 32.
     * This is the case that catches an off-by-one in the final-block truncation.
     */
    @Test
    fun `40 byte output spans two blocks with truncation`() = assertVector(
        "passwordPASSWORDpassword",
        "saltSALTsaltSALTsaltSALTsaltSALTsalt",
        4096,
        40,
        "348c89dbcbd32b2f32d814b8116e84cf2b17347ebc1800181c4e2a1fb8dd53e1c635518c7dac47e9",
    )

    /** Exactly two full blocks. */
    @Test
    fun `64 byte output is exactly two blocks`() {
        val actual = Pbkdf2.derive(
            password = "password".toByteArray(Charsets.UTF_8),
            salt = "salt".toByteArray(Charsets.UTF_8),
            iterations = 4096,
            dkLen = 64,
        )
        assertEquals(
            "c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a" +
                "f7ad98c1b458ce3fd74ca35beba3cda7b8d1038d6a87071b918f837405f3fe77",
            actual.toHexLower(),
        )
        // The first 32 bytes must equal the 32-byte derivation: PBKDF2 blocks are independent.
        assertArrayEquals(
            Pbkdf2.derive("password".toByteArray(), "salt".toByteArray(), 4096, 32),
            actual.copyOfRange(0, 32),
        )
    }

    /**
     * Non-ASCII password under UTF-8.
     *
     * This is the vector that justifies hand-rolling PBKDF2 instead of using
     * `SecretKeyFactory`: if we ever regress to an implementation that truncates each char
     * to its low 8 bits, "密码测试" would derive a different key and this test would fail.
     * Recovery on a new device depends on the derivation being byte-for-byte reproducible.
     */
    @Test
    fun `non-ascii password is utf-8 encoded`() = assertVector("密码测试", "盐值", 4096, 32,
        "cd7635c621220e8d8dcd5707b5f0a1f09c3278eb52b67cf245ad3baefce87da4")

    @Test
    fun `empty password is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            Pbkdf2.derive(ByteArray(0), "salt".toByteArray(), 10, 32)
        }
    }

    @Test
    fun `non-positive iterations are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            Pbkdf2.derive("p".toByteArray(), "salt".toByteArray(), 0, 32)
        }
    }

    @Test
    fun `non-positive length is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            Pbkdf2.derive("p".toByteArray(), "salt".toByteArray(), 10, 0)
        }
    }

    private fun assertVector(
        password: String,
        salt: String,
        iterations: Int,
        dkLen: Int,
        expectedHex: String,
    ) {
        val actual = Pbkdf2.derive(
            password = password.toByteArray(Charsets.UTF_8),
            salt = salt.toByteArray(Charsets.UTF_8),
            iterations = iterations,
            dkLen = dkLen,
        )
        assertEquals(expectedHex, actual.toHexLower())
    }
}
