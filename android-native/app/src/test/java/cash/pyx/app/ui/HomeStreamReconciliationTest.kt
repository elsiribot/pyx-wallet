package cash.pyx.app.ui

import cash.pyx.app.data.BootstrapState
import cash.pyx.app.data.HomeStreamRepository
import cash.pyx.app.data.WalletBootstrapRepository
import cash.pyx.app.nativeapi.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeStreamReconciliationTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `reconnect retains latest nonzero balance and does not duplicate recent activity`() = runTest(dispatcher) {
        val initial = wallet(balance = 500, payments = listOf(payment("first", 1)))
        val streams = ControllableHomeRepository(initial)
        val viewModel = viewModel(initial, streams)
        runCurrent()

        viewModel.setResumed(true)
        runCurrent()
        streams.balances.emit(900)
        streams.payments.emit(PaymentUpdate(listOf(payment("second", 2), payment("first", 1)), null))
        runCurrent()

        viewModel.setResumed(false)
        runCurrent()
        viewModel.setResumed(true)
        runCurrent()
        streams.payments.emit(PaymentUpdate(listOf(payment("second", 2), payment("first", 1)), null))
        runCurrent()

        val selected = (viewModel.state.value as BootstrapState.Home).snapshot.selected!!
        assertEquals(900L, selected.balanceSat)
        assertEquals(listOf("second", "first"), selected.payments.map(Payment::operationId))
        assertEquals(selected.payments.size, selected.payments.map(Payment::operationId).distinct().size)
        viewModel.setResumed(false)
    }

    @Test fun `three failed fallback polls retain cached wallet and expose offline state`() = runTest(dispatcher) {
        val initial = wallet(balance = 700, payments = listOf(payment("cached", 1)))
        val streams = ControllableHomeRepository(initial).apply { failSnapshots = true }
        val viewModel = viewModel(initial, streams)
        runCurrent()

        viewModel.setResumed(true)
        runCurrent()
        advanceTimeBy(10_000) // first failure after the normal refresh interval
        runCurrent()
        assertTrue(viewModel.refreshStatus.value is HomeRefreshStatus.Degraded)
        advanceTimeBy(1_000) // first retry
        runCurrent()
        advanceTimeBy(2_000) // second retry, third consecutive failure
        runCurrent()

        val status = viewModel.refreshStatus.value
        assertTrue(status is HomeRefreshStatus.Offline)
        assertEquals(3, (status as HomeRefreshStatus.Offline).consecutiveFailures)
        val selected = (viewModel.state.value as BootstrapState.Home).snapshot.selected!!
        assertEquals(700L, selected.balanceSat)
        assertEquals(listOf("cached"), selected.payments.map(Payment::operationId))
        viewModel.setResumed(false)
    }

    private fun viewModel(initial: WalletSnapshot, streams: HomeStreamRepository): WalletBootstrapViewModel {
        val repository = WalletBootstrapRepository(BootstrapApi(initial), "/tmp/home-stream-test")
        return WalletBootstrapViewModel(repository, workerDispatcher = dispatcher, homeRepository = streams)
    }

    private class ControllableHomeRepository(private val initial: WalletSnapshot) : HomeStreamRepository {
        val balances = MutableSharedFlow<Long>(extraBufferCapacity = 4)
        val payments = MutableSharedFlow<PaymentUpdate>(extraBufferCapacity = 4)
        var failSnapshots = false

        override suspend fun snapshotAsync(factory: Long): NativeResult<WalletSnapshot> =
            if (failSnapshots) NativeResult.Failure(AndroidError("offline", "Guardians are unavailable.", true))
            else NativeResult.Success(initial)
        override fun balanceEvents(client: Long): Flow<Long> = balances
        override fun paymentEvents(client: Long): Flow<PaymentUpdate> = payments
        override fun satsToFiat(client: Long, amount: Long): NativeResult<FiatDisplay?> = NativeResult.Success(null)
    }

    private class BootstrapApi(private val snapshot: WalletSnapshot) : NativeWalletApi {
        override fun listFiatCurrencies() = NativeResult.Success(FiatCurrencies(emptyList()))
        override fun classifyInput(payload: String) = NativeResult.Success(InputType.UNKNOWN)
        override fun closeTransientHandle(handle: Long, kind: String) = NativeResult.Success(TransientClosed(true))
        override suspend fun bootstrapAsync(filesDir: String) = NativeResult.Success(BootstrapSession.Ready("test", 1, 7))
        override suspend fun walletSnapshotAsync(factoryHandle: Long) = NativeResult.Success(snapshot)
        override suspend fun shutdownAndroidSession() = NativeResult.Success(AndroidSessionShutdown(true))
    }

    private fun wallet(balance: Long, payments: List<Payment>) = WalletSnapshot(
        currencyCode = "USD",
        federations = listOf(FederationSummary("fed", "Test federation", 4)),
        selected = SelectedWallet(9, "fed", "Test federation", balance, payments),
    )

    private fun payment(id: String, timestamp: Long) = Payment(
        operationId = id,
        direction = PaymentDirection.INCOMING,
        type = PaymentType.LIGHTNING,
        amountSat = 10,
        timestampMillis = timestamp,
        status = PaymentStatus.SUCCEEDED,
    )
}
