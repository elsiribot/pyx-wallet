package cash.pyx.app.security

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import cash.pyx.app.data.appPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Persisted intent is deliberately independent of the device's current capability. */
class BiometricPolicy private constructor(private val context: Context) {
    val enabled: Flow<Boolean> = context.appPreferences.data.map { it[ENABLED] ?: false }

    suspend fun setEnabled(value: Boolean) { context.appPreferences.edit { it[ENABLED] = value } }
    suspend fun currentEnabled(): Boolean = enabled.first()

    enum class Dispatch { PROCEED, AUTHENTICATE, BLOCK_UNAVAILABLE }

    fun dispatch(configuredEnabled: Boolean, available: Boolean): Dispatch =
        resolveBiometricDispatch(configuredEnabled, available)

    companion object {
        private val ENABLED = booleanPreferencesKey("biometric_protection_enabled")
        fun from(context: Context): BiometricPolicy = BiometricPolicy(context.applicationContext)
        internal fun dispatchForTest(configuredEnabled: Boolean, available: Boolean): Dispatch =
            resolveBiometricDispatch(configuredEnabled, available)
    }
}

private fun resolveBiometricDispatch(configuredEnabled: Boolean, available: Boolean): BiometricPolicy.Dispatch = when {
    !configuredEnabled -> BiometricPolicy.Dispatch.PROCEED
    available -> BiometricPolicy.Dispatch.AUTHENTICATE
    else -> BiometricPolicy.Dispatch.BLOCK_UNAVAILABLE
}
