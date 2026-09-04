package cash.pyx.app

import cash.pyx.app.ui.LightningInvoiceExpiryPresentation
import org.junit.Assert.assertEquals
import org.junit.Test

class LightningInvoiceExpiryPresentationTest {
    @Test fun `countdown is derived from authoritative epoch expiry`() {
        assertEquals(65L, LightningInvoiceExpiryPresentation.remainingSeconds(1_065, 1_000_000))
        assertEquals("Expires in 1m 5s", LightningInvoiceExpiryPresentation.text(1_065, 1_000_000))
    }

    @Test fun `expiry boundary and elapsed invoices are explicitly expired`() {
        assertEquals("Expired", LightningInvoiceExpiryPresentation.text(1_000, 1_000_000))
        assertEquals("Expired", LightningInvoiceExpiryPresentation.text(999, 1_000_000))
    }

    @Test fun `long durations use a stable compact presentation`() {
        assertEquals("Expires in 2h 3m", LightningInvoiceExpiryPresentation.text(8_380, 1_000_000))
    }
}
