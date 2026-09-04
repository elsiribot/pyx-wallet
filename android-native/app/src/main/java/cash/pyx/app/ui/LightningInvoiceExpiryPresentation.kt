package cash.pyx.app.ui

import kotlin.math.max

internal object LightningInvoiceExpiryPresentation {
    fun remainingSeconds(expiresAtEpochSeconds: Long, nowEpochMillis: Long): Long =
        max(0, expiresAtEpochSeconds - nowEpochMillis.floorDiv(1_000))

    fun text(expiresAtEpochSeconds: Long, nowEpochMillis: Long): String {
        val remaining = remainingSeconds(expiresAtEpochSeconds, nowEpochMillis)
        if (remaining == 0L) return "Expired"
        val hours = remaining / 3_600
        val minutes = (remaining % 3_600) / 60
        val seconds = remaining % 60
        return when {
            hours > 0 -> "Expires in ${hours}h ${minutes}m"
            minutes > 0 -> "Expires in ${minutes}m ${seconds}s"
            else -> "Expires in ${seconds}s"
        }
    }
}
