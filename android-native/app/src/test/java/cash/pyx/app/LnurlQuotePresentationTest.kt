package cash.pyx.app

import cash.pyx.app.ui.LnurlQuotePresentation
import cash.pyx.app.ui.WalletOperation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LnurlQuotePresentationTest {
    @Test fun `fixed session resolves native amount and variable session uses input`() {
        assertEquals(50L, LnurlQuotePresentation.resolveAmount(WalletOperation.LnurlPrepared(1, 50, 50, true), ""))
        assertEquals(75L, LnurlQuotePresentation.resolveAmount(WalletOperation.LnurlPrepared(1, 50, 100, false), "75"))
        assertNull(LnurlQuotePresentation.resolveAmount(WalletOperation.LnurlPrepared(1, 50, 100, false), "bad"))
    }

    @Test fun `range validation gates quote preparation`() {
        val session = WalletOperation.LnurlPrepared(1, 50, 100, false)
        assertFalse(LnurlQuotePresentation.canPrepare(session, 49))
        assertTrue(LnurlQuotePresentation.canPrepare(session, 50))
        assertTrue(LnurlQuotePresentation.canPrepare(session, 100))
        assertFalse(LnurlQuotePresentation.canPrepare(session, 101))
    }
}
