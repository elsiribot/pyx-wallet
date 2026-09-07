package cash.pyx.app.ui

import cash.pyx.app.nativeapi.LnaddrQuote

/** Pure state machine driving the claim-username sheet, independent of the JNI facade. */
sealed interface ClaimCheck {
    data object Idle : ClaimCheck
    data object Checking : ClaimCheck
    data object Available : ClaimCheck
    data class Paid(val priceMsat: Long) : ClaimCheck
    data object Taken : ClaimCheck
    data object Reserved : ClaimCheck
    data class Error(val hint: String) : ClaimCheck
}

/** Which lightning-address surface the receive screen shows beside the reusable code. */
enum class ReceiveLnaddrSurface { NONE, CLAIM_BANNER, ADDRESS_ROW }

object ReceiveLnaddrPresentation {
    /** Title `WalletOperation.Success` carries for the reusable, amountless LNURL. */
    const val LNURL_RECEIVE = "LNURL receive"

    /** The receive screen only offers a lightning address in the state the address itself
     * stands in for: the Lightning tab showing the amountless reusable LNURL. While an amount
     * is being typed, or once a BOLT11 invoice / on-chain address is on screen, the surface is
     * [ReceiveLnaddrSurface.NONE] — an address can't carry an amount the sender must pay. */
    fun surface(
        tab: Int,
        amount: String,
        typing: Boolean,
        operationTitle: String?,
        hasPrimary: Boolean,
    ): ReceiveLnaddrSurface = when {
        tab != 0 || amount.isNotBlank() || typing || operationTitle != LNURL_RECEIVE -> ReceiveLnaddrSurface.NONE
        hasPrimary -> ReceiveLnaddrSurface.ADDRESS_ROW
        else -> ReceiveLnaddrSurface.CLAIM_BANNER
    }
}

object LnaddrClaimPresentation {
    private const val MAX_USERNAME_LENGTH = 64
    private val ALLOWED_CHARS = ('a'..'z') + ('0'..'9') + listOf('-', '_', '.')

    /** Folds to lowercase, drops anything outside `[a-z0-9-_.]`, and caps length so the
     * server never rejects on formatting alone. */
    fun sanitizeUsername(raw: String): String =
        raw.lowercase().filter { it in ALLOWED_CHARS }.take(MAX_USERNAME_LENGTH)

    fun checkFromQuote(q: LnaddrQuote): ClaimCheck = when (q) {
        is LnaddrQuote.Free -> ClaimCheck.Available
        is LnaddrQuote.Paid -> ClaimCheck.Paid(q.priceMsat)
        is LnaddrQuote.Taken -> ClaimCheck.Taken
        is LnaddrQuote.Reserved -> ClaimCheck.Reserved
        is LnaddrQuote.Invalid -> ClaimCheck.Error(q.reason)
        is LnaddrQuote.RateLimited -> ClaimCheck.Error("Too many attempts. Try again shortly.")
    }

    /** A paid quote is informational only here — claiming a priced username isn't wired up yet. */
    fun canClaim(check: ClaimCheck, username: String): Boolean =
        check is ClaimCheck.Available && username.isNotBlank()

    /** Lightning-address auth is NIP-98 event based; a clock far enough out of sync makes
     * every request look unauthorized, so surface that possibility explicitly. */
    fun clockHint(message: String): String =
        if (message.contains("unauthorized", ignoreCase = true)) {
            "$message. Check your device clock is correct."
        } else {
            message
        }
}
