package cash.pyx.app

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import cash.pyx.app.security.SensitiveClipboardExpiry
import cash.pyx.app.ui.QrPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

class PlatformClipboardIntegrationTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test fun sensitiveCopySetsPlatformFlagAndReportsOwnedExpiry() {
        val activity = composeRule.activity
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.clearPrimaryClip()

        val feedback = QrPayload.copy(activity, "Pyx test secret", SECRET, sensitive = true)
        val clip = requireNotNull(clipboard.primaryClip)
        assertEquals(SECRET, clip.getItemAt(0).text.toString())
        assertTrue(requireNotNull(clip.description.extras).getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
        assertNotNull(clip.description.extras?.getString("cash.pyx.app.clipboard.ownership_token"))
        assertEquals(SensitiveClipboardExpiry.DEFAULT_TIMEOUT_MILLIS, feedback.expiresAfterMillis)
        assertTrue(feedback.announcement.contains("one minute"))
        clipboard.clearPrimaryClip()
    }

    @Test fun nonSensitiveCopyDoesNotClaimSensitiveClipboardSemantics() {
        val activity = composeRule.activity
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val feedback = QrPayload.copy(activity, "Bitcoin address", "bc1qexample", sensitive = false)
        val clip = requireNotNull(clipboard.primaryClip)
        assertFalse(clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) ?: false)
        assertEquals(null, feedback.expiresAfterMillis)
        clipboard.clearPrimaryClip()
    }

    @Test fun sharesheetPayloadRequiresExplicitNonSensitiveClassification() {
        val intent = QrPayload.shareIntent("bc1qexample", sensitive = false)
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals("bc1qexample", intent.getStringExtra(Intent.EXTRA_TEXT))
        try {
            QrPayload.shareIntent(SECRET, sensitive = true)
            fail("Sensitive payload reached Sharesheet intent creation")
        } catch (_: IllegalArgumentException) {
            // Expected fail-closed policy.
        }
    }

    private companion object { const val SECRET = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu" }
}
