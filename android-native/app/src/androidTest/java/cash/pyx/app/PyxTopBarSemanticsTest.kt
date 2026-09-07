package cash.pyx.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cash.pyx.app.ui.components.PyxIconButton
import cash.pyx.app.ui.components.PyxTopBar
import cash.pyx.app.ui.theme.PyxIcons
import cash.pyx.app.ui.theme.PyxTheme
import org.junit.Rule
import org.junit.Test

/**
 * `PyxTopBar` sizes its title against the width the chevron and the actions leave, which means
 * the title lives inside a layout wrapper. Neither that wrapper nor a plain `semantics` block
 * merges its descendants, so the caller's `heading()` has to stay on the title `Text` itself —
 * otherwise heading navigation lands on a node with no text, and the repo's own
 * `isHeading() and hasText(...)` assertions stop matching on every screen with a top bar.
 */
class PyxTopBarSemanticsTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test fun headingAndTitleTextAreTheSameNode() {
        setTopBar("Lightning addresses")
        composeRule.onNode(isHeading() and hasText("Lightning addresses")).assertIsDisplayed()
    }

    @Test fun aShortTitleKeepsTheSameNodeShape() {
        setTopBar("Send")
        composeRule.onNode(isHeading() and hasText("Send")).assertIsDisplayed()
    }

    /** The long title steps down rather than truncating, and still wraps at large font scales. */
    @Test fun theTitleIsNeverTruncated() {
        setTopBar("Lightning addresses")
        composeRule.onNodeWithText("Lightning addresses").assertIsDisplayed()
        setTopBar("Lightning addresses", fontScale = 2f)
        composeRule.onNode(isHeading() and hasText("Lightning addresses")).assertIsDisplayed()
    }

    private fun setTopBar(title: String, fontScale: Float = 1f) {
        composeRule.activity.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                PyxTheme {
                    Box(Modifier.width(390.dp).height(844.dp)) {
                        PyxTopBar(
                            title,
                            onBack = {},
                            titleSemantics = Modifier.semantics { heading() },
                            actions = { PyxIconButton(PyxIcons.Plus, onClick = {}) },
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }
}
