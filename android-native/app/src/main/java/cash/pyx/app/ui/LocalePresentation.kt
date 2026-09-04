package cash.pyx.app.ui

import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

/** Locale affects display only; JNI/storage values remain canonical ASCII. */
object LocalePresentation {
    fun integer(value: Long, locale: Locale = Locale.getDefault()): String =
        NumberFormat.getIntegerInstance(locale).format(value)

    fun decimal(value: String, locale: Locale = Locale.getDefault()): String {
        val parsed = BigDecimal(value)
        val scale = parsed.scale().coerceAtLeast(0)
        return NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = scale
            maximumFractionDigits = scale
            isGroupingUsed = true
        }.format(parsed)
    }
}
