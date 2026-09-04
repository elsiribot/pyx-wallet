package cash.pyx.app.ui

import java.net.URI

data class AboutLink(val label: String, val url: String)

object SettingsPresentation {
    private const val SOURCE = "https://github.com/joschisan/conduit"
    val links: List<AboutLink> = listOf(
        AboutLink("Source code", SOURCE),
        AboutLink("Open-source license", "$SOURCE/blob/main/LICENSE"),
    ).filter { isValidHttps(it.url) }

    fun isValidHttps(value: String): Boolean = runCatching { URI(value) }.getOrNull()?.let {
        it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null && it.fragment == null
    } == true

    fun biometricMessage(available: Boolean, enabled: Boolean): String = when {
        enabled && !available -> "Protection remains enabled, but strong biometrics are currently unavailable. Protected actions are blocked until a supported biometric is enrolled, or protection is turned off."
        !available -> "Strong biometric protection is unavailable. Enroll a supported biometric to enable it."
        enabled -> "Strong biometric protection is available and required for payments and recovery words."
        else -> "Require a strong biometric for payments, ecash creation, federation removal, and recovery words."
    }
}
