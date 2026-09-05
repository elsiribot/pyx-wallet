package cash.pyx.app.ui

import cash.pyx.app.nativeapi.FiatCurrency

/** Pure grouping/filter/flag logic for the prototype currency picker. */
object CurrencyPickerPresentation {
    data class BtcUnit(val code: String, val name: String)
    data class Sections(val btcUnits: List<BtcUnit>, val fiat: List<FiatCurrency>) {
        val separator: Boolean get() = btcUnits.isNotEmpty() && fiat.isNotEmpty()
        val empty: Boolean get() = btcUnits.isEmpty() && fiat.isEmpty()
    }

    val btcUnits = listOf(BtcUnit("SATS", "Satoshis"), BtcUnit("BTC", "Bitcoin"))

    fun sections(query: String, currencies: List<FiatCurrency>, includeBtcUnits: Boolean): Sections {
        val q = query.trim()
        fun matches(code: String, name: String) = q.isBlank() || code.contains(q, true) || name.contains(q, true)
        return Sections(
            if (includeBtcUnits) btcUnits.filter { matches(it.code, it.name) } else emptyList(),
            currencies.filter { matches(it.code, it.name) }.sortedBy { it.code },
        )
    }

    /**
     * Flag emoji derived from the ISO-4217 country prefix. Supranational and
     * non-country codes fall back to the currency symbol ("EU" happens to be a
     * valid regional-indicator pair, so EUR renders the EU flag naturally).
     */
    fun flag(code: String, fallback: String): String {
        if (code.length != 3 || code.startsWith("X")) return fallback
        val country = code.substring(0, 2)
        if (country.any { it !in 'A'..'Z' }) return fallback
        return String(Character.toChars(0x1F1E6 + (country[0] - 'A'))) +
            String(Character.toChars(0x1F1E6 + (country[1] - 'A')))
    }
}
