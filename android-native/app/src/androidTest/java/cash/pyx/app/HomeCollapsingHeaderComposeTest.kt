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
import cash.pyx.app.data.BootstrapState
import cash.pyx.app.nativeapi.FederationSummary
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

    @Test fun primaryWalletActionsRemainReachableAfterCollapseAtTwoHundredPercentText() {
        setHome(width = 280, height = 600, fontScale = 2f)
        composeRule.onNodeWithTag("wallet_home").performScrollToIndex(6)
        composeRule.onNodeWithTag("balance_header_collapsed").assertIsDisplayed()
        listOf("Receive", "Send", "Scan").forEach { label ->
            composeRule.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        }
    }

    private fun setHome(width: Int = 390, height: Int = 844, fontScale: Float = 1f) {
        composeRule.activity.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                Box(Modifier.width(width.dp).height(height.dp)) { PyxTheme { PyxApp(state = HOME) } }
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
    }
}
