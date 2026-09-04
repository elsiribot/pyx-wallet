package cash.pyx.app

import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cash.pyx.app.security.IrreversibleOperationReconciliation
import cash.pyx.app.security.PendingIrreversibleOperation
import cash.pyx.app.security.SharedPreferencesIrreversibleOperationJournal
import cash.pyx.app.ui.WalletBootstrapViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Configuration-recreation coverage that is safe against both an empty app and an installed
 * synthetic wallet fixture. OS process-death and live-stream assertions remain connected-device
 * release gates because ActivityScenario cannot kill and reattach the instrumentation process.
 */
@RunWith(AndroidJUnit4::class)
class ActivityLifecycleTest {
    private val context
        get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    @After
    fun clearOperationFixture() {
        context.getSharedPreferences("irreversible_operation_journal_v1", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun recreationRetainsActivityViewModelAndDoesNotInventIrreversibleWork() {
        val intent = Intent(context, MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            lateinit var before: WalletBootstrapViewModel
            scenario.onActivity {
                before = ViewModelProvider(it)[WalletBootstrapViewModel::class.java]
                assertNull(SharedPreferencesIrreversibleOperationJournal(it).read())
            }

            scenario.recreate()

            scenario.onActivity {
                val after = ViewModelProvider(it)[WalletBootstrapViewModel::class.java]
                assertSame(before, after)
                assertNull(SharedPreferencesIrreversibleOperationJournal(it).read())
            }
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    @Test
    fun durableOperationSurvivesActivityRecreationAndFreshProcessGraphBlocksDuplicate() {
        val intent = Intent(context, MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            val persisted = PendingIrreversibleOperation(
                kind = PendingIrreversibleOperation.Kind.LIGHTNING_SEND,
                startedAtEpochMillis = 1_700_000_000_000L,
                correlationId = "0123456789abcdef0123456789abcdef",
                federationId = "deterministic-federation",
            )
            val journal = SharedPreferencesIrreversibleOperationJournal(context)
            assertTrue("The pre-submit safety record must be committed synchronously", journal.begin(persisted))
            assertEquals(persisted, journal.read())

            scenario.recreate()

            scenario.onActivity {
                assertEquals(persisted, SharedPreferencesIrreversibleOperationJournal(it).read())
            }

            // ActivityScenario cannot kill the instrumentation process. Constructing a new
            // reconciliation graph against the same durable preferences exercises the state
            // restoration boundary used by a genuinely restarted Application/ViewModel graph.
            val restarted = IrreversibleOperationReconciliation(
                SharedPreferencesIrreversibleOperationJournal(context),
            )
            assertEquals(persisted, restarted.pending.value)
            assertFalse(
                "A restarted process must not submit while an unresolved operation exists",
                restarted.begin(
                    PendingIrreversibleOperation.Kind.LIGHTNING_SEND,
                    "fedcba9876543210fedcba9876543210",
                    "deterministic-federation",
                ),
            )
            assertEquals(persisted, SharedPreferencesIrreversibleOperationJournal(context).read())
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }
}
