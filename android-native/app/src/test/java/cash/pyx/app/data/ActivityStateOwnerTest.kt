package cash.pyx.app.data

import cash.pyx.app.nativeapi.AndroidError
import cash.pyx.app.nativeapi.NativeResult
import cash.pyx.app.nativeapi.Payment
import cash.pyx.app.nativeapi.PaymentDetails
import cash.pyx.app.nativeapi.PaymentDirection
import cash.pyx.app.nativeapi.PaymentPage
import cash.pyx.app.nativeapi.PaymentStatus
import cash.pyx.app.nativeapi.PaymentType
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ActivityStateOwnerTest {
    @Test fun `same-client reset rejects a non-cooperative stale page`() = runTest {
        val repository = ControllableActivityRepository()
        val owner = ActivityStateOwner(repository, this)
        owner.selectClient(7)

        owner.loadNextPage()
        runCurrent()
        owner.loadNextPage(reset = true)
        runCurrent()
        assertEquals(2, repository.pages.size)

        repository.pages[1].succeed(page("fresh", 20))
        runCurrent()
        repository.pages[0].succeed(page("stale", 30))
        runCurrent()

        assertEquals(listOf("fresh"), owner.state.value.payments.map(Payment::operationId))
        assertFalse(owner.state.value.loading)
    }

    @Test fun `client switch rejects old page and detail completions`() = runTest {
        val repository = ControllableActivityRepository()
        val owner = ActivityStateOwner(repository, this)
        owner.selectClient(1)
        owner.loadNextPage()
        owner.openDetail("old-detail")
        runCurrent()

        owner.selectClient(2)
        owner.loadNextPage()
        owner.openDetail("new-detail")
        runCurrent()
        repository.pages.single { it.client == 2L }.succeed(page("new-row", 2))
        repository.details.single { it.client == 2L }.succeed(payment("new-detail", 2))
        runCurrent()

        repository.pages.single { it.client == 1L }.succeed(page("old-row", 50))
        repository.details.single { it.client == 1L }.succeed(payment("old-detail", 50))
        runCurrent()

        assertEquals(2L, owner.state.value.clientHandle)
        assertEquals(listOf("new-row"), owner.state.value.payments.map(Payment::operationId))
        assertEquals("new-detail", (owner.state.value.detail as ActivityDetailState.Open).payment.operationId)
    }

    @Test fun `latest detail wins and dismissal rejects late completion`() = runTest {
        val repository = ControllableActivityRepository()
        val owner = ActivityStateOwner(repository, this)
        owner.selectClient(4)

        owner.openDetail("first")
        runCurrent()
        owner.openDetail("second")
        runCurrent()
        repository.details.single { it.operationId == "second" }.succeed(payment("second", 2))
        runCurrent()
        repository.details.single { it.operationId == "first" }.succeed(payment("first", 3))
        runCurrent()
        assertEquals("second", (owner.state.value.detail as ActivityDetailState.Open).payment.operationId)

        owner.openDetail("dismissed")
        runCurrent()
        owner.dismissDetail()
        repository.details.single { it.operationId == "dismissed" }.succeed(payment("dismissed", 4))
        runCurrent()
        assertEquals(ActivityDetailState.Closed, owner.state.value.detail)
    }

    @Test fun `slow detail refresh never blocks live row merging`() = runTest {
        val repository = ControllableActivityRepository()
        val owner = ActivityStateOwner(repository, this)
        owner.selectClient(9)
        owner.loadNextPage()
        runCurrent()
        repository.pages.single().succeed(page("selected", 1), nextCursor = "more")
        runCurrent()
        owner.openDetail("selected")
        runCurrent()
        repository.details.single().succeed(payment("selected", 1, PaymentStatus.PENDING))
        runCurrent()

        owner.acceptLivePayments(9, listOf(
            payment("new", 3),
            payment("selected", 1, PaymentStatus.SUCCEEDED),
        ))
        runCurrent()
        val delayedRefresh = repository.details.last()
        assertEquals(listOf("new", "selected"), owner.state.value.payments.map(Payment::operationId))
        assertEquals(PaymentStatus.SUCCEEDED, (owner.state.value.detail as ActivityDetailState.Open).payment.status)

        owner.acceptLivePayments(9, listOf(payment("newer", 4)))
        runCurrent()
        assertEquals(listOf("newer", "new", "selected"), owner.state.value.payments.map(Payment::operationId))
        delayedRefresh.succeed(payment("selected", 1, PaymentStatus.SUCCEEDED))
        runCurrent()
        assertEquals("selected", (owner.state.value.detail as ActivityDetailState.Open).payment.operationId)
    }

    @Test fun `failed page retains cache and duplicate loads issue one request`() = runTest {
        val repository = ControllableActivityRepository()
        val owner = ActivityStateOwner(repository, this)
        owner.selectClient(11)
        owner.loadNextPage()
        runCurrent()
        repository.pages.single().succeed(page("cached", 1), nextCursor = "next")
        runCurrent()

        owner.loadNextPage()
        owner.loadNextPage()
        runCurrent()
        assertEquals(2, repository.pages.size)
        repository.pages.last().fail("Offline")
        runCurrent()
        assertEquals(listOf("cached"), owner.state.value.payments.map(Payment::operationId))
        assertEquals("Offline", owner.state.value.error)
        assertTrue(owner.state.value.initialized)

        owner.loadNextPage()
        runCurrent()
        assertEquals(3, repository.pages.size)
        repository.pages.last().succeed(page("next-row", 2))
        runCurrent()
        assertEquals(listOf("next-row", "cached"), owner.state.value.payments.map(Payment::operationId))
        assertEquals(null, owner.state.value.error)
    }

    @Test fun `failed reset retains cached rows until a successful replacement`() = runTest {
        val repository = ControllableActivityRepository()
        val owner = ActivityStateOwner(repository, this)
        owner.selectClient(12)
        owner.loadNextPage()
        runCurrent()
        repository.pages.single().succeed(page("cached", 1), nextCursor = "next")
        runCurrent()

        owner.loadNextPage(reset = true)
        runCurrent()
        assertEquals(listOf("cached"), owner.state.value.payments.map(Payment::operationId))
        repository.pages.last().fail("Refresh failed")
        runCurrent()
        assertEquals(listOf("cached"), owner.state.value.payments.map(Payment::operationId))

        owner.loadNextPage(reset = true)
        runCurrent()
        repository.pages.last().succeed(page("replacement", 4))
        runCurrent()
        assertEquals(listOf("replacement"), owner.state.value.payments.map(Payment::operationId))
    }

    private class ControllableActivityRepository : ActivityRepository {
        val pages = mutableListOf<PageCall>()
        val details = mutableListOf<DetailCall>()

        override suspend fun page(clientHandle: Long, cursor: String?, pageSize: Int): NativeResult<PaymentPage> =
            suspendCoroutine { continuation -> pages += PageCall(clientHandle, cursor, continuation) }

        override suspend fun details(clientHandle: Long, operationId: String): NativeResult<PaymentDetails> =
            suspendCoroutine { continuation -> details += DetailCall(clientHandle, operationId, continuation) }
    }

    private class PageCall(
        val client: Long,
        val cursor: String?,
        private val continuation: Continuation<NativeResult<PaymentPage>>,
    ) {
        fun succeed(payments: List<Payment>, nextCursor: String? = null) =
            continuation.resume(NativeResult.Success(PaymentPage(payments, nextCursor)))
        fun fail(message: String) = continuation.resume(
            NativeResult.Failure(AndroidError("offline", message, true)),
        )
    }

    private class DetailCall(
        val client: Long,
        val operationId: String,
        private val continuation: Continuation<NativeResult<PaymentDetails>>,
    ) {
        fun succeed(payment: Payment) = continuation.resume(NativeResult.Success(PaymentDetails(payment)))
    }

    private companion object {
        fun page(id: String, timestamp: Long) = listOf(payment(id, timestamp))
        fun payment(id: String, timestamp: Long, status: PaymentStatus = PaymentStatus.SUCCEEDED) = Payment(
            id,
            PaymentDirection.OUTGOING,
            PaymentType.LIGHTNING,
            10,
            1,
            timestamp,
            status,
        )
    }
}
