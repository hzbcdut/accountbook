package nt.ddeoid.accountbook.security.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PinPolicy] - what the app will accept as a secret.
 *
 * Pinned by test because this is the line that decides how much offline brute-force
 * resistance the whole vault has. Loosening it should require editing a test, not just a
 * constant.
 */
class PinPolicyTest {

    @Test
    fun `six digits is the minimum accepted pin`() {
        val result = PinPolicy.validate("123456")
        assertTrue(result is PinPolicy.Result.Accepted)
        assertTrue((result as PinPolicy.Result.Accepted).digitOnly)
    }

    @Test
    fun `five digits is rejected with the digit minimum`() {
        val result = PinPolicy.validate("12345")
        assertTrue(result is PinPolicy.Result.TooShort)
        result as PinPolicy.Result.TooShort
        assertEquals(PinPolicy.MIN_DIGIT_PIN_LENGTH, result.required)
        assertTrue(result.digitOnly)
    }

    @Test
    fun `a long numeric pin stays on the digit track`() {
        val result = PinPolicy.validate("1".repeat(20))
        assertTrue(result is PinPolicy.Result.Accepted)
        assertTrue((result as PinPolicy.Result.Accepted).digitOnly)
    }

    /**
     * Anything containing a non-digit is treated as a passphrase and held to the higher bar.
     * 2^47 for 8 random alphanumeric characters is already out of reach for offline attack,
     * which is why the passphrase minimum is 8 rather than 6.
     */
    @Test
    fun `eight mixed characters is accepted as a passphrase`() {
        val result = PinPolicy.validate("hunter2x")
        assertTrue(result is PinPolicy.Result.Accepted)
        assertFalse((result as PinPolicy.Result.Accepted).digitOnly)
    }

    @Test
    fun `seven mixed characters is rejected with the passphrase minimum`() {
        val result = PinPolicy.validate("hunter2")
        assertTrue(result is PinPolicy.Result.TooShort)
        result as PinPolicy.Result.TooShort
        assertEquals(PinPolicy.MIN_PASSPHRASE_LENGTH, result.required)
        assertFalse(result.digitOnly)
    }

    @Test
    fun `a single letter forces the passphrase track`() {
        val result = PinPolicy.validate("123456a")
        assertTrue(result is PinPolicy.Result.TooShort)
        assertEquals(PinPolicy.MIN_PASSPHRASE_LENGTH, (result as PinPolicy.Result.TooShort).required)
    }

    @Test
    fun `empty is its own result so the message can differ from too short`() {
        assertEquals(PinPolicy.Result.Empty, PinPolicy.validate(""))
    }

    @Test
    fun `over the maximum is rejected`() {
        val result = PinPolicy.validate("a".repeat(PinPolicy.MAX_LENGTH + 1))
        assertTrue(result is PinPolicy.Result.TooLong)
        assertEquals(PinPolicy.MAX_LENGTH, (result as PinPolicy.Result.TooLong).max)
    }

    @Test
    fun `exactly at the maximum is accepted`() {
        assertTrue(PinPolicy.validate("a".repeat(PinPolicy.MAX_LENGTH)) is PinPolicy.Result.Accepted)
    }

    /**
     * `Char.isDigit()` is Unicode-aware, so localized digits count as digits.
     *
     * This is deliberate rather than accidental, and worth pinning: the derivation runs over
     * the UTF-8 bytes of whatever the user typed, so Arabic-Indic or Devanagari digits are
     * just as deterministic and just as strong (10 symbols each) as ASCII ones. Restricting
     * to ASCII would only serve to reject a legitimate keyboard layout.
     */
    @Test
    fun `localized digits are treated as digits`() {
        val arabicIndic = "١٢٣٤٥٦" // ١٢٣٤٥٦
        val result = PinPolicy.validate(arabicIndic)
        assertTrue(result is PinPolicy.Result.Accepted)
        assertTrue((result as PinPolicy.Result.Accepted).digitOnly)
    }

    /** Spaces are not stripped: a passphrase's spacing is part of its entropy. */
    @Test
    fun `spaces count towards length and break the digit track`() {
        val result = PinPolicy.validate("correct horse")
        assertTrue(result is PinPolicy.Result.Accepted)
        assertFalse((result as PinPolicy.Result.Accepted).digitOnly)
    }
}
