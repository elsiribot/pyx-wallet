package cash.pyx.app.ui

import cash.pyx.app.data.BootstrapState
import cash.pyx.app.data.HomeStreamRepository
import cash.pyx.app.data.ReceiveRepository
import cash.pyx.app.data.RecoveryRepository
import cash.pyx.app.data.WalletBootstrapRepository
import cash.pyx.app.nativeapi.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReceiveRecoveryRepositoryViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `receive seam surfaces success and retryable or fatal failures`() = runTest(dispatcher) {
        val receive = FakeReceiveRepository()
        val viewModel = viewModel(receive = receive)
        advanceUntilIdle()

        viewModel.receiveLightning(9, 125)
        advanceUntilIdle()
        assertEquals("Lightning invoice", (viewModel.operation.value as WalletOperation.Success).title)
        assertEquals(listOf(9L to 125L), receive.lightningCalls)

        viewModel.clearOperation(); runCurrent()
        receive.lightningResult = NativeResult.Failure(AndroidError("offline", "Try again online.", true))
        viewModel.receiveLightning(9, 125); advanceUntilIdle()
        assertEquals("Try again online.", (viewModel.operation.value as WalletOperation.Failure).message)

        viewModel.clearOperation(); runCurrent()
        receive.lightningResult = NativeResult.Failure(AndroidError("unsupported", "Receive is unavailable.", false))
        viewModel.receiveLightning(9, 125); advanceUntilIdle()
        assertEquals("Receive is unavailable.", (viewModel.operation.value as WalletOperation.Failure).message)
    }

    @Test fun `cancelled receive cannot publish a late non cooperative secret`() = runTest(dispatcher) {
        val receive = FakeReceiveRepository().apply { suspendLightning = true }
        val viewModel = viewModel(receive = receive)
        advanceUntilIdle()

        viewModel.receiveLightning(9, 125); runCurrent()
        assertTrue(receive.lightningStarted)
        viewModel.clearOperation(); runCurrent()
        receive.lightningGate.complete(Unit)
        advanceUntilIdle()

        assertTrue(receive.lightningCancelled)
        assertTrue(viewModel.operation.value is WalletOperation.Idle)
    }

    @Test fun `recovery seam supports retry then success and rejects stale restore completion`() = runTest(dispatcher) {
        val recovery = FakeRecoveryRepository()
        val viewModel = viewModel(initialized = false, recovery = recovery)
        advanceUntilIdle()

        recovery.restoreResult = NativeResult.Failure(AndroidError("offline", "Recovery is offline.", true))
        viewModel.restoreWallet(7, phrase()); advanceUntilIdle()
        val retry = viewModel.state.value as BootstrapState.Error
        assertEquals("Recovery is offline.", retry.message)
        assertTrue(retry.retryable)

        viewModel.refresh(); advanceUntilIdle()
        recovery.restoreResult = NativeResult.Success(RestoredWallet(7))
        viewModel.restoreWallet(7, phrase()); advanceUntilIdle()
        assertTrue(viewModel.state.value is BootstrapState.Home)

        val late = FakeRecoveryRepository().apply { suspendRestore = true }
        val staleViewModel = viewModel(initialized = false, recovery = late)
        advanceUntilIdle()
        staleViewModel.restoreWallet(7, phrase()); runCurrent()
        staleViewModel.refresh(); runCurrent()
        late.restoreGate.complete(Unit); advanceUntilIdle()
        assertTrue(late.restoreCancelled)
        assertTrue(staleViewModel.state.value is BootstrapState.Onboarding)
    }

    @Test fun `recovery stream is cancelled and resubscribed after recreation`() = runTest(dispatcher) {
        val recovery = FakeRecoveryRepository()
        val first = viewModel(recovery = recovery)
        advanceUntilIdle()
        first.setResumed(true); runCurrent()
        recovery.updates.emit(event(1)); runCurrent()
        assertEquals(1L, first.recovery.value?.complete)

        first.setResumed(false); runCurrent()
        assertEquals(1, recovery.cancelledCollectors)

        val recreated = viewModel(recovery = recovery)
        advanceUntilIdle()
        recreated.setResumed(true); runCurrent()
        recovery.updates.emit(event(2)); runCurrent()
        assertEquals(2, recovery.collectors)
        assertEquals(1L, first.recovery.value?.complete)
        assertEquals(2L, recreated.recovery.value?.complete)
        recreated.setResumed(false); runCurrent()
    }

    @Test fun `federation recovery uses recovery seam and cancelled late completion is ignored`() = runTest(dispatcher) {
        val recovery = FakeRecoveryRepository().apply { suspendFederationRecovery = true }
        val viewModel = viewModel(recovery = recovery)
        advanceUntilIdle()

        viewModel.join(7, "  fedimint:recover  ", true); runCurrent()
        assertEquals(listOf(7L to "fedimint:recover"), recovery.federationCalls)
        viewModel.clearOperation(); runCurrent()
        recovery.federationGate.complete(Unit); advanceUntilIdle()

        assertTrue(recovery.federationCancelled)
        assertTrue(viewModel.operation.value is WalletOperation.Failure)
        assertEquals("Operation status is unknown. Refresh wallet state before trying again.",
            (viewModel.operation.value as WalletOperation.Failure).message)

        val fatal = FakeRecoveryRepository().apply {
            federationResult = NativeResult.Failure(AndroidError("invalid_invite", "Recovery invite is invalid.", false))
        }
        val retryViewModel = viewModel(recovery = fatal)
        advanceUntilIdle()
        retryViewModel.join(7, "fedimint:bad", true); advanceUntilIdle()
        assertEquals("Recovery invite is invalid.", (retryViewModel.operation.value as WalletOperation.Failure).message)
    }

    private fun viewModel(
        initialized: Boolean = true,
        receive: ReceiveRepository = FakeReceiveRepository(),
        recovery: RecoveryRepository = FakeRecoveryRepository(),
    ): WalletBootstrapViewModel {
        val snapshot = wallet()
        val repository = WalletBootstrapRepository(BootstrapApi(initialized, snapshot), "/tmp/seam-test")
        return WalletBootstrapViewModel(
            repository,
            workerDispatcher = dispatcher,
            homeRepository = FakeHomeRepository(snapshot),
            receiveRepository = receive,
            recoveryRepository = recovery,
        )
    }

    private class FakeReceiveRepository : ReceiveRepository {
        val lightningCalls = mutableListOf<Pair<Long, Long>>()
        var lightningResult: NativeResult<LightningReceive> = NativeResult.Success(
            LightningReceive("lnbc1opaque", 125, 1, "https://gateway", 1_800_000_000),
        )
        var suspendLightning = false
        var lightningStarted = false
        var lightningCancelled = false
        val lightningGate = CompletableDeferred<Unit>()

        override suspend fun lightning(client: Long, amountSat: Long): NativeResult<LightningReceive> {
            lightningCalls += client to amountSat
            if (suspendLightning) {
                lightningStarted = true
                try { lightningGate.await() } catch (_: CancellationException) {
                    lightningCancelled = true
                    withContext(NonCancellable) { lightningGate.await() }
                }
            }
            return lightningResult
        }
        override suspend fun onchain(client: Long) = NativeResult.Success(OnchainReceive("bc1opaque"))
        override suspend fun lnurl(client: Long) = NativeResult.Success(LnurlReceive("lnurl1opaque"))
    }

    private class FakeRecoveryRepository : RecoveryRepository {
        var restoreResult: NativeResult<RestoredWallet> = NativeResult.Success(RestoredWallet(7))
        var suspendRestore = false
        var restoreCancelled = false
        val restoreGate = CompletableDeferred<Unit>()
        val updates = MutableSharedFlow<RecoveryEvent>(extraBufferCapacity = 4)
        var collectors = 0
        var cancelledCollectors = 0
        val federationCalls = mutableListOf<Pair<Long, String>>()
        var federationResult: NativeResult<WalletSnapshot> = NativeResult.Success(
            WalletSnapshot("EUR", emptyList(), null),
        )
        var suspendFederationRecovery = false
        var federationCancelled = false
        val federationGate = CompletableDeferred<Unit>()

        override suspend fun restore(databaseHandle: Long, words: List<String>): NativeResult<RestoredWallet> {
            if (suspendRestore) try { restoreGate.await() } catch (_: CancellationException) {
                restoreCancelled = true
                withContext(NonCancellable) { restoreGate.await() }
            }
            return restoreResult
        }
        override suspend fun recoverFederation(factoryHandle: Long, invite: String): NativeResult<WalletSnapshot> {
            federationCalls += factoryHandle to invite
            if (suspendFederationRecovery) try { federationGate.await() } catch (_: CancellationException) {
                federationCancelled = true
                withContext(NonCancellable) { federationGate.await() }
            }
            return federationResult
        }
        override suspend fun expiry(client: Long) = NativeResult.Success(RecoveryExpirySnapshot(false, null, false, null))
        override fun events(client: Long): Flow<RecoveryEvent> = flow {
            collectors++
            try { updates.collect { emit(it) } } finally { cancelledCollectors++ }
        }
    }

    private class FakeHomeRepository(private val snapshot: WalletSnapshot) : HomeStreamRepository {
        override suspend fun snapshotAsync(factory: Long) = NativeResult.Success(snapshot)
        override fun balanceEvents(client: Long): Flow<Long> = emptyFlow()
        override fun paymentEvents(client: Long): Flow<PaymentUpdate> = emptyFlow()
        override fun satsToFiat(client: Long, amount: Long): NativeResult<FiatDisplay?> = NativeResult.Success(null)
    }

    private class BootstrapApi(private val initialized: Boolean, private val snapshot: WalletSnapshot) : NativeWalletApi {
        override suspend fun bootstrapAsync(filesDir: String) = NativeResult.Success<BootstrapSession>(
            if (initialized) BootstrapSession.Ready("test", 7, 9) else BootstrapSession.Uninitialized("test", 7),
        )
        override suspend fun walletSnapshotAsync(factoryHandle: Long) = NativeResult.Success(snapshot)
        override fun listFiatCurrencies() = NativeResult.Success(FiatCurrencies(emptyList()))
        override fun classifyInput(payload: String) = NativeResult.Success(InputType.UNKNOWN)
        override fun closeTransientHandle(handle: Long, kind: String) = NativeResult.Success(TransientClosed(true))
    }

    private fun phrase() = List(12) { "word$it" }.joinToString(" ")
    private fun wallet() = WalletSnapshot(
        "EUR", listOf(FederationSummary("fed", "Test", 1)),
        SelectedWallet(9, "fed", "Test", 100, emptyList()),
    )
    private fun event(complete: Long) = RecoveryEvent(1, complete, 2, false, complete, 2, false)
}
