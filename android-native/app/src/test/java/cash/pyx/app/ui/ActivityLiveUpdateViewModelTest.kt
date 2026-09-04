package cash.pyx.app.ui

import cash.pyx.app.data.BootstrapState
import cash.pyx.app.data.ActivityDetailState
import cash.pyx.app.data.WalletBootstrapRepository
import cash.pyx.app.data.WalletSubscriptionBindings
import cash.pyx.app.nativeapi.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
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
class ActivityLiveUpdateViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `live terminal update deduplicates and refreshes the selected detail by operation id`() = runTest(dispatcher) {
        val api = ActivityApi()
        val subscriptions = PaymentSubscriptions()
        val viewModel = viewModel(api, subscriptions)
        runCurrent()
        viewModel.loadActivityPage(9)
        viewModel.paymentDetails(9, "same")
        runCurrent()
        viewModel.setResumed(true)
        runCurrent()

        subscriptions.emit(paymentsJson(paymentJson("new", 20, "succeeded"), paymentJson("same", 10, "succeeded")))
        runCurrent()

        assertEquals(listOf("new", "same", "old"), viewModel.activityState.value.payments.map(Payment::operationId))
        assertEquals(3, viewModel.activityState.value.payments.map(Payment::operationId).toSet().size)
        val detail = viewModel.activityState.value.detail as ActivityDetailState.Open
        assertEquals("same", detail.payment.operationId)
        assertEquals(PaymentStatus.SUCCEEDED, detail.payment.status)
        viewModel.setResumed(false)
        runCurrent()
    }

    @Test fun `late live refresh cannot replace a newly selected payment detail`() = runTest(dispatcher) {
        val api = ActivityApi()
        val subscriptions = PaymentSubscriptions()
        val viewModel = viewModel(api, subscriptions)
        runCurrent()
        viewModel.paymentDetails(9, "same")
        runCurrent()
        viewModel.setResumed(true)
        runCurrent()

        api.delayNextSameDetail = true
        subscriptions.emit(paymentsJson(paymentJson("same", 10, "succeeded")))
        runCurrent()
        assertTrue(api.delayedSameDetailRequested)

        viewModel.dismissPaymentDetails()
        viewModel.paymentDetails(9, "other")
        runCurrent()
        assertEquals("other", (viewModel.activityState.value.detail as ActivityDetailState.Open).payment.operationId)

        api.delayedSameDetail.complete(PaymentDetails(payment("same", 10, PaymentStatus.SUCCEEDED)))
        runCurrent()
        assertEquals("other", (viewModel.activityState.value.detail as ActivityDetailState.Open).payment.operationId)
        viewModel.setResumed(false)
        runCurrent()
    }

    private fun viewModel(api: ActivityApi, subscriptions: PaymentSubscriptions) = WalletBootstrapViewModel(
        WalletBootstrapRepository(api, "/files", subscriptions, CoroutineScope(SupervisorJob() + dispatcher)),
        dispatcher,
    )

    private class ActivityApi : NativeWalletApi {
        var delayNextSameDetail = false
        var delayedSameDetailRequested = false
        val delayedSameDetail = CompletableDeferred<PaymentDetails>()
        private var sameDetailCalls = 0

        private fun snapshot() = WalletSnapshot(
            "EUR",
            listOf(FederationSummary("fed", "Federation", 3)),
            SelectedWallet(9, "fed", "Federation", 500, listOf(payment("same", 10, PaymentStatus.PENDING))),
        )

        override suspend fun bootstrapAsync(filesDir: String) = NativeResult.Success<BootstrapSession>(BootstrapSession.Ready("1", 3, 7))
        override suspend fun walletSnapshotAsync(factoryHandle: Long) = NativeResult.Success(snapshot())
        override suspend fun paymentHistoryPageAsync(clientHandle: Long, cursor: String?, pageSize: Int) = NativeResult.Success(
            PaymentPage(listOf(payment("same", 10, PaymentStatus.PENDING), payment("old", 1, PaymentStatus.SUCCEEDED)), null),
        )
        override suspend fun paymentDetailsAsync(clientHandle: Long, operationId: String): NativeResult<PaymentDetails> {
            if (operationId == "same") sameDetailCalls++
            if (operationId == "same" && delayNextSameDetail) {
                delayNextSameDetail = false
                delayedSameDetailRequested = true
                return NativeResult.Success(delayedSameDetail.await())
            }
            val status = if (operationId == "same" && sameDetailCalls > 1) PaymentStatus.SUCCEEDED else PaymentStatus.PENDING
            return NativeResult.Success(PaymentDetails(payment(operationId, if (operationId == "same") 10 else 5, status)))
        }
        override suspend fun recoveryExpirySnapshotAsync(clientHandle: Long) = NativeResult.Success(
            RecoveryExpirySnapshot(true, null, false, null),
        )
        override fun satsToFiat(clientHandle: Long, amountSat: Long): NativeResult<FiatDisplay?> = NativeResult.Success(null)
        override fun listFiatCurrencies(): NativeResult<FiatCurrencies> = error("unused")
        override fun classifyInput(payload: String): NativeResult<InputType> = error("unused")
        override fun closeTransientHandle(handle: Long, kind: String): NativeResult<TransientClosed> =
            NativeResult.Success(TransientClosed(true))
    }

    private class PaymentSubscriptions : WalletSubscriptionBindings {
        private var nextHandle = 100L
        private var payment: Pair<Long, NativeSubscriptionCallback>? = null
        override fun subscribeBalance(client: Long, callback: NativeSubscriptionCallback) = nextHandle++
        override fun subscribeConnection(client: Long, callback: NativeSubscriptionCallback) = nextHandle++
        override fun subscribeRecovery(client: Long, callback: NativeSubscriptionCallback) = nextHandle++
        override fun subscribePayments(client: Long, callback: NativeSubscriptionCallback) = nextHandle++.also { payment = it to callback }
        override fun close(handle: Long) = Unit
        fun emit(json: String) = payment!!.let { (handle, callback) -> callback.onEvent(handle, json) }
    }

    private companion object {
        fun payment(id: String, timestamp: Long, status: PaymentStatus) = Payment(
            id, PaymentDirection.OUTGOING, PaymentType.LIGHTNING, 10, 1, timestamp, status,
        )

        fun paymentJson(id: String, timestamp: Long, status: String) =
            """{"operationId":"$id","type":"lightning","direction":"outgoing","amountSat":10,"feeSat":1,"timestampMillis":$timestamp,"status":"$status","fiatAmount":null,"fiatCurrencyCode":null}"""

        fun paymentsJson(vararg payments: String) = """{"payments":[${payments.joinToString()}],"notification":null}"""
    }
}
