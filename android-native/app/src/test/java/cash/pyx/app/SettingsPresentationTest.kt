package cash.pyx.app

import cash.pyx.app.ui.SettingsPresentation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPresentationTest {
    @Test fun `about links are valid non-credential HTTPS metadata`() {
        assertTrue(SettingsPresentation.links.isNotEmpty())
        assertTrue(SettingsPresentation.links.all { SettingsPresentation.isValidHttps(it.url) })
        assertFalse(SettingsPresentation.isValidHttps("http://example.com"))
        assertFalse(SettingsPresentation.isValidHttps("https://user:secret@example.com"))
        assertFalse(SettingsPresentation.isValidHttps("javascript:alert(1)"))
    }

    @Test fun `biometric messaging distinguishes enrollment capability and enabled state`() {
        assertTrue(SettingsPresentation.biometricMessage(false, false).contains("unavailable"))
        assertTrue(SettingsPresentation.biometricMessage(false, false).contains("Enroll"))
        assertTrue(SettingsPresentation.biometricMessage(false, true).contains("remains enabled"))
        assertTrue(SettingsPresentation.biometricMessage(false, true).contains("blocked"))
        assertTrue(SettingsPresentation.biometricMessage(true, false).contains("Require"))
        assertTrue(SettingsPresentation.biometricMessage(true, true).contains("required"))
    }
}
