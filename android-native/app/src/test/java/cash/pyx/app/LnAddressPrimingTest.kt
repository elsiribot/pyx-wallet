package cash.pyx.app

import cash.pyx.app.data.*
import cash.pyx.app.nativeapi.*
import cash.pyx.app.ui.WalletBootstrapViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

/**
 * Regression coverage for a bug where the Settings row showed "Claim" on cold start even when a
 * primary address already existed: nothing primed [WalletBootstrapViewModel.lnAddressStateOwner]
 * outside the Lightning-addresses screen itself. `publishState` must prime it off the Home
 * transition, the same way it primes `activityStateOwner`/`onchainAddressStateOwner`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LnAddressPrimingTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun cleanup() = Dispatchers.resetMain()

    @Test fun bootstrappingToHomePrimesLnAddressStateWithoutVisitingTheScreen() = runTest(dispatcher) {
        val api = LnaddrApi()
        val viewModel = WalletBootstrapViewModel(WalletBootstrapRepository(api, "/files"), dispatcher)
        advanceUntilIdle()

        assertTrue(viewModel.state.value is BootstrapState.Home)
        assertEquals(1, api.lnaddrSnapshotCalls)
        assertEquals("eric@pyx.cash", viewModel.lnAddressStateOwner.state.value.addresses.single().display)
    }

    @Test fun livePollingDoesNotRepeatedlyRepriming() = runTest(dispatcher) {
        val api = LnaddrApi()
        val viewModel = WalletBootstrapViewModel(WalletBootstrapRepository(api, "/files"), dispatcher)
        advanceUntilIdle()
        assertEquals(1, api.lnaddrSnapshotCalls)

        // Home's live refresh polls walletSnapshotAsync every 10s; priming is keyed on the
        // (unchanging) factory handle, so it must not fire again on every tick.
        viewModel.setResumed(true)
        advanceTimeBy(31_000)
        assertTrue(api.snapshotCalls > 1)
        assertEquals(1, api.lnaddrSnapshotCalls)
        // Live refresh's `while (true) { delay(...) }` loop must not still be scheduling work
        // when the test ends, or runTest's drain never completes.
        viewModel.setResumed(false)
    }

    private class LnaddrApi : NativeWalletApi {
        var snapshotCalls = 0
        var lnaddrSnapshotCalls = 0
        override suspend fun bootstrapAsync(filesDir: String) =
            NativeResult.Success<BootstrapSession>(BootstrapSession.Ready("1", 7, 9))
        override suspend fun walletSnapshotAsync(factoryHandle: Long): NativeResult<WalletSnapshot> {
            snapshotCalls++
            return NativeResult.Success(WalletSnapshot("USD", emptyList(), null))
        }
        override suspend fun lnaddrSnapshotAsync(factoryHandle: Long): NativeResult<LnAddressSnapshot> {
            lnaddrSnapshotCalls++
            return NativeResult.Success(
                LnAddressSnapshot(listOf(LnAddress("pyx.cash", "eric", "https://pyx.cash", "fed1", "lnurl1dest", true, 0L))),
            )
        }
        override fun listFiatCurrencies() = error("unused")
        override fun classifyInput(payload: String) = error("unused")
        override fun closeTransientHandle(handle: Long, kind: String) = NativeResult.Success(TransientClosed(true))
    }
}
