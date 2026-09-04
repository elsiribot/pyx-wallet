package cash.pyx.app.ui

import cash.pyx.app.nativeapi.ConnectionState
import cash.pyx.app.nativeapi.GuardianConnectionSnapshot
import java.util.Locale

object HomePresentation {
    fun balanceText(balanceSat: Long, masked: Boolean, locale: Locale = Locale.getDefault()): String =
        if (masked) "•••• sats" else "${LocalePresentation.integer(balanceSat, locale)} sats"

    fun fiatText(value: String?, currencyCode: String, masked: Boolean, locale: Locale = Locale.getDefault()): String? = when {
        value == null -> null
        masked -> "•••• $currencyCode"
        else -> "${LocalePresentation.decimal(value, locale)} $currencyCode"
    }

    fun connectionTitle(status: GuardianConnectionSnapshot?): String = when (status?.state) {
        ConnectionState.CONNECTED -> "Connected"
        ConnectionState.DEGRADED -> "Connection degraded"
        ConnectionState.OFFLINE -> "Offline"
        null -> "Connection status unavailable"
    }

    fun connectionDetail(status: GuardianConnectionSnapshot?): String = status?.let {
        "${it.onlineCount} of ${it.totalCount} guardians online"
    } ?: "Cached wallet data remains available"
}
