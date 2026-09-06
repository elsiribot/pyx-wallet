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
