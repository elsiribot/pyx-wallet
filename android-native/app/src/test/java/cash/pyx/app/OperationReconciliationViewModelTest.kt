package cash.pyx.app

import cash.pyx.app.data.BootstrapState
import cash.pyx.app.data.WalletBootstrapRepository
import cash.pyx.app.nativeapi.*
import cash.pyx.app.security.*
import cash.pyx.app.ui.WalletBootstrapViewModel
import cash.pyx.app.ui.WalletOperation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OperationReconciliationViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val correlation = "00112233445566778899aabbccddeeff"
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun cleanup() = Dispatchers.resetMain()

    @Test fun conclusiveSuccessFailureAndNotSubmittedClearBothRecords() = runTest(dispatcher) {
        for (status in listOf(DurableOperationStatus.SUCCEEDED, DurableOperationStatus.FAILED, DurableOperationStatus.NOT_SUBMITTED)) {
            val journal = MemoryJournal(record())
            val api = Api(status = status)
            val viewModel = viewModel(api, journal)
            advanceUntilIdle()
            assertNull(journal.value)
            assertEquals(1, api.clearCalls)
            when (status) {
                DurableOperationStatus.SUCCEEDED -> assertTrue(viewModel.operation.value is WalletOperation.Success)
                else -> assertTrue(viewModel.operation.value is WalletOperation.Failure)
            }
        }
    }

    @Test fun pendingInFlightAndAmbiguousRemainBlockedAndRefreshable() = runTest(dispatcher) {
        for (status in listOf(DurableOperationStatus.PENDING, DurableOperationStatus.IN_FLIGHT, DurableOperationStatus.AMBIGUOUS)) {
            val journal = MemoryJournal(record())
            val api = Api(status = status)
            val viewModel = viewModel(api, journal)
            advanceUntilIdle()
            assertNotNull(journal.value)
            assertEquals(0, api.clearCalls)
            viewModel.refreshOperationReconciliation()
            advanceUntilIdle()
            assertEquals(2, api.reconcileCalls)
            assertNotNull(journal.value)
        }
    }

    @Test fun failedConclusiveClearNeverShowsSafeToRetryAndKeepsJournal() = runTest(dispatcher) {
        val journal = MemoryJournal(record())
        val api = Api(status = DurableOperationStatus.NOT_SUBMITTED, clearSucceeds = false)
        val viewModel = viewModel(api, journal)
        advanceUntilIdle()
        assertNotNull(journal.value)
        assertTrue((viewModel.operation.value as WalletOperation.Failure).message.contains("could not be cleared"))
    }

    @Test fun legacyMarkerWithoutCorrelationRemainsConservativelyBlocked() = runTest(dispatcher) {
        val journal = MemoryJournal(PendingIrreversibleOperation(PendingIrreversibleOperation.Kind.ONCHAIN_SEND, 1L))
        val api = Api(status = DurableOperationStatus.NOT_SUBMITTED, exposeNativeRecord = false)
        val viewModel = viewModel(api, journal)
        advanceUntilIdle()
        assertNotNull(journal.value)
        assertEquals(0, api.reconcileCalls)
        viewModel.executeOnchain(11, 44)
        advanceUntilIdle()
        assertEquals(0, api.executeCalls)
    }

    @Test fun nativeRecordOnlyIsRecoveredAfterProcessRecreation() = runTest(dispatcher) {
        val journal = MemoryJournal()
        val api = Api(status = DurableOperationStatus.PENDING, exposeNativeRecord = true)
        viewModel(api, journal)
        advanceUntilIdle()
        assertEquals(correlation, journal.value?.correlationId)
        assertEquals("fed-a", journal.value?.federationId)

        viewModel(api, journal)
        advanceUntilIdle()
        assertNotNull(journal.value)
        assertTrue(api.reconcileCalls >= 2)
    }

    @Test fun mismatchedNativeCorrelationNeverClearsOrPermitsRetry() = runTest(dispatcher) {
        val journal = MemoryJournal(record())
        val api = Api(status = DurableOperationStatus.SUCCEEDED, pendingCorrelation = "ffeeddccbbaa99887766554433221100")
        val viewModel = viewModel(api, journal)
        advanceUntilIdle()
        assertEquals(PendingIrreversibleOperation.ReconciliationStatus.AMBIGUOUS, journal.value?.reconciliationStatus)
        assertEquals(0, api.clearCalls)
        viewModel.executeLightning(11, 44)
        advanceUntilIdle()
        assertEquals(0, api.executeCalls)
    }

    @Test fun failedExecuteIsNeverAutoRetriedAndStaysBlockedWhilePending() = runTest(dispatcher) {
        val journal = MemoryJournal()
        var observedCommittedMarker = false
        val api = Api(status = DurableOperationStatus.PENDING, exposeNativeRecord = false, onExecute = { passed ->
            observedCommittedMarker = journal.value?.correlationId == passed && journal.value?.federationId == "fed-a"
        })
        val viewModel = viewModel(api, journal)
        advanceUntilIdle()
        viewModel.executeLightning(11, 44)
        advanceUntilIdle()
        viewModel.executeLightning(11, 44)
        advanceUntilIdle()
        assertEquals(1, api.executeCalls)
        assertTrue(observedCommittedMarker)
        assertNotNull(journal.value)
    }

    @Test fun operationOwnedByAnotherFederationRemainsBlockedWithoutWrongClientQuery() = runTest(dispatcher) {
        val journal = MemoryJournal(record().copy(federationId = "fed-other"))
        val api = Api(status = DurableOperationStatus.SUCCEEDED)
        viewModel(api, journal)
        advanceUntilIdle()
        assertEquals(0, api.reconcileCalls)
        assertNotNull(journal.value)
    }

    private fun record() = PendingIrreversibleOperation(
        PendingIrreversibleOperation.Kind.LIGHTNING_SEND, 10L, correlation, "fed-a",
    )

    private fun viewModel(api: Api, journal: MemoryJournal) = WalletBootstrapViewModel(
        WalletBootstrapRepository(api, "/files"), dispatcher,
        operationReconciliation = IrreversibleOperationReconciliation(journal) { 20L },
        correlationIdGenerator = { correlation },
    )

    private inner class Api(
        var status: DurableOperationStatus,
        var exposeNativeRecord: Boolean = true,
        var pendingCorrelation: String = correlation,
        var clearSucceeds: Boolean = true,
        var onExecute: (String) -> Unit = {},
    ) : NativeWalletApi {
        var clearCalls = 0
        var reconcileCalls = 0
        var executeCalls = 0
        private val selected = SelectedWallet(11, "fed-a", "Wallet", 100, emptyList())
        override suspend fun bootstrapAsync(filesDir: String) = NativeResult.Success<BootstrapSession>(BootstrapSession.Ready("1", 7, 9))
        override suspend fun walletSnapshotAsync(factoryHandle: Long) = NativeResult.Success(WalletSnapshot("EUR", emptyList(), selected))
        override suspend fun pendingOperationsAsync(clientHandle: Long) = NativeResult.Success(PendingNativeOperations(
            if (exposeNativeRecord) listOf(PendingNativeOperation(pendingCorrelation, DurableOperationKind.LIGHTNING, DurableOperationStatus.SUBMITTED)) else emptyList(),
        ))
        override suspend fun reconcileOperationAsync(clientHandle: Long, correlationId: String, kind: DurableOperationKind): NativeResult<OperationReconciliationResult> {
            reconcileCalls++
            return NativeResult.Success(OperationReconciliationResult(correlationId, DurableOperationKind.LIGHTNING, status,
                if (status == DurableOperationStatus.SUCCEEDED) "operation-1" else null,
                if (status == DurableOperationStatus.NOT_SUBMITTED) 0 else 1))
        }
        override suspend fun clearOperationAsync(clientHandle: Long, correlationId: String): NativeResult<DurableOperationCleared> {
            clearCalls++
            if (clearSucceeds) exposeNativeRecord = false
            return NativeResult.Success(DurableOperationCleared(clearSucceeds))
        }
        override suspend fun executeLightningSendAsync(clientHandle: Long, quoteHandle: Long, correlationId: String): NativeResult<LightningSend> {
            executeCalls++
            onExecute(correlationId)
            exposeNativeRecord = true
            return NativeResult.Failure(AndroidError("submission_unknown", "Submission interrupted.", true))
        }
        override suspend fun executeOnchainSendAsync(clientHandle: Long, quoteHandle: Long, correlationId: String): NativeResult<OnchainSend> {
            executeCalls++
            return NativeResult.Failure(AndroidError("submission_unknown", "Submission interrupted.", true))
        }
        override fun listFiatCurrencies() = NativeResult.Success(FiatCurrencies(emptyList()))
        override fun classifyInput(payload: String) = NativeResult.Success(InputType.UNKNOWN)
        override fun closeTransientHandle(handle: Long, kind: String) = NativeResult.Success(TransientClosed(true))
    }

    private class MemoryJournal(var value: PendingIrreversibleOperation? = null) : IrreversibleOperationJournal {
        override fun read() = value
        override fun begin(operation: PendingIrreversibleOperation): Boolean { value = operation; return true }
        override fun clear(): Boolean { value = null; return true }
    }
}
