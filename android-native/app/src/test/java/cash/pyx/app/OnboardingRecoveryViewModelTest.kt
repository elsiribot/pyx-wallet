package cash.pyx.app

import cash.pyx.app.data.BootstrapState
import cash.pyx.app.data.WalletBootstrapRepository
import cash.pyx.app.nativeapi.*
import cash.pyx.app.security.SeedBackupState
import cash.pyx.app.ui.WalletBootstrapViewModel
import cash.pyx.app.ui.WalletOperation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Deterministic onboarding/recovery coverage; no federation or network is required. */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingRecoveryViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `incomplete phrase is rejected without entering native restore`() = runTest(dispatcher) {
        val api = OnboardingApi()
        val viewModel = viewModel(api)
        advanceUntilIdle()

        viewModel.restoreWallet(7, List(11) { "word$it" }.joinToString(" "))

        assertEquals(0, api.restoreCalls)
        assertEquals("Enter all 12 recovery words.", (viewModel.state.value as BootstrapState.Error).message)
    }

    @Test fun `invalid word and invalid order failures remain explicit and retryable`() = runTest(dispatcher) {
        val api = OnboardingApi()
        val viewModel = viewModel(api)
        advanceUntilIdle()

        api.restoreResult = NativeResult.Failure(AndroidError("invalid_seed", "Recovery phrase contains an invalid word.", false))
        viewModel.restoreWallet(7, phrase("invalid"))
        advanceUntilIdle()
        assertEquals("Recovery phrase contains an invalid word.", (viewModel.state.value as BootstrapState.Error).message)

        viewModel.refresh(); advanceUntilIdle()
        api.restoreResult = NativeResult.Failure(AndroidError("invalid_seed", "Recovery phrase checksum is invalid.", false))
        viewModel.restoreWallet(7, phrase("ordered"))
        advanceUntilIdle()
        assertEquals(2, api.restoreCalls)
        assertEquals("Recovery phrase checksum is invalid.", (viewModel.state.value as BootstrapState.Error).message)
    }

    @Test fun `blank invite is rejected locally and offline invite can be retried unchanged`() = runTest(dispatcher) {
        val api = OnboardingApi(initialized = true)
        val viewModel = viewModel(api)
        advanceUntilIdle()

        viewModel.join(9, "   ", false)
        assertEquals(0, api.joinCalls)
        assertEquals("Enter a valid federation invite.", (viewModel.operation.value as WalletOperation.Failure).message)

        viewModel.join(9, "x".repeat(16 * 1024 + 1), false)
        assertEquals(0, api.joinCalls)
        assertEquals("Enter a valid federation invite.", (viewModel.operation.value as WalletOperation.Failure).message)

        viewModel.clearOperation()
        api.joinResult = NativeResult.Failure(AndroidError("offline", "Federation is offline.", true))
        viewModel.join(9, "  fedimint:retry-me  ", true)
        advanceUntilIdle()
        assertEquals("fedimint:retry-me", api.lastInvite)
        assertTrue(api.lastRecover)
        assertEquals("Federation is offline.", (viewModel.operation.value as WalletOperation.Failure).message)

        viewModel.clearOperation()
        viewModel.join(9, "fedimint:retry-me", true)
        advanceUntilIdle()
        assertEquals(2, api.joinCalls)
    }

    @Test fun `refresh suppresses a late non cooperative restore secret result`() = runTest(dispatcher) {
        val api = OnboardingApi()
        api.delayRestore = true
        val viewModel = viewModel(api)
        advanceUntilIdle()

        viewModel.restoreWallet(7, phrase("word"))
        runCurrent()
        assertTrue(api.restoreStarted)
        viewModel.refresh()
        runCurrent()
        api.restoreGate.complete(Unit)
        advanceUntilIdle()

        assertTrue(api.restoreObservedCancellation)
        assertTrue(viewModel.state.value is BootstrapState.Onboarding)
    }

    private fun phrase(prefix: String) = List(12) { "$prefix$it" }.joinToString(" ")

    private fun viewModel(api: OnboardingApi) = WalletBootstrapViewModel(
        WalletBootstrapRepository(api, "/files"), dispatcher,
        object : SeedBackupState {
            override suspend fun isPending() = false
            override suspend fun setPending(value: Boolean) = Unit
        },
    )

    private class OnboardingApi(var initialized: Boolean = false) : NativeWalletApi {
        var restoreCalls = 0
        var joinCalls = 0
        var lastInvite: String? = null
        var lastRecover = false
        var delayRestore = false
        var restoreStarted = false
        var restoreObservedCancellation = false
        val restoreGate = CompletableDeferred<Unit>()
        var restoreResult: NativeResult<RestoredWallet> = NativeResult.Success(RestoredWallet(9))
        var joinResult: NativeResult<WalletSnapshot> = NativeResult.Failure(AndroidError("offline", "Federation is offline.", true))
        private val emptySnapshot = WalletSnapshot("EUR", emptyList(), null)

        override suspend fun bootstrapAsync(filesDir: String) = NativeResult.Success<BootstrapSession>(
            if (initialized) BootstrapSession.Ready("test", 7, 9) else BootstrapSession.Uninitialized("test", 7),
        )
        override suspend fun restoreWalletAsync(databaseHandle: Long, words: List<String>): NativeResult<RestoredWallet> {
            restoreCalls++
            if (delayRestore) {
                restoreStarted = true
                try { restoreGate.await() } catch (_: CancellationException) {
                    restoreObservedCancellation = true
                    withContext(NonCancellable) { restoreGate.await() }
                }
            }
            return restoreResult
        }
        override suspend fun joinFederationAsync(factoryHandle: Long, invite: String, recover: Boolean): NativeResult<WalletSnapshot> {
            joinCalls++; lastInvite = invite; lastRecover = recover
            return joinResult
        }
        override suspend fun walletSnapshotAsync(factoryHandle: Long) = NativeResult.Success(emptySnapshot)
        override fun listFiatCurrencies() = NativeResult.Success(FiatCurrencies(emptyList()))
        override fun classifyInput(payload: String) = NativeResult.Success(InputType.UNKNOWN)
        override fun closeTransientHandle(handle: Long, kind: String) = NativeResult.Success(TransientClosed(true))
    }
}
