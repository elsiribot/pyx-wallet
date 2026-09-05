package cash.pyx.app

import android.Manifest
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import cash.pyx.app.data.BootstrapState
import cash.pyx.app.data.appPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import cash.pyx.app.nativeapi.BootstrapSession
import cash.pyx.app.nativeapi.SelectedWallet
import cash.pyx.app.nativeapi.WalletSnapshot
import cash.pyx.app.ui.PyxApp
import cash.pyx.app.ui.QrScanner
import cash.pyx.app.ui.WalletOperation
import cash.pyx.app.ui.theme.PyxTheme
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.FileInputStream

/** Device-side contracts that exercise real Compose measurement and Android window/permission state. */
class AdaptiveSecurityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()
    private val originalAnimationScales = linkedMapOf<String, String?>()

    @Before
    fun requireIsolatedPreviewPackage() {
        assertEquals("cash.pyx.app.nativepreview", composeRule.activity.packageName)
    }

    @After
    fun clearSecureWindow() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            composeRule.activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        restoreAnimationScales()
    }

    @Test
    fun onboardingFitsReferenceViewportAtDefaultTextScale() {
        setAdaptiveOnboarding(width = 390, height = 844, fontScale = 1f)

        composeRule.onNodeWithTag("pyx_app")
            .assertWidthIsEqualTo(390.dp)
            .assertHeightIsEqualTo(844.dp)
        assertOnboardingActionsRemainOperable()
    }

    @Test
    fun onboardingRemainsOperableAt390DpAndTwoHundredPercentText() {
        setTestContent {
            val density = LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                Box(Modifier.width(390.dp)) {
                    PyxTheme {
                        PyxApp(
                            state = BootstrapState.Onboarding(
                                BootstrapSession.Uninitialized("instrumentation", 1L),
                            ),
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("pyx_app").assertWidthIsEqualTo(390.dp)
        composeRule.onNode(isHeading() and androidx.compose.ui.test.hasText("Welcome to Pyx"))
            .assertIsDisplayed()
        composeRule.onNodeWithText("Create wallet").performScrollTo().assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("Restore wallet").performScrollTo().assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun onboardingRemainsOperableAtOneHundredThirtyPercentText() {
        setTestContent {
            val density = LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 1.3f),
            ) {
                PyxTheme {
                    PyxApp(
                        state = BootstrapState.Onboarding(
                            BootstrapSession.Uninitialized("instrumentation", 1L),
                        ),
                    )
                }
            }
        }
        composeRule.onNodeWithText("Create wallet").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Restore wallet").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun onboardingRemainsOperableOnGenuinelyNarrowViewportAtTwoHundredPercentText() {
        setAdaptiveOnboarding(width = 280, height = 600, fontScale = 2f)

        composeRule.onNodeWithTag("pyx_app")
            .assertWidthIsEqualTo(280.dp)
            .assertHeightIsEqualTo(600.dp)
        assertOnboardingActionsRemainOperable()
    }

    @Test
    fun onboardingRemainsOperableWithIncreasedDisplayDensity() {
        setAdaptiveOnboarding(width = 280, height = 480, fontScale = 1.3f, densityScale = 1.35f)

        composeRule.onNodeWithTag("pyx_app")
            .assertWidthIsEqualTo(280.dp)
            .assertHeightIsEqualTo(480.dp)
        assertOnboardingActionsRemainOperable()
    }

    @Test
    fun everyTransferTabKeepsItsPrimaryActionReachableAtLargeTextOnNarrowDisplay() {
        setAdaptiveHome(width = 280, height = 600, fontScale = 2f)
        waitForHome()
        // Receive has no per-tab submit buttons any more: the code is generated live,
        // so the reachable primary affordances are the amount unit chip (Lightning /
        // On-Chain) and the claim action (Ecash).
        listOf("Receive" to listOf("Lightning" to "SATS", "On-Chain" to "SATS", "Ecash" to "Claim ecash"),
            "Send" to listOf("Lightning" to "Review and send", "On-chain" to "Review and send", "Ecash" to "Review and send"),
        ).forEach { (destination, tabs) ->
            tabs.forEach { (tab, action) ->
                // Receive/Send are inside the balance card at list index 1.
                composeRule.onNodeWithTag("wallet_home").performScrollToIndex(0)
                composeRule.onNodeWithText(destination).performClick()
                waitForText("Back")
                if (tab != "Lightning") composeRule.onNodeWithText(tab).performClick()
                waitForText(action)
                composeRule.onNodeWithText(action).performScrollTo().assertIsDisplayed()
                composeRule.onNodeWithText("Back").performScrollTo().assertIsDisplayed().performClick()
                waitForHome()
            }
        }
    }

    @Test
    fun navigationRemainsOperableWithSystemAnimationsDisabled() {
        disableSystemAnimations()
        try {
            val home = BootstrapState.Home(2L, WalletSnapshot("USD", emptyList(), null))
            setTestContent { PyxTheme { PyxApp(state = home) } }
            composeRule.onNodeWithText("Settings").performScrollTo().performClick()
            composeRule.onNodeWithText("About").assertIsDisplayed()
            composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            composeRule.onNodeWithTag("wallet_home").assertIsDisplayed()
        } finally {
            restoreAnimationScales()
        }
    }

    @Test
    fun seedWordsSetSecureFlagAndDisposalClearsIt() {
        val words = listOf(
            "abandon", "ability", "able", "about", "above", "absent",
            "absorb", "abstract", "absurd", "abuse", "access", "accident",
        )
        setTestContent {
            PyxTheme { PyxApp(state = BootstrapState.SeedConfirmation(7L, words)) }
        }
        composeRule.waitForIdle()
        assertTrue(composeRule.activity.secureFlagIsSet())
        composeRule.onNode(isHeading() and androidx.compose.ui.test.hasText("Save your recovery words"))
            .assertIsDisplayed()

        setTestContent {
            PyxTheme { PyxApp(state = BootstrapState.Unavailable("done")) }
        }
        composeRule.waitForIdle()
        assertFalse(composeRule.activity.secureFlagIsSet())
    }

    @Test
    fun ecashQrSetsSecureFlagAndLeavingSurfaceClearsIt() {
        val home = BootstrapState.Home(
            factoryHandle = 2L,
            snapshot = WalletSnapshot(
                currencyCode = "USD",
                federations = emptyList(),
                selected = SelectedWallet(3L, "fed", "Test federation", 1_000L, emptyList()),
            ),
        )
        setTestContent {
            PyxTheme {
                PyxApp(
                    state = home,
                    operation = WalletOperation.Success("Ecash token", "token-for-device-test"),
                )
            }
        }
        composeRule.onNodeWithText("Receive").performScrollTo().performClick()
        composeRule.onNodeWithText("Ecash").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Ecash token · 21 characters").assertIsDisplayed()
        assertTrue(composeRule.activity.secureFlagIsSet())

        composeRule.onNodeWithText("Back").performClick()
        composeRule.waitForIdle()
        assertFalse(composeRule.activity.secureFlagIsSet())
    }

    @Test
    fun cameraPermanentDenialUsesSettingsRecoveryWithoutOpeningCamera() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packageName = composeRule.activity.packageName
        runCatching {
            instrumentation.uiAutomation.revokeRuntimePermission(packageName, Manifest.permission.CAMERA)
        }
        kotlinx.coroutines.runBlocking {
            composeRule.activity.applicationContext.appPreferences.edit {
                it[booleanPreferencesKey("asked")] = true
            }
        }
        assertEquals(
            android.content.pm.PackageManager.PERMISSION_DENIED,
            composeRule.activity.checkSelfPermission(Manifest.permission.CAMERA),
        )

        setTestContent {
            PyxTheme { QrScanner(onResult = {}, onBack = {}) }
        }
        composeRule.onNodeWithText("Camera permission is disabled in Android settings.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Open app settings").assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
    }

    private fun setTestContent(content: @Composable () -> Unit) {
        composeRule.activity.runOnUiThread {
            composeRule.activity.setContent(content = content)
        }
        composeRule.waitForIdle()
    }

    private fun setAdaptiveOnboarding(width: Int, height: Int, fontScale: Float, densityScale: Float = 1f) {
        setTestContent {
            val density = LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                LocalDensity provides Density(density.density * densityScale, fontScale),
            ) {
                Box(Modifier.width(width.dp).height(height.dp)) {
                    PyxTheme {
                        PyxApp(state = BootstrapState.Onboarding(BootstrapSession.Uninitialized("instrumentation", 1L)))
                    }
                }
            }
        }
    }

    private fun setAdaptiveHome(width: Int, height: Int, fontScale: Float) {
        val home = BootstrapState.Home(
            2L,
            WalletSnapshot("USD", emptyList(), SelectedWallet(3L, "fed", "Test federation", 1_000L, emptyList())),
        )
        setTestContent {
            val density = LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                Box(Modifier.width(width.dp).height(height.dp)) { PyxTheme { PyxApp(state = home) } }
            }
        }
    }

    private fun waitForHome() {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("wallet_home").fetchSemanticsNodes().size == 1
        }
    }

    private fun waitForText(text: String) {
        try {
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (failure: Throwable) {
            throw AssertionError("Timed out waiting for '$text':\n${composeRule.onRoot(useUnmergedTree = true).printToString(8)}", failure)
        }
    }

    private fun assertOnboardingActionsRemainOperable() {
        composeRule.onNode(isHeading() and androidx.compose.ui.test.hasText("Welcome to Pyx")).assertIsDisplayed()
        composeRule.onNodeWithText("Create wallet").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("Restore wallet").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }

    private fun disableSystemAnimations() {
        ANIMATION_SCALE_KEYS.forEach { key ->
            originalAnimationScales.putIfAbsent(key, shell("settings get global $key").takeUnless { it == "null" || it.isBlank() })
            shell("settings put global $key 0")
            assertEquals(0f, shell("settings get global $key").toFloat())
        }
    }

    private fun restoreAnimationScales() {
        originalAnimationScales.forEach { (key, value) ->
            if (value == null) shell("settings delete global $key") else shell("settings put global $key $value")
        }
        originalAnimationScales.clear()
    }

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText().trim() }
            .also { descriptor.close() }
    }

    private fun MainActivity.secureFlagIsSet(): Boolean {
        var secure = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            secure = window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        }
        return secure
    }

    private companion object {
        val ANIMATION_SCALE_KEYS = listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
    }
}
