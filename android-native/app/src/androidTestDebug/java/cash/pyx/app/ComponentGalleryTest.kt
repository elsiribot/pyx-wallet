package cash.pyx.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import cash.pyx.app.debug.ComponentGalleryActivity
import org.junit.Rule
import org.junit.Test

class ComponentGalleryTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentGalleryActivity>()

    @Test fun debugOnlyGalleryLaunchesWithSyntheticSafetySurfaces() {
        composeRule.onNodeWithTag("component_gallery").assertIsDisplayed()
        composeRule.onNodeWithText("Pyx component gallery").assertIsDisplayed()
        composeRule.onNodeWithText("Color roles").assertIsDisplayed()
        composeRule.onNodeWithTag("component_gallery")
            .performScrollToNode(hasText("NOT PAYABLE · fixed synthetic gallery data"))
        composeRule.onNodeWithText("NOT PAYABLE · fixed synthetic gallery data").assertIsDisplayed()
    }
}
