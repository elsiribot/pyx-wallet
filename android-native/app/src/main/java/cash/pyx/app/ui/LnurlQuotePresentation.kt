package cash.pyx.app.ui

object LnurlQuotePresentation {
    fun resolveAmount(session: WalletOperation.LnurlPrepared, input: String): Long? =
        if (session.fixedAmount) session.minSat else input.toLongOrNull()
    fun canPrepare(session: WalletOperation.LnurlPrepared, amount: Long?): Boolean =
        amount != null && amount in session.minSat..session.maxSat
}
