package cash.pyx.app.data

import cash.pyx.app.nativeapi.BootstrapSession
import cash.pyx.app.nativeapi.RecoveryEvent
import cash.pyx.app.nativeapi.SelectedWallet
import cash.pyx.app.nativeapi.WalletSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WalletApplicationStateOwnerTest {
    @Test fun mapsEveryApplicationStateAndRetainsSelectedWalletDuringRecovery() = runTest {
        val owner = WalletApplicationStateOwner()
        assertSame(WalletApplicationState.Uninitialized, owner.state.value)

        owner.accept(BootstrapState.Loading)
        assertSame(WalletApplicationState.Loading, owner.state.value)

        owner.accept(BootstrapState.Onboarding(BootstrapSession.Uninitialized("test", 1)))
        assertSame(WalletApplicationState.Uninitialized, owner.state.value)

        val home = BootstrapState.Home(7, snapshot("fed-a"))
        owner.accept(home)
        val ready = owner.state.value as WalletApplicationState.Ready
        assertEquals("fed-a", ready.selectedFederationId)

        val progress = RecoveryEvent(1, 1, 2, false, 1, 2, false)
        owner.acceptRecovery(progress)
        val recovering = owner.state.value as WalletApplicationState.Recovering
        assertSame(ready, recovering.lastReady)
        assertSame(progress, recovering.progress)

        owner.acceptRecovery(progress.copy(finished = true, allFinished = true))
        assertSame(ready, owner.state.value)

        owner.accept(BootstrapState.Error("offline", retryable = true))
        assertEquals("offline", (owner.state.value as WalletApplicationState.Recovering).message)

        owner.accept(BootstrapState.Error("corrupt", retryable = false))
        assertEquals(WalletApplicationState.Fatal("corrupt"), owner.state.value)
    }

    @Test fun newerLoadWinsWhenAnOlderRepositoryCompletesLate() = runTest {
        val owner = WalletApplicationStateOwner()
        val slow = TimedFakeBootstrapRepository(1_000, BootstrapState.Home(1, snapshot("stale")), ignoreCancellation = true)
        val fast = TimedFakeBootstrapRepository(10, BootstrapState.Home(2, snapshot("current")))

        owner.load(this, slow)
        advanceTimeBy(1)
        owner.load(this, fast)
        advanceTimeBy(11)
        assertEquals("current", (owner.state.value as WalletApplicationState.Ready).selectedFederationId)
        advanceUntilIdle()
        assertEquals("current", (owner.state.value as WalletApplicationState.Ready).selectedFederationId)
        assertEquals(1, slow.calls)
        assertEquals(1, fast.calls)
    }

    @Test fun fakeRepositoryCanPauseAtAnExactVirtualTimeBoundary() = runTest {
        val release = CompletableDeferred<Unit>()
        val owner = WalletApplicationStateOwner()
        val fake = ApplicationBootstrapRepository {
            delay(500)
            release.await()
            BootstrapState.Home(3, snapshot("fed-b"))
        }

        owner.load(this, fake)
        advanceTimeBy(500)
        assertSame(WalletApplicationState.Loading, owner.state.value)
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals("fed-b", (owner.state.value as WalletApplicationState.Ready).selectedFederationId)
    }

    private class TimedFakeBootstrapRepository(
        private val delayMillis: Long,
        private val result: BootstrapState,
        private val ignoreCancellation: Boolean = false,
    ) : ApplicationBootstrapRepository {
        var calls = 0
        override suspend fun load(): BootstrapState {
            calls++
            if (ignoreCancellation) withContext(NonCancellable) { delay(delayMillis) } else delay(delayMillis)
            return result
        }
    }

    private fun snapshot(federationId: String) = WalletSnapshot(
        currencyCode = "EUR",
        federations = emptyList(),
        selected = SelectedWallet(11, federationId, "Test federation", 42, emptyList()),
    )
}
