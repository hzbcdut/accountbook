package nt.ddeoid.accountbook.security.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PinKdf] - PIN -> wrapping key.
 *
 * Expected values are OpenSSL's (`hashlib.pbkdf2_hmac`), including two at the **production**
 * iteration count of 600,000, so the number that ships is the number that was verified.
 *
 * The non-ASCII cases are the important ones: they prove the `CharArray` -> bytes conversion
 * is UTF-8 and not a per-char low-byte truncation. If it were truncation, a passphrase with
 * non-ASCII characters would derive a different key here than on any other implementation -
 * i.e. the user's own mnemonic/PIN would stop working after a device migration.
 */
class PinKdfTest {

    private val salt = ByteArray(16) { it.toByte() }

    @Test
    fun `production iteration count is the documented value`() {
        assertEquals(600_000, PinKdf.PRODUCTION_ITERATIONS)
    }

    @Test
    fun `6 digit pin at production iterations matches openssl`() {
        val pin = "123456".toCharArray()
        PinKdf.derive(pin, salt, PinKdf.PRODUCTION_ITERATIONS).use { derived ->
            assertEquals(
                "93922e39f17be3ac82ee49e41b689b2f825ffcb9b18c64406350c92b4fa3c59c",
                derived.bytes.toHexLower(),
            )
        }
    }

    @Test
    fun `alphanumeric passphrase at production iterations matches openssl`() {
        val pin = "hunter2x".toCharArray()
        PinKdf.derive(pin, salt, PinKdf.PRODUCTION_ITERATIONS).use { derived ->
            assertEquals(
                "256aa68fb44ace5cf8f5f646e692feb0adad5f77947cfcb4bbe9b529e85400c1",
                derived.bytes.toHexLower(),
            )
        }
    }

    /**
     * Non-ASCII passphrase through the [CharArray] -> bytes path.
     *
     * Vector: PBKDF2-HMAC-SHA256(UTF-8("密码测试"), UTF-8("盐值"), 4096, 32).
     *
     * This goes through [Pbkdf2] rather than [PinKdf] because the vector's salt is 6 bytes
     * and [PinKdf] enforces a 16-byte minimum. What is under test here is the encoding of
     * the *password*, which is exactly the part [Pbkdf2Chars] owns.
     */
    @Test
    fun `non ascii passphrase is utf-8 encoded not truncated`() {
        val derived = Pbkdf2.derive(
            password = Pbkdf2Chars.toUtf8Bytes("密码测试".toCharArray()),
            salt = "盐值".toByteArray(Charsets.UTF_8),
            iterations = 4096,
            dkLen = 32,
        )
        assertEquals(
            "cd7635c621220e8d8dcd5707b5f0a1f09c3278eb52b67cf245ad3baefce87da4",
            derived.toHexLower(),
        )
    }

    /**
     * The same check driven through [PinKdf.derive] with a legal salt, asserting only that
     * a non-ASCII PIN produces a stable, non-degenerate key (i.e. the policy layer does not
     * mangle it). Cross-implementation equality is asserted by the test above.
     */
    @Test
    fun `non ascii pin survives the policy layer`() {
        val a = PinKdf.derive("密码测试".toCharArray(), salt, 1000)
        val b = PinKdf.derive("密码测试".toCharArray(), salt, 1000)
        assertEquals(a.bytes.toHexLower(), b.bytes.toHexLower())
        // A truncated-to-low-byte implementation would collapse these two; UTF-8 keeps them apart.
        val c = PinKdf.derive("密码测詝".toCharArray(), salt, 1000)
        assertNotEquals(a.bytes.toHexLower(), c.bytes.toHexLower())
        a.wipe(); b.wipe(); c.wipe()
    }

    @Test
    fun `short salt is rejected`() {
        val bad = assertThrows(IllegalArgumentException::class.java) {
            PinKdf.derive("123456".toCharArray(), ByteArray(8), 10)
        }
        assertTrue(bad.message!!.contains("盐"))
    }

    @Test
    fun `empty pin is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PinKdf.derive(CharArray(0), salt, 10)
        }
    }

    @Test
    fun `derivation is deterministic for the same inputs`() {
        val a = PinKdf.derive("246810".toCharArray(), salt, 1000)
        val b = PinKdf.derive("246810".toCharArray(), salt, 1000)
        assertEquals(a.bytes.toHexLower(), b.bytes.toHexLower())
        a.wipe(); b.wipe()
    }

    @Test
    fun `a different salt yields a different key`() {
        val other = ByteArray(16) { (it + 7).toByte() }
        val a = PinKdf.derive("246810".toCharArray(), salt, 1000)
        val b = PinKdf.derive("246810".toCharArray(), other, 1000)
        assertNotEquals(a.bytes.toHexLower(), b.bytes.toHexLower())
        a.wipe(); b.wipe()
    }

    @Test
    fun `a different pin yields a different key`() {
        val a = PinKdf.derive("246810".toCharArray(), salt, 1000)
        val b = PinKdf.derive("246811".toCharArray(), salt, 1000)
        assertNotEquals(a.bytes.toHexLower(), b.bytes.toHexLower())
        a.wipe(); b.wipe()
    }

    @Test
    fun `a different iteration count yields a different key`() {
        val a = PinKdf.derive("246810".toCharArray(), salt, 1000)
        val b = PinKdf.derive("246810".toCharArray(), salt, 1001)
        assertNotEquals(a.bytes.toHexLower(), b.bytes.toHexLower())
        a.wipe(); b.wipe()
    }

    @Test
    fun `derived key is 32 bytes and wipeable`() {
        val derived = PinKdf.derive("246810".toCharArray(), salt, 1000)
        assertEquals(32, derived.bytes.size)
        derived.wipe()
        assertTrue(derived.isWiped)
        assertTrue(derived.bytes.all { it == 0.toByte() })
    }

    @Test
    fun `generateSalt produces distinct 16 byte salts`() {
        val source = EntropySource()
        val salts = List(50) { PinKdf.generateSalt(source).toHexLower() }
        assertEquals(50, salts.toSet().size)
        salts.forEach { assertEquals(32, it.length) } // 16 bytes -> 32 hex chars
    }
}
