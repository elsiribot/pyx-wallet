package cash.pyx.app.ui

import cash.pyx.app.nativeapi.PaymentDirection
import cash.pyx.app.nativeapi.PaymentStatus
import cash.pyx.app.nativeapi.PaymentUpdate

object PaymentNotificationPresentation {
    fun notice(update: PaymentUpdate, previousIdentity: String?): PaymentNotice? {
        val notification = update.notification ?: return null
        val terminal = if (notification.success) PaymentStatus.SUCCEEDED else PaymentStatus.FAILED
        val payment = update.payments.firstOrNull { it.direction == notification.direction && it.type == notification.type && it.amountSat == notification.amountSat && it.status == terminal }
        val identity = payment?.let { "${it.operationId}:${it.status}" }
            ?: "${notification.direction}:${notification.type}:${notification.amountSat}:${notification.success}"
        if (identity == previousIdentity) return null
        val direction = if (notification.direction == PaymentDirection.INCOMING) "received" else "sent"
        return PaymentNotice(identity, "Payment ${if (notification.success) direction else "failed"} · ${notification.amountSat} sats")
    }
}
