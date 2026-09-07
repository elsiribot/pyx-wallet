package cash.pyx.app.ui

import cash.pyx.app.nativeapi.LnaddrQuote
import cash.pyx.app.nativeapi.LnaddrServer

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

/**
 * One selectable `username@domain` target in the claim sheet: the [domain] the address will
 * read as, and the server [origin] that will actually host it.
 *
 * The two are independent — an announcement declares its own `domains` list and nothing binds
 * those names to the announcing origin, so any server can claim to offer `pyx.cash`. When that
 * matters, [originLabel] is non-null and the UI must show it; otherwise the domain alone is an
 * unambiguous description of the option.
 */
data class LnaddrDomainOption(
    val origin: String,
    val domain: String,
    val originLabel: String?,
)

object LnaddrClaimPresentation {
    private const val MAX_USERNAME_LENGTH = 64
    private val ALLOWED_CHARS = ('a'..'z') + ('0'..'9') + listOf('-', '_', '.')

    /** Folds to lowercase, drops anything outside `[a-z0-9-_.]`, and caps length so the
     * server never rejects on formatting alone. */
    fun sanitizeUsername(raw: String): String =
        raw.lowercase().filter { it in ALLOWED_CHARS }.take(MAX_USERNAME_LENGTH)

    /** lnaddrd folds several distinct rejections into the single `invalid` quote state and
     * distinguishes them only by an error code. Each spec'd code gets its own sentence;
     * anything unrecognised (or absent, if a server answers with no code at all) falls back
     * to a generic line rather than rendering an empty status. */
    fun invalidReasonText(reason: String): String = when (reason) {
        "unsupported_domain" -> "That domain isn't available on this server."
        "length_disabled" -> "Names of this length aren't available on this domain."
        "invalid_input" -> "That name isn't valid. Use letters, digits, '-', '_' or '.'."
        else -> "That name can't be claimed here."
    }

    fun checkFromQuote(q: LnaddrQuote): ClaimCheck = when (q) {
        is LnaddrQuote.Free -> ClaimCheck.Available
        is LnaddrQuote.Paid -> ClaimCheck.Paid(q.priceMsat)
        is LnaddrQuote.Taken -> ClaimCheck.Taken
        is LnaddrQuote.Reserved -> ClaimCheck.Reserved
        is LnaddrQuote.Invalid -> ClaimCheck.Error(invalidReasonText(q.reason))
        is LnaddrQuote.RateLimited -> ClaimCheck.Error("Too many attempts. Try again shortly.")
    }

    /** Display form of a server origin: the bare host. Rust only ever hands up canonical
     * `https://host[:port]` origins (see `lnaddr::discovery::is_valid_origin`), so dropping
     * the scheme and any trailing slash is the whole job. */
    fun originHost(origin: String): String =
        origin.substringAfter("://", origin).trimEnd('/')

    /**
     * Flattens discovered servers into the sheet's selectable options, preserving server order
     * — Rust's `discover()` always emits the built-in default server first, so the first option
     * is the built-in one and is safe to pre-select.
     *
     * [LnaddrDomainOption.originLabel] is set whenever the domain alone would be misleading:
     * when the hosting origin isn't the domain itself, or when two different origins both
     * advertise the same domain (a hostile announcement can produce a second, visually
     * identical "pyx.cash" chip that is actually hosted elsewhere). It is left null for the
     * common unambiguous case — notably the built-in `pyx.cash` on `https://pyx.cash` — so the
     * default option stays clean.
     */
    fun domainOptions(servers: List<LnaddrServer>): List<LnaddrDomainOption> {
        val pairs = servers.flatMap { server -> server.domains.map { server.origin to it } }.distinct()
        val originsPerDomain = pairs.groupBy({ it.second }, { it.first }).mapValues { it.value.distinct().size }
        return pairs.map { (origin, domain) ->
            val host = originHost(origin)
            val contested = (originsPerDomain[domain] ?: 1) > 1
            LnaddrDomainOption(origin, domain, if (contested || host != domain) host else null)
        }
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
