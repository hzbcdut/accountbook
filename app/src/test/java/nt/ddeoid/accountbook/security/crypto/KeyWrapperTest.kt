package nt.ddeoid.accountbook.security.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

/**
 * [KeyWrapper] - the AES-GCM layer that protects the master key at rest.
 *
 * The tests that matter most here are the **negative** ones. A wrapper that happily unwraps
 * a tampered blob, or that lets the biometric blob be used on the PIN path, is worse than no
 * wrapper at all: it looks like encryption while providing none of the guarantees.
 */
class KeyWrapperTest {

    private val wrapper = KeyWrapper()
    private val masterKey = SecretBytes(ByteArray(32) { (it + 1).toByte() })
    private val wrappingKey = ByteArray(32) { (it + 100).toByte() }

    @Test
    fun `wrap then unwrap returns the original key`() {
        val blob = wrapper.wrap(wrappingKey, masterKey, KeyWrapper.WrapContext.PIN)
        wrapper.unwrap(wrappingKey, blob, KeyWrapper.WrapContext.PIN).use { recovered ->
            assertEquals(masterKey.bytes.toHexLower(), recovered.bytes.toHexLower())
        }
    }

    @Test
    fun `the SecretKey overload agrees with the raw byte overload`() {
        val viaRaw = wrapper.wrap(wrappingKey, masterKey, KeyWrapper.WrapContext.PIN)
        val viaSecretKey = SecretKeySpec(wrappingKey, "AES")
        wrapper.unwrap(viaSecretKey, viaRaw, KeyWrapper.WrapContext.PIN).use { recovered ->
            assertEquals(masterKey.bytes.toHexLower(), recovered.bytes.toHexLower())
        }
    }

    /** A fresh random IV per wrap; identical plaintexts must not produce identical blobs. */
    @Test
    fun `wrapping the same key twice yields different blobs`() {
        val first = wrapper.wrap(wrappingKey, masterKey, KeyWrapper.WrapContext.PIN)
        val second = wrapper.wrap(wrappingKey, masterKey, KeyWrapper.WrapContext.PIN)
        assertNotEquals(first, second)
        // ...but both still unwrap to the same thing.
        wrapper.unwrap(wrappingKey, first, KeyWrapper.WrapContext.PIN).use { a ->
            wrapper.unwrap(wrappingKey, second, KeyWrapper.WrapContext.PIN).use { b ->
                assertEquals(a.bytes.toHexLower(), b.bytes.toHexLower())
            }
        }
    }

    @Test
    fun `a different wrapping key fails`() {
        val blob = wrapper.wrap(wrappingKey, masterKey, KeyWrapper.WrapContext.PIN)
        val wrongKey = ByteArray(32) { (it + 101).toByte() }
        assertThrows(CryptoException.UnwrapFailed::class.java) {
            wrapper.unwrap(wrongKey, blob, KeyWrapper.WrapContext.PIN)
        }
    }

    /**
     * AAD binds a blob to the path that produced it.
     *
     * Without this, the PIN blob and the biometric blob would be interchangeable whenever
     * the two wrapping keys happened to coincide.
     */
    @Test
    fun `a PIN blob cannot be unwrapped on the biometric path`() {
        val pinBlob = wrapper.wrap(wrappingKey, masterKey, KeyWrapper.WrapContext.PIN)
        assertThrows(CryptoException.UnwrapFailed::class.java) {
            wrapper.unwrap(wrappingKey, pinBlob, KeyWrapper.WrapContext.BIOMETRIC)
        }
    }

    @Test
    fun `a biometric blob cannot be unwrapped on the PIN path`() {
        val bioBlob = wrapper.wrap(wrappingKey, masterKey, KeyWrapper.WrapContext.BIOMETRIC)
        assertThrows(CryptoException.UnwrapFailed::class.java) {
            wrapper.unwrap(wrappingKey, bioBlob, KeyWrapper.WrapContext.PIN)
        }
    }

    @Test
    fun `flipping one bit of the ciphertext fails`() {
        val blob = wrapper.wrap(wrappingKey, masterKey, KeyWrapper.WrapContext.PIN)
        val bytes = java.util.Base64.getDecoder().decode(blob)
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        val tampered = java.util.Base64.getEncoder().encodeToString(bytes)
        assertThrows(CryptoException.UnwrapFailed::class.java) {
            wrapper.unwrap(wrappingKey, tampered, KeyWrapper.WrapContext.PIN)
        }
    }

    @Test
    fun `flipping one bit of the IV fails`() {
        val blob = wrapper.wrap(wrappingKey, masterKey, KeyWrapper.WrapContext.PIN)
        val bytes = java.util.Base64.getDecoder().decode(blob)
        bytes[0] = (bytes[0].toInt() xor 0x80).toByte()
        val tampered = java.util.Base64.getEncoder().encodeToString(bytes)
        assertThrows(CryptoException.UnwrapFailed::class.java) {
            wrapper.unwrap(wrappingKey, tampered, KeyWrapper.WrapContext.PIN)
        }
    }

    @Test
    fun `not base64 is reported as corruption not as a wrong key`() {
        assertThrows(CryptoException.BlobCorrupted::class.java) {
            wrapper.unwrap(wrappingKey, "!!! not base64 !!!", KeyWrapper.WrapContext.PIN)
        }
    }

    /** Shorter than IV(12) + GCM tag(16). Must not reach the cipher with a malformed input. */
    @Test
    fun `a truncated blob is reported as corruption`() {
        val tooShort = java.util.Base64.getEncoder().encodeToString(ByteArray(20))
        assertThrows(CryptoException.BlobCorrupted::class.java) {
            wrapper.unwrap(wrappingKey, tooShort, KeyWrapper.WrapContext.PIN)
        }
    }

    @Test
    fun `an empty blob is reported as corruption`() {
        assertThrows(CryptoException.BlobCorrupted::class.java) {
            wrapper.unwrap(wrappingKey, "", KeyWrapper.WrapContext.PIN)
        }
    }

    @Test
    fun `wrapping an empty payload still round trips`() {
        val empty = SecretBytes(ByteArray(0))
        val blob = wrapper.wrap(wrappingKey, empty, KeyWrapper.WrapContext.PIN)
        wrapper.unwrap(wrappingKey, blob, KeyWrapper.WrapContext.PIN).use { recovered ->
            assertEquals(0, recovered.bytes.size)
        }
    }

    @Test
    fun `AAD differs between the two contexts`() {
        assertFalse(
            KeyWrapper.WrapContext.PIN.aad.contentEquals(KeyWrapper.WrapContext.BIOMETRIC.aad),
        )
        assertTrue(KeyWrapper.WrapContext.PIN.aad.isNotEmpty())
    }
}
