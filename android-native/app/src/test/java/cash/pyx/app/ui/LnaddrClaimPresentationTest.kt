package cash.pyx.app.ui

import cash.pyx.app.nativeapi.LnaddrQuote
import cash.pyx.app.nativeapi.LnaddrServer
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
        val invalid = LnaddrClaimPresentation.checkFromQuote(LnaddrQuote.Invalid("unsupported_domain"))
        assertTrue(invalid is ClaimCheck.Error)
        assertEquals(
            LnaddrClaimPresentation.invalidReasonText("unsupported_domain"),
            (invalid as ClaimCheck.Error).hint,
        )

        val rateLimited = LnaddrClaimPresentation.checkFromQuote(LnaddrQuote.RateLimited)
        assertTrue(rateLimited is ClaimCheck.Error)
    }

    /** The bridge folds `invalid_input`, `unsupported_domain` and `length_disabled` into the
     * one `invalid` quote state and separates them only by the reason code, so each has to
     * render as its own sentence — and an absent/unknown code must still say something rather
     * than leave the status line as a bare red dot. */
    @Test fun `each spec'd invalid reason renders its own non-empty text`() {
        val spec = listOf("invalid_input", "unsupported_domain", "length_disabled")
        val texts = spec.map { LnaddrClaimPresentation.invalidReasonText(it) }

        texts.forEach { assertTrue(it.isNotBlank()) }
        assertEquals(spec.size, texts.distinct().size)
        assertTrue(texts.any { it.contains("domain", ignoreCase = true) })
        assertTrue(texts.any { it.contains("length", ignoreCase = true) })
    }

    @Test fun `an unknown or empty invalid reason still renders something`() {
        assertTrue(LnaddrClaimPresentation.invalidReasonText("").isNotBlank())
        assertTrue(LnaddrClaimPresentation.invalidReasonText("brand_new_code").isNotBlank())

        val check = LnaddrClaimPresentation.checkFromQuote(LnaddrQuote.Invalid(""))
        assertTrue((check as ClaimCheck.Error).hint.isNotBlank())
    }

    @Test fun `originHost strips the scheme and any trailing slash`() {
        assertEquals("pyx.cash", LnaddrClaimPresentation.originHost("https://pyx.cash"))
        assertEquals("pyx.cash", LnaddrClaimPresentation.originHost("https://pyx.cash/"))
        assertEquals("pay.example.com:8443", LnaddrClaimPresentation.originHost("https://pay.example.com:8443"))
    }

    /** An announcement's `domains` are not bound to the announcing origin, so a hostile server
     * can advertise `pyx.cash`. The two options must not render identically. */
    @Test fun `a domain claimed by two origins is flagged on both options`() {
        val options = LnaddrClaimPresentation.domainOptions(
            listOf(
                LnaddrServer("https://pyx.cash", "pyx.cash", listOf("pyx.cash"), listOf("pyx.cash")),
                LnaddrServer("https://attacker.example.com", "Free names", listOf("pyx.cash"), listOf("pyx.cash")),
            ),
        )

        assertEquals(2, options.size)
        assertEquals(listOf("pyx.cash", "pyx.cash"), options.map { it.domain })
        assertEquals(listOf("pyx.cash", "attacker.example.com"), options.map { it.originLabel })
        assertEquals("https://pyx.cash", options.first().origin)
    }

    @Test fun `the built-in default alone stays unambiguous`() {
        val options = LnaddrClaimPresentation.domainOptions(
            listOf(LnaddrServer("https://pyx.cash", "pyx.cash", listOf("pyx.cash"), listOf("pyx.cash"))),
        )

        assertEquals(1, options.size)
        assertEquals("pyx.cash", options.single().domain)
        assertEquals(null, options.single().originLabel)
    }

    @Test fun `a domain hosted by a differently-named origin names its host`() {
        val options = LnaddrClaimPresentation.domainOptions(
            listOf(
                LnaddrServer(
                    "https://pay.example.com", "Example",
                    listOf("pay.example.com", "tips.example.org"),
                    listOf("pay.example.com"),
                ),
            ),
        )

        assertEquals(null, options[0].originLabel)
        assertEquals("pay.example.com", options[1].originLabel)
    }

    @Test fun `domainOptions preserves server order so the built-in default is first`() {
        val options = LnaddrClaimPresentation.domainOptions(
            listOf(
                LnaddrServer("https://pyx.cash", "pyx.cash", listOf("pyx.cash"), listOf("pyx.cash")),
                LnaddrServer("https://other.example.com", "Other", listOf("other.example.com"), emptyList()),
            ),
        )

        assertEquals("https://pyx.cash", options.first().origin)
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
