package cash.pyx.app

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.dp
import cash.pyx.app.data.BootstrapState
import cash.pyx.app.nativeapi.BootstrapSession
import cash.pyx.app.nativeapi.WalletSnapshot
import cash.pyx.app.nativeapi.SeedPhraseValidation
import cash.pyx.app.nativeapi.ClassifiedInput
import cash.pyx.app.nativeapi.InputType
import cash.pyx.app.nativeapi.SelectedWallet
import cash.pyx.app.nativeapi.ConnectionState
import cash.pyx.app.nativeapi.GuardianConnectionSnapshot
import cash.pyx.app.nativeapi.GuardianStatus
import cash.pyx.app.ui.PyxApp
import cash.pyx.app.ui.WalletOperation
import cash.pyx.app.ui.theme.PyxTheme
import org.junit.Rule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class MainActivityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @After
    fun clearRootCrashMarker() {
        (composeRule.activity.application as PyxApplication).clearRootCrashMarker()
    }

    @Test
    fun nativePreviewLaunches() {
        composeRule.activity.setContent {
            PyxTheme {
                PyxApp(
                    state = BootstrapState.Onboarding(
                        BootstrapSession.Uninitialized("instrumentation", 1L),
                    ),
                )
            }
        }
        composeRule.onNodeWithTag("pyx_app").assertIsDisplayed()
        composeRule.onNodeWithTag("bootstrap_content").assertIsDisplayed()
        composeRule.onNodeWithText("Welcome to Pyx").assertIsDisplayed()
    }

    @Test
    fun errorActionsRetainAccessibleTouchTarget() {
        composeRule.activity.setContent {
            PyxTheme {
                PyxApp(state = BootstrapState.Unavailable("Test failure"))
            }
        }
        composeRule.onNodeWithText("Try again").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun typedDestinationAndSystemBackReturnToWalletRoot() {
        composeRule.activity.setContent {
            PyxTheme {
                PyxApp(state = BootstrapState.Home(7L, WalletSnapshot("USD", emptyList(), null)))
            }
        }

        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithText("About").assertIsDisplayed()
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag("wallet_home").assertIsDisplayed()
    }

    @Test
    fun settingsReachTypedAccessAndBackupDestinations() {
        composeRule.activity.setContent {
            PyxTheme { PyxApp(state = BootstrapState.Home(7L, WalletSnapshot("USD", emptyList(), null))) }
        }

        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithText("Configure access protection").performClick()
        composeRule.onNodeWithText("Access").assertIsDisplayed()
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithText("About").assertIsDisplayed()
        composeRule.onNodeWithText("Recovery words").performClick()
        composeRule.onNodeWithText("Show recovery words").assertIsDisplayed()
    }

    @Test
    fun currencyIsADistinctDestinationAndBackReturnsToSettings() {
        composeRule.activity.setContent {
            PyxTheme { PyxApp(state = BootstrapState.Home(7L, WalletSnapshot("USD", emptyList(), null))) }
        }

        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithText("Currency").performClick()
        composeRule.onNodeWithText("Search currencies").assertIsDisplayed()
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithText("About").assertIsDisplayed()
    }

    @Test
    fun guardiansAreADistinctDestinationAndBackReturnsToDetails() {
        val selected = SelectedWallet(9L, "fed", "Federation", 1_000L, emptyList())
        val connection = GuardianConnectionSnapshot(
            guardians = listOf(GuardianStatus("Guardian One", true), GuardianStatus("Guardian Two", false)),
            onlineCount = 1,
            totalCount = 2,
            requiredCount = 2,
            state = ConnectionState.DEGRADED,
        )
        composeRule.activity.setContent {
            PyxTheme {
                PyxApp(
                    state = BootstrapState.Home(7L, WalletSnapshot("USD", emptyList(), selected)),
                    connection = connection,
                )
            }
        }

        composeRule.onNodeWithText("Details").performClick()
        composeRule.onNodeWithText("Guardians").performClick()
        composeRule.onNodeWithText("Guardian One").assertIsDisplayed()
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithText("Load federation details").assertIsDisplayed()
    }

    @Test
    fun classifiedDeepLinkWaitsForUnlockedHomeAndIsConsumedExactlyOnce() {
        val state = mutableStateOf<BootstrapState>(BootstrapState.Loading)
        val request = mutableStateOf<ClassifiedInput?>(
            ClassifiedInput(InputType.BITCOIN, "bitcoin:deterministic-review-only"),
        )
        var consumed = 0
        val operations = mutableListOf<String>()
        composeRule.activity.setContent {
            PyxTheme {
                PyxApp(
                    state = state.value,
                    classifiedInput = request.value,
                    onConsumeClassified = { consumed += 1; request.value = null },
                    onOperation = { action, _, _, _ -> operations += action },
                )
            }
        }

        composeRule.onNodeWithText("Starting wallet…").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, consumed) }
        composeRule.runOnIdle {
            state.value = BootstrapState.Home(
                7L,
                WalletSnapshot("USD", emptyList(), SelectedWallet(9L, "fed", "Federation", 1_000L, emptyList())),
            )
        }

        composeRule.onNodeWithText("Send").assertIsDisplayed()
        composeRule.onNodeWithText("bitcoin:deterministic-review-only").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(1, consumed)
            assertEquals(listOf("parse_bitcoin"), operations)
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(1, consumed) }
    }

    @Test
    fun backgroundingNestedDestinationClearsBackStackToWalletRoot() {
        composeRule.activity.setContent {
            PyxTheme { PyxApp(state = BootstrapState.Home(7L, WalletSnapshot("USD", emptyList(), null))) }
        }
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithText("Configure access protection").performClick()
        composeRule.onNodeWithText("Access").assertIsDisplayed()

        composeRule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        composeRule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)

        composeRule.onNodeWithTag("wallet_home").assertIsDisplayed()
    }

    @Test
    fun priorProcessFailureShowsOnlyRedactedRecoverySurface() {
        val application = composeRule.activity.application as PyxApplication
        application.createRootCrashMarkerForTest()
        composeRule.activityRule.scenario.recreate()

        composeRule.onNodeWithText("Pyx needs to restart").assertIsDisplayed()
        composeRule.onNodeWithText("The previous app process stopped unexpectedly. Your wallet data was not cleared.").assertIsDisplayed()
        composeRule.onNodeWithText("Restart wallet").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun restoreValidationShowsInvalidWordAndOrderWithoutEnablingSubmission() {
        val validation = mutableStateOf<SeedPhraseValidation?>(null)
        composeRule.activity.setContent {
            PyxTheme {
                PyxApp(
                    state = BootstrapState.Onboarding(BootstrapSession.Uninitialized("instrumentation", 1L)),
                    seedValidation = validation.value,
                )
            }
        }
        composeRule.onNodeWithText("Restore wallet").performClick()

        composeRule.runOnIdle { validation.value = SeedPhraseValidation(false, 12, listOf(2), false) }
        composeRule.onNodeWithText("Not a valid BIP39 word").assertIsDisplayed()
        composeRule.onNodeWithText("Restore wallet").assertIsNotEnabled()

        composeRule.runOnIdle { validation.value = SeedPhraseValidation(false, 12, emptyList(), false) }
        composeRule.onNodeWithText("Recovery phrase checksum is invalid").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Restore wallet").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun offlineJoinKeepsInviteVisibleForRetry() {
        val operation = mutableStateOf<WalletOperation>(WalletOperation.Idle)
        composeRule.activity.setContent {
            PyxTheme {
                PyxApp(
                    state = BootstrapState.Home(7L, WalletSnapshot("USD", emptyList(), null)),
                    operation = operation.value,
                    onJoin = { _, _, _ -> operation.value = WalletOperation.Failure("Federation is offline.") },
                )
            }
        }
        composeRule.onNodeWithText("Join federation").performClick()
        composeRule.onNodeWithTag("federation_invite").performTextInput("fedimint:retry-me")
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.onNodeWithText("Confirm").performClick()

        composeRule.onNodeWithText("Federation is offline.").assertIsDisplayed()
        composeRule.onNodeWithTag("federation_invite").assertTextContains("fedimint:retry-me")
    }
}
