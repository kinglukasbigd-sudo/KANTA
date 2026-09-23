package mk.kanta.app.core.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthInputTest {

    // --- Code field: typing, pasting, autofill all funnel through sanitizeCode --------------

    @Test fun `plain six digits pass through`() = assertEquals("123456", AuthInput.sanitizeCode("123456"))

    @Test fun `pasted code with a space is cleaned`() = assertEquals("123456", AuthInput.sanitizeCode("123 456"))

    @Test fun `pasted code with a dash is cleaned`() = assertEquals("123456", AuthInput.sanitizeCode("123-456"))

    @Test fun `a whole pasted email line yields just the code`() =
        assertEquals("482913", AuthInput.sanitizeCode("Your Kanta code is 482913. It expires in 1 hour."))

    @Test fun `more than six digits is capped`() = assertEquals("123456", AuthInput.sanitizeCode("12345678"))

    @Test fun `letters are dropped`() = assertEquals("12", AuthInput.sanitizeCode("1a2b"))

    @Test fun `empty stays empty`() = assertEquals("", AuthInput.sanitizeCode(""))

    // --- Email: catch obvious typos before spending a rate-limited email -------------------

    @Test fun `normal address is plausible`() = assertTrue(AuthInput.isPlausibleEmail("ivan@example.mk"))

    @Test fun `surrounding spaces are tolerated`() = assertTrue(AuthInput.isPlausibleEmail("  ivan@example.mk "))

    @Test fun `missing at sign is rejected`() = assertFalse(AuthInput.isPlausibleEmail("ivan.example.mk"))

    @Test fun `two at signs are rejected`() = assertFalse(AuthInput.isPlausibleEmail("ivan@@example.mk"))

    @Test fun `missing domain dot is rejected`() = assertFalse(AuthInput.isPlausibleEmail("ivan@localhost"))

    @Test fun `trailing dot domain is rejected`() = assertFalse(AuthInput.isPlausibleEmail("ivan@example."))

    @Test fun `inner space is rejected`() = assertFalse(AuthInput.isPlausibleEmail("iv an@example.mk"))

    @Test fun `email is normalised to lowercase without spaces`() =
        assertEquals("ivan@example.mk", AuthInput.normalizeEmail("  Ivan@Example.MK "))

    // --- Display name ------------------------------------------------------------------------

    @Test fun `display name collapses whitespace`() =
        assertEquals("Иван Стојанов", AuthInput.normalizeDisplayName("  Иван    Стојанов "))

    @Test fun `one character name is too short`() = assertFalse(AuthInput.isValidDisplayName(" И "))

    @Test fun `two character name is fine`() = assertTrue(AuthInput.isValidDisplayName("Ив"))

    @Test fun `very long name is capped at 40`() =
        assertEquals(40, AuthInput.normalizeDisplayName("x".repeat(100)).length)
}
