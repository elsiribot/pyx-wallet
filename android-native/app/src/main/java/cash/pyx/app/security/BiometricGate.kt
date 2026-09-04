package cash.pyx.app.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.Flow

interface ProtectedActionAuthenticator {
    val available: Boolean
    val configured: Flow<Boolean>
    suspend fun setConfigured(enabled: Boolean)
    fun authenticate(title: String, onSuccess: () -> Unit, onFailure: (String) -> Unit)
}

class BiometricGate(private val activity: FragmentActivity) : ProtectedActionAuthenticator {
    val policy = BiometricPolicy.from(activity)
    val capability: Int get() = BiometricManager.from(activity).canAuthenticate(AUTHENTICATORS)
    override val available: Boolean get() = capability == BiometricManager.BIOMETRIC_SUCCESS
    override val configured: Flow<Boolean> get() = policy.enabled
    override suspend fun setConfigured(enabled: Boolean) = policy.setEnabled(enabled)

    override fun authenticate(title: String, onSuccess: () -> Unit, onFailure: (String) -> Unit) =
        authenticate(onSuccess, onFailure, title)

    fun authenticate(onSuccess: () -> Unit, onError: (String) -> Unit,
        title: String = "Authenticate", subtitle: String = "Confirm this wallet action") {
        if (capability != BiometricManager.BIOMETRIC_SUCCESS) {
            onError("Biometric authentication is not available or enrolled.")
            return
        }
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onError("Authentication was not completed.")
        }).authenticate(BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .setNegativeButtonText("Cancel")
            .build())
    }

    companion object { private const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG }
}
