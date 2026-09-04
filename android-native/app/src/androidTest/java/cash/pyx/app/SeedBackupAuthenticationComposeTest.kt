package cash.pyx.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cash.pyx.app.data.BootstrapState
import cash.pyx.app.nativeapi.FederationSummary
import cash.pyx.app.nativeapi.SelectedWallet
import cash.pyx.app.nativeapi.WalletSnapshot
import cash.pyx.app.security.ProtectedActionAuthenticator
import cash.pyx.app.ui.ProtectedActionHost
import cash.pyx.app.ui.PyxApp
import cash.pyx.app.ui.theme.PyxTheme
import cash.pyx.app.ui.WalletOperation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import android.view.WindowManager
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context

class SeedBackupAuthenticationComposeTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test fun successfulAuthenticationIsRequiredBeforeRecoveryWordsAppear() {
        val fixture = mount()
        openBackupAndRequest()
        composeRule.onNodeWithText(SECRET).assertDoesNotExist()
        composeRule.runOnIdle { fixture.authenticator.succeed() }
        composeRule.onNodeWithText(SECRET).assertIsDisplayed()
        assertEquals(1, fixture.backupCalls)
    }

    @Test fun cancelledAuthenticationNeverRequestsOrDisplaysRecoveryWords() {
        val fixture = mount()
        openBackupAndRequest()
        composeRule.runOnIdle { fixture.authenticator.fail("Authentication was not completed.") }
        composeRule.onNodeWithText(SECRET).assertDoesNotExist()
        composeRule.onNodeWithText("Authentication was not completed.").assertIsDisplayed()
        assertEquals(0, fixture.backupCalls)
    }

    @Test fun authenticationFailureNeverRequestsOrDisplaysRecoveryWords() {
        val fixture = mount()
        openBackupAndRequest()
        composeRule.runOnIdle { fixture.authenticator.fail("Authentication failed. Try again.") }
        composeRule.onNodeWithText(SECRET).assertDoesNotExist()
        composeRule.onNodeWithText("Authentication failed. Try again.").assertIsDisplayed()
        assertEquals(0, fixture.backupCalls)
    }

    @Test fun backgroundingInvalidatesPendingAuthenticationCallback() {
        val fixture = mount()
        openBackupAndRequest()
        composeRule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        composeRule.runOnIdle { fixture.authenticator.succeed() }
        composeRule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        composeRule.onNodeWithText(SECRET).assertDoesNotExist()
        assertEquals(0, fixture.backupCalls)
    }

    @Test fun onStopClearsRevealedWordsAndSecureWindowCapability() {
        val fixture = mount()
        openBackupAndRequest()
        composeRule.runOnIdle { fixture.authenticator.succeed() }
        composeRule.onNodeWithText(SECRET).assertIsDisplayed()
        composeRule.runOnIdle { assertSecureWindow(true) }

        composeRule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        composeRule.runOnIdle { assertSecureWindow(false) }
        composeRule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        composeRule.onNodeWithText(SECRET).assertDoesNotExist()
    }

    @Test fun activityRecreationNeverRestoresRevealedWordsFromSavedState() {
        val fixture = mount()
        openBackupAndRequest()
        composeRule.runOnIdle { fixture.authenticator.succeed() }
        composeRule.onNodeWithText(SECRET).assertIsDisplayed()

        composeRule.activityRule.scenario.recreate()

        composeRule.onNodeWithText(SECRET).assertDoesNotExist()
        composeRule.runOnIdle { assertSecureWindow(false) }
    }

    @Test fun recoveryWordsRequireWarningBeforeSensitiveCopyAndAnnounceExpiry() {
        val clipboard = composeRule.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.clearPrimaryClip()
        val fixture = mount()
        openBackupAndRequest()
        composeRule.runOnIdle { fixture.authenticator.succeed() }

        composeRule.onNodeWithText("Copy recovery words").performClick()
        composeRule.onNodeWithText("Copy recovery words?").assertIsDisplayed()
        composeRule.runOnIdle {
            assertTrue(clipboard.primaryClip?.getItemAt(0)?.text?.toString() != SECRET)
        }
        composeRule.onNodeWithText("Copy for one minute").performClick()
        composeRule.onNodeWithText("Sensitive content copied. It will be cleared from the clipboard in one minute.").assertIsDisplayed()
        composeRule.runOnIdle {
            val clip = requireNotNull(clipboard.primaryClip)
            assertEquals(SECRET, clip.getItemAt(0).text.toString())
            assertTrue(requireNotNull(clip.description.extras).getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
            clipboard.clearPrimaryClip()
        }
    }

    private fun mount(): Fixture {
        val authenticator = FakeAuthenticator()
        var operation by mutableStateOf<WalletOperation>(WalletOperation.Idle)
        var backupCalls = 0
        composeRule.activity.setContent {
            PyxTheme {
                ProtectedActionHost(authenticator, { operation = WalletOperation.Failure(it) }) { configured, available, _, guarded ->
                    PyxApp(
                        state = HOME,
                        operation = operation,
                        onClearOperation = { operation = WalletOperation.Idle },
                        biometricAvailable = available,
                        biometricEnabled = configured == true,
                        onBackup = {
                            guarded("Unlock recovery words") {
                                backupCalls++
                                operation = WalletOperation.Success("Recovery words", SECRET, sensitive = true, shareable = false)
                            }
                        },
                    )
                }
            }
        }
        return Fixture(authenticator) { backupCalls }
    }

    private fun openBackupAndRequest() {
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithText("Recovery words").performClick()
        composeRule.onNodeWithText("Show recovery words").performClick()
    }

    private fun assertSecureWindow(expected: Boolean) {
        val secure = composeRule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        assertEquals(expected, secure)
    }

    private class FakeAuthenticator : ProtectedActionAuthenticator {
        override val available = true
        private val enabled = MutableStateFlow(true)
        override val configured: Flow<Boolean> = enabled
        private var success: (() -> Unit)? = null
        private var failure: ((String) -> Unit)? = null
        override suspend fun setConfigured(enabled: Boolean) { this.enabled.value = enabled }
        override fun authenticate(title: String, onSuccess: () -> Unit, onFailure: (String) -> Unit) {
            success = onSuccess
            failure = onFailure
        }
        fun succeed() { success?.invoke() }
        fun fail(message: String) { failure?.invoke(message) }
    }

    private class Fixture(val authenticator: FakeAuthenticator, private val calls: () -> Int) {
        val backupCalls get() = calls()
    }

    private companion object {
        const val SECRET = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu"
        val HOME = BootstrapState.Home(
            7,
            WalletSnapshot(
                "EUR",
                listOf(FederationSummary("fed", "Test federation", 1)),
                SelectedWallet(9, "fed", "Test federation", 100, emptyList()),
            ),
        )
    }
}
