package cash.pyx.app.ui

import cash.pyx.app.data.SendFlowRepository
import cash.pyx.app.data.WalletBootstrapRepository
import cash.pyx.app.nativeapi.*
import cash.pyx.app.security.IrreversibleOperationJournal
import cash.pyx.app.security.IrreversibleOperationReconciliation
import cash.pyx.app.security.PendingIrreversibleOperation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Deterministic safety matrix for the state machine between review and submission. */
@OptIn(ExperimentalCoroutinesApi::class)
class SendFlowViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val correlation = "00112233445566778899aabbccddeeff"

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `quote rejection matrix never reaches a review or submission`() = runTest(dispatcher) {
        val failures = listOf(
            "wrong_network" to "This invoice belongs to another network.",
            "expired_invoice" to "This invoice has expired.",
            "amount_required" to "This invoice does not specify an amount.",
            "insufficient_balance" to "The balance is insufficient after fees.",
            "gateway_offline" to "No Lightning gateway is currently available.",
        )

        failures.forEach { (code, message) ->
            val send = FakeSendRepository().apply {
                prepareLightningResult = NativeResult.Failure(AndroidError(code, message, code == "gateway_offline"))
            }
            val viewModel = viewModel(send)
            runCurrent()

            viewModel.prepareLightning(11, "lnbc1case")
            runCurrent()

            assertEquals(message, (viewModel.operation.value as WalletOperation.Failure).message)
            assertEquals("$code must not submit", 0, send.executeCalls)
        }
    }

    @Test fun `execution displays authoritative changed fee instead of stale review fee`() = runTest(dispatcher) {
        val send = FakeSendRepository().apply {
            prepareLightningResult = NativeResult.Success(LightningQuote(44, 100, 1, "https://gateway", false))
            executeResult = { id -> NativeResult.Success(LightningSend("operation-1", 3, id)) }
        }
        val viewModel = viewModel(send)
        runCurrent()

        viewModel.prepareLightning(11, "lnbc1invoice")
        runCurrent()
        assertEquals(1, (viewModel.operation.value as WalletOperation.LightningPrepared).quote.feeSat)
        viewModel.executeLightning(11, 44)
        runCurrent()

        val result = viewModel.operation.value as WalletOperation.Success
        assertTrue(result.detail.contains("fee 3 sats"))
        assertEquals(1, send.executeCalls)
        assertEquals(1, send.clearCalls)
    }

    @Test fun `leaving quote review cancels preparation and suppresses its late result`() = runTest(dispatcher) {
        val send = FakeSendRepository().apply { suspendPrepare = true }
        val viewModel = viewModel(send)
        runCurrent()

        viewModel.prepareLightning(11, "lnbc1invoice")
        runCurrent()
        assertTrue(send.prepareStarted)
        viewModel.clearOperation()
        runCurrent()

        assertTrue(send.prepareCancelled)
        assertTrue(viewModel.operation.value is WalletOperation.Idle)
        assertEquals(0, send.executeCalls)
    }

    @Test fun `double confirmation submits exactly once`() = runTest(dispatcher) {
        val completion = CompletableDeferred<NativeResult<LightningSend>>()
        val send = FakeSendRepository().apply { executeResult = { completion.await() } }
        val viewModel = viewModel(send)
        runCurrent()

        viewModel.executeLightning(11, 44)
        viewModel.executeLightning(11, 44)
        runCurrent()
        assertEquals(1, send.executeCalls)

        completion.complete(NativeResult.Success(LightningSend("operation-1", 2, correlation)))
        runCurrent()
        assertTrue(viewModel.operation.value is WalletOperation.Success)
        assertEquals(1, send.executeCalls)
    }

    @Test fun `ambiguous timeout retains safety lock and blocks retry through reconciliation`() = runTest(dispatcher) {
        val journal = MemoryJournal()
        val send = FakeSendRepository().apply {
            executeResult = {
                pending = listOf(PendingNativeOperation(it, DurableOperationKind.LIGHTNING, DurableOperationStatus.SUBMITTED))
                NativeResult.Failure(AndroidError("submission_unknown", "Submission timed out.", true))
            }
            reconciliationStatus = DurableOperationStatus.AMBIGUOUS
        }
        val viewModel = viewModel(send, journal)
        runCurrent()

        viewModel.executeLightning(11, 44)
        runCurrent()
        assertEquals(1, send.executeCalls)
        assertNotNull(journal.value)
        assertTrue((viewModel.operation.value as WalletOperation.Failure).message.contains("ambiguous"))

        viewModel.executeLightning(11, 44)
        viewModel.refreshOperationReconciliation()
        runCurrent()
        assertEquals(1, send.executeCalls)
        assertNotNull(journal.value)
        assertEquals(PendingIrreversibleOperation.ReconciliationStatus.AMBIGUOUS, journal.value?.reconciliationStatus)
    }

    private fun viewModel(send: FakeSendRepository, journal: MemoryJournal = MemoryJournal()) =
        WalletBootstrapViewModel(
            repository = WalletBootstrapRepository(BootstrapApi(), "/files"),
            workerDispatcher = dispatcher,
            sendRepository = send,
            operationReconciliation = IrreversibleOperationReconciliation(journal) { 20L },
            correlationIdGenerator = { correlation },
        )

    private class BootstrapApi : NativeWalletApi {
        private val selected = SelectedWallet(11, "fed-a", "Wallet", 1_000, emptyList())
        override suspend fun bootstrapAsync(filesDir: String) =
            NativeResult.Success<BootstrapSession>(BootstrapSession.Ready("1", 7, 9))
        override suspend fun walletSnapshotAsync(factoryHandle: Long) =
            NativeResult.Success(WalletSnapshot("EUR", emptyList(), selected))
        override fun listFiatCurrencies() = NativeResult.Success(FiatCurrencies(emptyList()))
        override fun classifyInput(payload: String) = NativeResult.Success(InputType.UNKNOWN)
        override fun closeTransientHandle(handle: Long, kind: String) = NativeResult.Success(TransientClosed(true))
    }

    private class FakeSendRepository : SendFlowRepository {
        var prepareLightningResult: NativeResult<LightningQuote> =
            NativeResult.Success(LightningQuote(44, 100, 1, "https://gateway", false))
        var executeResult: suspend (String) -> NativeResult<LightningSend> = { id ->
            NativeResult.Success(LightningSend("operation-1", 1, id))
        }
        var suspendPrepare = false
        var prepareStarted = false
        var prepareCancelled = false
        var executeCalls = 0
        var clearCalls = 0
        var pending = emptyList<PendingNativeOperation>()
        var reconciliationStatus = DurableOperationStatus.NOT_SUBMITTED

        override suspend fun prepareLightningAsync(client: Long, invoice: String): NativeResult<LightningQuote> {
            prepareStarted = true
            if (suspendPrepare) try {
                awaitCancellation()
            } catch (_: CancellationException) {
                prepareCancelled = true
                return prepareLightningResult
            }
            return prepareLightningResult
        }
        override suspend fun prepareOnchainAsync(client: Long, address: String, amount: Long) =
            NativeResult.Success(OnchainQuote(45, amount, 1, address))
        override suspend fun executeLightningAsync(client: Long, quote: Long, correlationId: String): NativeResult<LightningSend> {
            executeCalls++
            return executeResult(correlationId)
        }
        override suspend fun executeOnchainAsync(client: Long, quote: Long, correlationId: String) =
            NativeResult.Success(OnchainSend(1, correlationId, "operation-2"))
        override suspend fun pendingOperationsAsync(client: Long) = NativeResult.Success(PendingNativeOperations(pending))
        override suspend fun reconcileOperationAsync(client: Long, correlationId: String, kind: DurableOperationKind) =
            NativeResult.Success(OperationReconciliationResult(correlationId, kind, reconciliationStatus, null, 1))
        override suspend fun clearOperationAsync(client: Long, correlationId: String): NativeResult<DurableOperationCleared> {
            clearCalls++
            pending = emptyList()
            return NativeResult.Success(DurableOperationCleared(true))
        }
    }

    private class MemoryJournal(var value: PendingIrreversibleOperation? = null) : IrreversibleOperationJournal {
        override fun read() = value
        override fun begin(operation: PendingIrreversibleOperation): Boolean {
            value = operation
            return true
        }
        override fun clear(): Boolean { value = null; return true }
    }
}
