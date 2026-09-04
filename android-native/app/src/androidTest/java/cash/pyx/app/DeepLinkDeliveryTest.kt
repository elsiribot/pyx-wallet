package cash.pyx.app

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cash.pyx.app.security.SharedPreferencesDeepLinkConsumptionStore
import cash.pyx.app.security.PendingIrreversibleOperation
import cash.pyx.app.security.SharedPreferencesIrreversibleOperationJournal
import cash.pyx.app.ui.IntentConsumptionGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeepLinkDeliveryTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    @After
    fun clearNonSecretTestState() {
        context.getSharedPreferences("deep_link_consumption_v1", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("irreversible_operation_journal_v1", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun coldDeepLinkIsConsumedWithoutSubmittingAndSurvivesRecreation() {
        val uri = Uri.parse("bitcoin:fixture-address")
        withMainActivity(viewIntent(uri)) { scenario ->
            awaitConsumedLink()
            scenario.onActivity { activity ->
                assertEquals(uri, activity.intent.data)
                assertNull(SharedPreferencesIrreversibleOperationJournal(activity).read())
            }

            scenario.recreate()

            scenario.onActivity { recreated ->
                assertEquals(uri, recreated.intent.data)
                assertNotNull(SharedPreferencesDeepLinkConsumptionStore(recreated).read())
                assertNull(SharedPreferencesIrreversibleOperationJournal(recreated).read())
            }
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    @Test
    fun warmDuplicateUsesSingleTopActivityAndDoesNotConsumeTwice() {
        withMainActivity(launcherIntent()) { scenario ->
            var original: MainActivity? = null
            scenario.onActivity { original = it }

            val uri = Uri.parse("lightning:deterministic-non-invoice")
            scenario.onActivity { it.acceptNewIntent(viewIntent(uri)) }
            val first = awaitConsumedLink()
            var afterFirstDelivery: MainActivity? = null
            scenario.onActivity {
                afterFirstDelivery = it
                assertEquals(uri, it.intent.data)
            }

            scenario.onActivity { it.acceptNewIntent(viewIntent(uri)) }
            val afterDuplicate = SharedPreferencesDeepLinkConsumptionStore(context).read()

            assertSame(original, afterFirstDelivery)
            assertEquals(first, afterDuplicate)
            assertNull(SharedPreferencesIrreversibleOperationJournal(context).read())
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    @Test
    fun manifestDeclaresSingleTopBackStackSemantics() {
        val info = context.packageManager.getActivityInfo(
            android.content.ComponentName(context, MainActivity::class.java),
            0,
        )

        assertEquals(ActivityInfo.LAUNCH_SINGLE_TOP, info.launchMode)
    }

    @Test
    fun persistedSafetyLockSurvivesDeepLinkDeliveryAndFreshConsumptionGraph() {
        val correlation = "0123456789abcdef0123456789abcdef"
        val pending = PendingIrreversibleOperation(
            PendingIrreversibleOperation.Kind.ONCHAIN_SEND,
            1_700_000_000_000L,
            correlation,
            "deterministic-federation",
        )
        val operationJournal = SharedPreferencesIrreversibleOperationJournal(context)
        assertTrue(operationJournal.begin(pending))
        val uri = Uri.parse("bitcoin:fixture-address")

        withMainActivity(viewIntent(uri)) { scenario ->
            awaitConsumedLink()
            scenario.onActivity { it.acceptNewIntent(viewIntent(uri)) }
            scenario.recreate()

            assertEquals(pending, operationJournal.read())
            // A newly constructed gate represents the persisted part of a cold-start object
            // graph: the launch intent is still locked as consumed and cannot be replayed.
            assertNull(
                IntentConsumptionGate(SharedPreferencesDeepLinkConsumptionStore(context))
                    .consume(uri.toString()),
            )
            assertEquals(pending, operationJournal.read())
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    private fun launcherIntent() = Intent(context, MainActivity::class.java)
        .setAction(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)

    private fun viewIntent(uri: Uri) = Intent(Intent.ACTION_VIEW, uri, context, MainActivity::class.java)

    private fun withMainActivity(
        intent: Intent,
        assertions: (ActivityScenario<MainActivity>) -> Unit,
    ) {
        // ActivityScenario adds NEW_TASK | CLEAR_TASK, so every following launch replaces this
        // task and the instrumentation runner force-stops the target after the class. Explicit
        // close()/finish() is intentionally avoided: API 34 keeps the instrumentation's root
        // Activity RESUMED until the next CLEAR_TASK launch and ActivityScenario then times out
        // despite the target process being cleaned up by the runner.
        assertions(ActivityScenario.launch(intent))
    }

    private fun awaitConsumedLink(): cash.pyx.app.security.ConsumedDeepLink {
        val deadline = SystemClock.uptimeMillis() + 2_000
        while (SystemClock.uptimeMillis() < deadline) {
            SharedPreferencesDeepLinkConsumptionStore(context).read()?.let { return it }
            SystemClock.sleep(10)
        }
        throw AssertionError("MainActivity did not persist deep-link consumption within 2 seconds")
    }
}
