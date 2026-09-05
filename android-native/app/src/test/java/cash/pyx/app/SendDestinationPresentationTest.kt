package cash.pyx.app

import cash.pyx.app.ui.SendDestinationPresentation
import cash.pyx.app.ui.SendDestinationPresentation.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SendDestinationPresentationTest {
    @Test fun classifiesDestinationsAndReusability() {
        assertEquals(Kind.LN_ADDRESS, SendDestinationPresentation.parse("alice@example.com").kind)
        assertTrue(SendDestinationPresentation.parse("alice@example.com").reusable)
        assertEquals(Kind.LNURL, SendDestinationPresentation.parse("lnurl1dp68gurn8ghj7").kind)
        assertEquals(Kind.BOLT12_OFFER, SendDestinationPresentation.parse("lno1qsgqmqvgm96frzdg8m0gc6nzeqffvzsqzrxqy32afmr3jn9ggkwg3egfwch2hy0l6jut6vfcarpplq").kind)
        assertEquals(Kind.BOLT12_INVOICE, SendDestinationPresentation.parse("lni1qqgds4gweqxey37gexf5jus4kcrwuq3").kind)
        assertFalse(SendDestinationPresentation.parse("lni1qqgds4gweqxey37gexf5jus4kcrwuq3").reusable)
        assertEquals(Kind.INVALID, SendDestinationPresentation.parse("abc").kind)
        assertEquals(Kind.INVALID, SendDestinationPresentation.parse("not a destination").kind)
    }

    @Test fun decodesBolt11AmountsPerUnitMultiplier() {
        // 17410n BTC = 1,741 sats (prototype's sample invoice prefix)
        assertEquals(1_741L, SendDestinationPresentation.bolt11Sats("lnbc17410n1p4rqx7vdqq"))
        assertEquals(100_000L, SendDestinationPresentation.bolt11Sats("lnbc1m1p"))
        assertEquals(2_100L, SendDestinationPresentation.bolt11Sats("lnbc21u1p"))
        assertEquals(21L, SendDestinationPresentation.bolt11Sats("lnbc210n1p"))
        assertEquals(100_000_000L, SendDestinationPresentation.bolt11Sats("lnbc11p"))
        assertNull(SendDestinationPresentation.bolt11Sats("lnbc1p"))
        assertNull(SendDestinationPresentation.bolt11Sats("lnbcxyz"))
    }

    @Test fun amountlessInvoiceLocksNothing() {
        assertNull(SendDestinationPresentation.parse("lnbcrt-something-without-amount").lockedAmountSat)
    }
}
