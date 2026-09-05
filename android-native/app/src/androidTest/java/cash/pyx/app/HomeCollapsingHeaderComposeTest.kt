package cash.pyx.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cash.pyx.app.data.ActivityState
import cash.pyx.app.data.BootstrapState
import cash.pyx.app.nativeapi.FederationSummary
import cash.pyx.app.nativeapi.Payment
import cash.pyx.app.nativeapi.PaymentDirection
import cash.pyx.app.nativeapi.PaymentStatus
import cash.pyx.app.nativeapi.PaymentType
import cash.pyx.app.nativeapi.SelectedWallet
import cash.pyx.app.nativeapi.WalletSnapshot
import cash.pyx.app.ui.PyxApp
import cash.pyx.app.ui.theme.PyxTheme
import org.junit.Rule
import org.junit.Test

class HomeCollapsingHeaderComposeTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test fun expandedAndCollapsedHeadersExposeExactlyOneSemanticState() {
        setHome(height = 500)
        composeRule.onNodeWithTag("balance_header_expanded")
            .assertIsDisplayed()
            .assert(state("Expanded balance header"))
        composeRule.onNodeWithTag("balance_header_collapsed").assertDoesNotExist()

        composeRule.onNodeWithTag("wallet_home").performScrollToIndex(6)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("balance_header_collapsed")
            .assertIsDisplayed()
            .assert(state("Collapsed balance header"))
        composeRule.onNodeWithTag("balance_header_expanded").assertDoesNotExist()
    }

    @Test fun maskingIsPreservedWhenHeaderCollapses() {
        setHome(height = 500)
        composeRule.onNodeWithContentDescription("Balance privacy").performClick()
        composeRule.onNodeWithTag("wallet_home").performScrollToIndex(6)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("balance_header_collapsed")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Balance hidden")))
        composeRule.onNodeWithText("42,000 sats").assertDoesNotExist()
    }

    @Test fun paymentAmountsFollowTheBalanceMask() {
        setHome(height = 800)
        composeRule.onNodeWithText("+1,200 sats").assertExists()
        composeRule.onNodeWithContentDescription("Balance privacy").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("+1,200 sats").assertDoesNotExist()
    }

    @Test fun primaryWalletActionsRemainReachableAfterCollapseAtTwoHundredPercentText() {
        setHome(width = 280, height = 600, fontScale = 2f)
        composeRule.onNodeWithTag("wallet_home").performScrollToIndex(6)
        composeRule.onNodeWithTag("balance_header_collapsed").assertIsDisplayed()
        // Receive/Send live in the balance card at the top of the list; reachable
        // means the user can scroll back to them after collapsing.
        composeRule.onNodeWithTag("wallet_home").performScrollToIndex(0)
        composeRule.waitForIdle()
        listOf("Receive", "Send").forEach { label ->
            composeRule.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        }
        // At the top of the list the scan action floats above it, reachable without scrolling.
        composeRule.onNodeWithText("Scan").assertIsDisplayed()
    }

    @Test fun scanFabSwapsForJumpToTopWhenScrolledPastTheHeader() {
        setHome(height = 500)
        composeRule.onNodeWithText("Scan").assertIsDisplayed()
        composeRule.onNodeWithTag("wallet_home").performScrollToIndex(6)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Scan").assertDoesNotExist()
        composeRule.onNodeWithText("Top").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Scan").assertIsDisplayed()
        composeRule.onNodeWithTag("balance_header_expanded").assertIsDisplayed()
    }

    private fun setHome(width: Int = 390, height: Int = 844, fontScale: Float = 1f) {
        composeRule.activity.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                Box(Modifier.width(width.dp).height(height.dp)) {
                    PyxTheme { PyxApp(state = HOME, activityState = ACTIVITY) }
                }
            }
        }
    }

    private fun state(value: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)

    private companion object {
        val HOME = BootstrapState.Home(
            7,
            WalletSnapshot(
                "EUR",
                listOf(FederationSummary("fed", "Test federation", 3)),
                SelectedWallet(9, "fed", "Test federation", 42_000, emptyList()),
            ),
        )
        val ACTIVITY = ActivityState(
            clientHandle = 9,
            payments = (0 until 8).map { index ->
                Payment(
                    operationId = "op$index",
                    direction = if (index % 2 == 0) PaymentDirection.INCOMING else PaymentDirection.OUTGOING,
                    type = PaymentType.LIGHTNING,
                    amountSat = 1_200L + index,
                    timestampMillis = 1_756_000_000_000L - index * 3_600_000L,
                    status = PaymentStatus.SUCCEEDED,
                )
            },
            nextCursor = null,
            initialized = true,
        )
    }
}
