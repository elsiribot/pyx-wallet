package cash.pyx.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiveLnaddrPresentationTest {
    private fun surface(
        tab: Int = 0,
        amount: String = "",
        typing: Boolean = false,
        title: String? = "LNURL receive",
        hasPrimary: Boolean = false,
    ) = ReceiveLnaddrPresentation.surface(tab, amount, typing, title, hasPrimary)

    @Test fun `amountless LNURL without a primary address offers the claim banner`() {
        assertEquals(ReceiveLnaddrSurface.CLAIM_BANNER, surface())
    }

    @Test fun `amountless LNURL with a primary address shows the address row`() {
        assertEquals(ReceiveLnaddrSurface.ADDRESS_ROW, surface(hasPrimary = true))
    }

    @Test fun `a typed amount hides both surfaces`() {
        assertEquals(ReceiveLnaddrSurface.NONE, surface(amount = "1000"))
        assertEquals(ReceiveLnaddrSurface.NONE, surface(amount = "1000", hasPrimary = true))
    }

    @Test fun `mid-typing debounce hides both surfaces`() {
        assertEquals(ReceiveLnaddrSurface.NONE, surface(typing = true, hasPrimary = true))
    }

    @Test fun `a displayed invoice or missing code hides both surfaces`() {
        assertEquals(ReceiveLnaddrSurface.NONE, surface(title = "Lightning invoice", hasPrimary = true))
        assertEquals(ReceiveLnaddrSurface.NONE, surface(title = null, hasPrimary = true))
    }

    @Test fun `the on-chain and ecash tabs hide both surfaces`() {
        assertEquals(ReceiveLnaddrSurface.NONE, surface(tab = 1, title = "On-chain address", hasPrimary = true))
        assertEquals(ReceiveLnaddrSurface.NONE, surface(tab = 2, hasPrimary = true))
    }
}
