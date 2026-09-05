package cash.pyx.app

import cash.pyx.app.nativeapi.FiatCurrency
import cash.pyx.app.ui.CurrencyPickerPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrencyPickerPresentationTest {
    private val currencies = listOf(
        FiatCurrency("USD", "US Dollar", "$", 2),
        FiatCurrency("EUR", "Euro", "€", 2),
        FiatCurrency("JPY", "Japanese Yen", "¥", 0),
    )

    @Test fun pinsBtcUnitsAndSortsFiatByCode() {
        val sections = CurrencyPickerPresentation.sections("", currencies, includeBtcUnits = true)
        assertEquals(listOf("SATS", "BTC"), sections.btcUnits.map { it.code })
        assertEquals(listOf("EUR", "JPY", "USD"), sections.fiat.map { it.code })
        assertTrue(sections.separator)
    }

    @Test fun filterMatchesCodeAndNameCaseInsensitivelyAndHidesSeparator() {
        val sections = CurrencyPickerPresentation.sections("yen", currencies, includeBtcUnits = true)
        assertEquals(emptyList<String>(), sections.btcUnits.map { it.code })
        assertEquals(listOf("JPY"), sections.fiat.map { it.code })
        assertFalse(sections.separator)
        val byCode = CurrencyPickerPresentation.sections("usd", currencies, includeBtcUnits = false)
        assertEquals(listOf("USD"), byCode.fiat.map { it.code })
    }

    @Test fun emptyWhenNothingMatches() {
        assertTrue(CurrencyPickerPresentation.sections("zzz", currencies, includeBtcUnits = true).empty)
    }

    @Test fun derivesFlagsWithFallbacksForNonCountryCodes() {
        assertEquals("🇺🇸", CurrencyPickerPresentation.flag("USD", "$"))
        assertEquals("🇪🇺", CurrencyPickerPresentation.flag("EUR", "€"))
        assertEquals("Fr", CurrencyPickerPresentation.flag("XOF", "Fr"))
        assertEquals("?", CurrencyPickerPresentation.flag("B1T", "?"))
    }
}
