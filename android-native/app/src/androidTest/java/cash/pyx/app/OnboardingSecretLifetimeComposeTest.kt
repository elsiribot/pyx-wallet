package cash.pyx.app

import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import cash.pyx.app.data.BootstrapState
import cash.pyx.app.nativeapi.BootstrapSession
import cash.pyx.app.nativeapi.ClassifiedInput
import cash.pyx.app.nativeapi.InputType
import cash.pyx.app.nativeapi.WalletSnapshot
import cash.pyx.app.ui.PyxApp
import cash.pyx.app.ui.theme.PyxTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class OnboardingSecretLifetimeComposeTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test fun restoreWordsAreClearedAcrossBackgroundAndNeverEnterSavedState() {
        composeRule.activity.setContent { PyxTheme { PyxApp(state = ONBOARDING) } }
        composeRule.onNodeWithText("Restore wallet").performClick()
        composeRule.onNodeWithTag("restore_word_1").performTextInput(SECRET_WORD)
        composeRule.onNodeWithTag("restore_word_1").assertTextContains(SECRET_WORD)
        composeRule.runOnIdle { assertTrue(secureFlag()) }

        composeRule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        composeRule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)

        composeRule.onNodeWithTag("restore_word_1").assertDoesNotExist()
        composeRule.runOnIdle { assertFalse(secureFlag()) }
    }

    @Test fun inviteClassifiedFromPasteOrScanRoutesToPrefilledReviewWithoutJoining() {
        var consumed = 0
        composeRule.activity.setContent {
            PyxTheme {
                PyxApp(
                    state = BootstrapState.Home(7, WalletSnapshot("EUR", emptyList(), null)),
                    classifiedInput = ClassifiedInput(InputType.INVITE, INVITE),
                    onConsumeClassified = { consumed++ },
                )
            }
        }

        composeRule.onNodeWithTag("federation_invite").assertTextContains(INVITE)
        composeRule.onNodeWithText("Continue").assertIsDisplayed()
        composeRule.runOnIdle { org.junit.Assert.assertEquals(1, consumed) }
    }

    private fun secureFlag() = composeRule.activity.window.attributes.flags and
        WindowManager.LayoutParams.FLAG_SECURE != 0

    private companion object {
        const val SECRET_WORD = "abandon"
        const val INVITE = "fedimint:classified-invite"
        val ONBOARDING = BootstrapState.Onboarding(BootstrapSession.Uninitialized("test", 7))
    }
}
