package cash.pyx.app.ui

import cash.pyx.app.nativeapi.LnaddrQuote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LnaddrClaimPresentationTest {
    @Test fun `sanitizeUsername folds case, strips disallowed characters, and caps length`() {
        assertEquals("johndoe", LnaddrClaimPresentation.sanitizeUsername("JohnDoe"))
        assertEquals("johndoe-1.a", LnaddrClaimPresentation.sanitizeUsername("john doe-1.a!$"))
        assertEquals("a".repeat(64), LnaddrClaimPresentation.sanitizeUsername("a".repeat(100)))
    }

    @Test fun `sanitizeUsername keeps only lowercase letters digits dash underscore dot`() {
        assertEquals("abc123", LnaddrClaimPresentation.sanitizeUsername("abc123"))
        assertEquals("", LnaddrClaimPresentation.sanitizeUsername("!@#$%^&*()"))
        assertEquals("a-b_c.d", LnaddrClaimPresentation.sanitizeUsername("a-b_c.d"))
    }

    @Test fun `checkFromQuote maps Free to Available`() {
        assertEquals(ClaimCheck.Available, LnaddrClaimPresentation.checkFromQuote(LnaddrQuote.Free))
    }

    @Test fun `checkFromQuote maps Paid to disabled Paid check`() {
        val check = LnaddrClaimPresentation.checkFromQuote(LnaddrQuote.Paid(5000))
        assertEquals(ClaimCheck.Paid(5000), check)
        assertFalse(LnaddrClaimPresentation.canClaim(check, "alice"))
    }

    @Test fun `checkFromQuote maps Taken and Reserved`() {
        assertEquals(ClaimCheck.Taken, LnaddrClaimPresentation.checkFromQuote(LnaddrQuote.Taken))
        assertEquals(ClaimCheck.Reserved, LnaddrClaimPresentation.checkFromQuote(LnaddrQuote.Reserved))
    }

    @Test fun `checkFromQuote maps Invalid and RateLimited to Error`() {
        val invalid = LnaddrClaimPresentation.checkFromQuote(LnaddrQuote.Invalid("bad username"))
        assertTrue(invalid is ClaimCheck.Error)
        assertEquals("bad username", (invalid as ClaimCheck.Error).hint)

        val rateLimited = LnaddrClaimPresentation.checkFromQuote(LnaddrQuote.RateLimited)
        assertTrue(rateLimited is ClaimCheck.Error)
    }

    @Test fun `canClaim requires Available and a non-blank username`() {
        assertTrue(LnaddrClaimPresentation.canClaim(ClaimCheck.Available, "alice"))
        assertFalse(LnaddrClaimPresentation.canClaim(ClaimCheck.Available, ""))
        assertFalse(LnaddrClaimPresentation.canClaim(ClaimCheck.Available, "   "))
        assertFalse(LnaddrClaimPresentation.canClaim(ClaimCheck.Taken, "alice"))
        assertFalse(LnaddrClaimPresentation.canClaim(ClaimCheck.Checking, "alice"))
        assertFalse(LnaddrClaimPresentation.canClaim(ClaimCheck.Idle, "alice"))
    }

    @Test fun `clockHint appends a device-clock hint for unauthorized messages`() {
        val hinted = LnaddrClaimPresentation.clockHint("Request unauthorized by server")
        assertTrue(hinted.contains("unauthorized"))
        assertTrue(hinted.lowercase().contains("clock"))
    }

    @Test fun `clockHint is case-insensitive and leaves other messages untouched`() {
        val hinted = LnaddrClaimPresentation.clockHint("UNAUTHORIZED")
        assertTrue(hinted.lowercase().contains("clock"))

        assertEquals("Server offline", LnaddrClaimPresentation.clockHint("Server offline"))
    }
}
