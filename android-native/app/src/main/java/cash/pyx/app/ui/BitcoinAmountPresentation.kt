package cash.pyx.app.ui

import java.math.BigDecimal
import java.math.RoundingMode

/** Decimal-only BTC input conversion. Money never crosses this boundary as a Double. */
object BitcoinAmountPresentation {
    private val satsPerBitcoin = BigDecimal("100000000")

    fun toSats(input: String): Long? {
        val value = input.trim().toBigDecimalOrNull() ?: return null
        if (value.signum() <= 0 || value.scale().coerceAtLeast(0) > 8) return null
        return runCatching {
            value.multiply(satsPerBitcoin).setScale(0, RoundingMode.UNNECESSARY).longValueExact()
        }.getOrNull()?.takeIf { it > 0 }
    }

    fun inputCharacterAllowed(character: Char): Boolean = character.isDigit() || character == '.'

    /** BIP21 amount: plain decimal BTC, no exponent, trailing zeros trimmed. */
    fun toBtcDecimal(sats: Long): String =
        BigDecimal(sats).divide(satsPerBitcoin).stripTrailingZeros().toPlainString()
}

/** BIP21 payment URI for a reusable address once an amount is entered. */
object Bip21Presentation {
    fun uri(address: String, amountSat: Long): String =
        "bitcoin:$address?amount=${BitcoinAmountPresentation.toBtcDecimal(amountSat)}"
}
