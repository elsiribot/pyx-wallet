package cash.pyx.app

import cash.pyx.app.ui.BitcoinAmountPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BitcoinAmountPresentationTest {
    @Test fun convertsExactBitcoinDecimalsToIntegerSats() {
        assertEquals(1L, BitcoinAmountPresentation.toSats("0.00000001"))
        assertEquals(100_000_000L, BitcoinAmountPresentation.toSats("1"))
        assertEquals(123_456_789L, BitcoinAmountPresentation.toSats("1.23456789"))
    }

    @Test fun rejectsRoundingNegativeZeroMalformedAndOverflow() {
        listOf("0", "-1", "0.000000001", "1.2.3", "", "999999999999999999999999")
            .forEach { assertNull(it, BitcoinAmountPresentation.toSats(it)) }
    }

    @Test fun formatsBip21AmountsAsPlainTrimmedBtcDecimals() {
        assertEquals("0.00000001", BitcoinAmountPresentation.toBtcDecimal(1))
        assertEquals("1", BitcoinAmountPresentation.toBtcDecimal(100_000_000))
        assertEquals("0.005", BitcoinAmountPresentation.toBtcDecimal(500_000))
        assertEquals(
            "bitcoin:bc1qexample?amount=0.00012345",
            cash.pyx.app.ui.Bip21Presentation.uri("bc1qexample", 12_345),
        )
    }
}
