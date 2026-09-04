package cash.pyx.app.security

import android.content.Context

interface DeepLinkConsumptionStore {
    fun read(): ConsumedDeepLink?
    fun write(value: ConsumedDeepLink): Boolean
}

data class ConsumedDeepLink(val fingerprint: String, val consumedAtEpochMillis: Long)

/**
 * Deliberate DataStore exception: intent consumption is a synchronous boundary.
 * [write] must be durably visible before routing returns a payload to UI; making
 * it asynchronous would reopen a process-death replay window. One atomic
 * SharedPreferences commit stores both non-secret fields and reports fsync
 * failure to the consume-once gate.
 */
class SharedPreferencesDeepLinkConsumptionStore(context: Context) : DeepLinkConsumptionStore {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun read(): ConsumedDeepLink? {
        val fingerprint = preferences.getString(KEY_FINGERPRINT, null) ?: return null
        val consumedAt = preferences.getLong(KEY_CONSUMED_AT, -1L)
        if (fingerprint.length != 64 || consumedAt < 0) return null
        return ConsumedDeepLink(fingerprint, consumedAt)
    }

    override fun write(value: ConsumedDeepLink): Boolean = preferences.edit()
        .putString(KEY_FINGERPRINT, value.fingerprint)
        .putLong(KEY_CONSUMED_AT, value.consumedAtEpochMillis)
        .commit()

    private companion object {
        const val PREFERENCES = "deep_link_consumption_v1"
        const val KEY_FINGERPRINT = "fingerprint_sha256"
        const val KEY_CONSUMED_AT = "consumed_at_epoch_millis"
    }
}
