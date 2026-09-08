package nt.ddeoid.accountbook.security.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SecretBytes] - the wipe discipline behind Q4=C.
 *
 * Small class, but the guarantee it makes is the one that decides whether "the key was
 * cleared from memory" is a true statement or a comment.
 */
class SecretBytesTest {

    @Test
    fun `wipe zeroes the contents`() {
        val secret = SecretBytes(byteArrayOf(1, 2, 3, 4))
        assertFalse(secret.isWiped)
        secret.wipe()
        assertTrue(secret.isWiped)
        assertTrue(secret.bytes.all { it == 0.toByte() })
    }

    @Test
    fun `wipe is idempotent`() {
        val secret = SecretBytes(byteArrayOf(9, 9))
        secret.wipe()
        secret.wipe()
        assertTrue(secret.isWiped)
    }

    @Test
    fun `use wipes on normal exit`() {
        val secret = SecretBytes(byteArrayOf(5, 6, 7))
        secret.use {
            assertEquals(3, it.bytes.size)
            assertFalse(it.isWiped)
        }
        assertTrue(secret.isWiped)
    }

    /**
     * Wiping must also happen when the block throws - that is the case that a `finally`-less
     * implementation would get wrong, and an exception path is exactly when key material is
     * most likely to be abandoned.
     */
    @Test
    fun `use wipes on exception`() {
        val secret = SecretBytes(byteArrayOf(5, 6, 7))
        runCatching {
            secret.use { throw IllegalStateException("boom") }
        }
        assertTrue(secret.isWiped)
    }

    @Test
    fun `copy is independent`() {
        val original = SecretBytes(byteArrayOf(1, 2, 3))
        val copy = original.copy()
        original.wipe()
        assertFalse(copy.isWiped)
        assertEquals(byteArrayOf(1, 2, 3).toHexLower(), copy.bytes.toHexLower())
        copy.wipe()
    }

    @Test
    fun `toString never reveals the contents`() {
        val secret = SecretBytes(byteArrayOf(0x41, 0x42))
        val rendered = secret.toString()
        assertFalse(rendered.contains("41"))
        assertFalse(rendered.contains("65"))
        assertFalse(rendered.contains("AB"))
        assertTrue(rendered.contains("hidden"))
        secret.wipe()
        assertTrue(secret.toString().contains("wiped"))
    }

    @Test
    fun `wipe of a char array zeroes it`() {
        val pin = "123456".toCharArray()
        SecretBytes.wipe(pin)
        assertTrue(pin.all { it == 0.toChar() })
        assertNotEquals('1', pin[0])
    }
}
