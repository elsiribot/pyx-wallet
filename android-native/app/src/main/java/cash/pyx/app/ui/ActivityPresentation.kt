package cash.pyx.app.ui

import cash.pyx.app.nativeapi.Payment
import cash.pyx.app.nativeapi.PaymentDirection
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

data class PaymentDay(val date: LocalDate, val payments: List<Payment>)
data class TechnicalField(val label: String, val value: String, val copyable: Boolean, val sensitive: Boolean)

object ActivityPresentation {
    /** Compose identity must follow the durable native operation, never row position. */
    fun itemKey(payment: Payment): String = payment.operationId

    fun group(payments: List<Payment>, zoneId: ZoneId = ZoneId.systemDefault()): List<PaymentDay> =
        payments.sortedWith(compareByDescending<Payment> { it.timestampMillis }.thenBy { it.operationId })
            .groupBy { Instant.ofEpochMilli(it.timestampMillis).atZone(zoneId).toLocalDate() }
            .map { PaymentDay(it.key, it.value) }

    fun amount(payment: Payment, locale: Locale = Locale.getDefault()): String =
        "${if (payment.direction == PaymentDirection.INCOMING) "+" else "−"}${LocalePresentation.integer(payment.amountSat, locale)} sats"

    fun historicalFiat(payment: Payment, locale: Locale = Locale.getDefault()): String? =
        payment.fiat?.let { "${LocalePresentation.decimal(it.decimal, locale)} ${it.currencyCode}" }

    fun timestamp(payment: Payment, zoneId: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale)
            .format(Instant.ofEpochMilli(payment.timestampMillis).atZone(zoneId))

    fun technicalFields(payment: Payment): List<TechnicalField> = listOfNotNull(
        payment.txid?.let { TechnicalField("Transaction ID", it, copyable = true, sensitive = false) },
        payment.address?.let { TechnicalField("Address", it, copyable = true, sensitive = false) },
        payment.preimage?.let { TechnicalField("Preimage", it, copyable = false, sensitive = true) },
        payment.ecash?.let { TechnicalField("Ecash", it, copyable = false, sensitive = true) },
    )
}
