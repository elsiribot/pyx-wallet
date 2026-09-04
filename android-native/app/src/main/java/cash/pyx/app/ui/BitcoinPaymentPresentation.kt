package cash.pyx.app.ui

import cash.pyx.app.nativeapi.BitcoinPayment

data class BitcoinPaymentViewState(val address: String, val amount: String, val amountLocked: Boolean,
    val label: String?, val message: String?)

object BitcoinPaymentPresentation {
    fun state(payment: BitcoinPayment) = BitcoinPaymentViewState(payment.address, payment.amountSat?.toString().orEmpty(),
        payment.amountSat != null, payment.label, payment.message)
}
