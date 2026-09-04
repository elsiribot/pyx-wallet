package cash.pyx.app.security

/** Identifies an app-owned clipboard write without retaining the secret in the expiry callback. */
data class SensitiveClipboardSnapshot(
    val ownershipToken: String,
    val valueSha256: String,
)

fun interface ClipboardExpiryScheduler {
    fun schedule(delayMillis: Long, action: () -> Unit)
}

fun interface OwnedClipboard {
    /** Clears only when the clipboard still contains [expected]. */
    fun clearIfOwned(expected: SensitiveClipboardSnapshot)
}

/**
 * Keeps clipboard expiry policy independent of Android so its ownership rule can be unit tested.
 * Each write has a unique token: an old timer therefore cannot erase a newer app or user write.
 */
class SensitiveClipboardExpiry(
    private val clipboard: OwnedClipboard,
    private val scheduler: ClipboardExpiryScheduler,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    init {
        require(timeoutMillis > 0)
    }

    fun schedule(snapshot: SensitiveClipboardSnapshot) {
        scheduler.schedule(timeoutMillis) { clipboard.clearIfOwned(snapshot) }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 60_000L
    }
}
