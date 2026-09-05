package cash.pyx.app.ui

import java.math.BigDecimal

/**
 * Client-side classification of a typed Lightning destination, used only to gate
 * the amount field like the prototype (blocked / locked / editable) and to decide
 * dropdown affordances. The authoritative amount always remains the native quote.
 */
object SendDestinationPresentation {
    enum class Kind { INVALID, LN_ADDRESS, LNURL, BOLT12_OFFER, BOLT12_INVOICE, BOLT11 }

    data class Destination(val kind: Kind, val reusable: Boolean, val lockedAmountSat: Long?) {
        val valid: Boolean get() = kind != Kind.INVALID
    }

    private val lnAddress = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    fun parse(input: String): Destination {
        val value = input.trim()
        return when {
            value.length < 4 -> Destination(Kind.INVALID, reusable = false, lockedAmountSat = null)
            lnAddress.matches(value) -> Destination(Kind.LN_ADDRESS, reusable = true, lockedAmountSat = null)
            value.startsWith("lnurl", ignoreCase = true) -> Destination(Kind.LNURL, reusable = true, lockedAmountSat = null)
            value.startsWith("lno", ignoreCase = true) -> Destination(Kind.BOLT12_OFFER, reusable = true, lockedAmountSat = null)
            value.startsWith("lni", ignoreCase = true) -> Destination(Kind.BOLT12_INVOICE, reusable = false, lockedAmountSat = null)
            value.startsWith("lnbc", ignoreCase = true) ->
                Destination(Kind.BOLT11, reusable = false, lockedAmountSat = bolt11Sats(value))
            else -> Destination(Kind.INVALID, reusable = false, lockedAmountSat = null)
        }
    }

    /** Amount baked into a BOLT11 HRP, in sats; null for amountless or unparseable invoices. */
    fun bolt11Sats(invoice: String): Long? {
        val match = Regex("^lnbc(\\d+)([munp]?)1", RegexOption.IGNORE_CASE).find(invoice.trim()) ?: return null
        val quantity = match.groupValues[1].toBigDecimalOrNull() ?: return null
        val satsPerUnit = when (match.groupValues[2].lowercase()) {
            "m" -> BigDecimal("100000")      // milli-BTC
            "u" -> BigDecimal("100")         // micro-BTC
            "n" -> BigDecimal("0.1")         // nano-BTC
            "p" -> BigDecimal("0.0001")      // pico-BTC
            else -> BigDecimal("100000000")  // whole BTC
        }
        return runCatching { quantity.multiply(satsPerUnit).longValueExact() }.getOrNull()?.takeIf { it > 0 }
    }
}
