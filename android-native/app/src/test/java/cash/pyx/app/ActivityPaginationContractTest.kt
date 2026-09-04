package cash.pyx.app

import cash.pyx.app.nativeapi.*
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ActivityPaginationContractTest {
    private fun payment(id: String, timestamp: Long, status: PaymentStatus = PaymentStatus.SUCCEEDED) = Payment(
        id, PaymentDirection.OUTGOING, PaymentType.LIGHTNING, 10, 1, timestamp, status,
    )

    private fun page(cursor: String? = null) = """{"payments":[{"operationId":"a","direction":"outgoing","type":"lightning","amountSat":10,"feeSat":1,"timestampMillis":20,"status":"succeeded","fiatAmount":null,"fiatCurrencyCode":null}],"nextCursor":${cursor?.let { "\"$it\"" } ?: "null"}}"""

    @Test fun pageParserAcceptsEndAndOpaqueCursor() = runTest {
        val cursor = "pyx1.0000000000000014.${"ab".repeat(32)}"
        val api = JniNativeWalletApi(libraryLoader = {}, paymentHistoryPageAsyncBinding = { _, supplied, size, callback ->
            assertEquals("", supplied); assertEquals(50, size); callback.onSuccess(31, page(cursor)); 31
        })
        val result = api.paymentHistoryPageAsync(1, null, 50) as NativeResult.Success
        assertEquals(listOf("a"), result.value.payments.map { it.operationId })
        assertEquals(cursor, result.value.nextCursor)

        val end = JniNativeWalletApi(libraryLoader = {}, paymentHistoryPageAsyncBinding = { _, _, _, callback ->
            callback.onSuccess(32, page()); 32
        }).paymentHistoryPageAsync(1, cursor, 20) as NativeResult.Success
        assertNull(end.value.nextCursor)
    }

    @Test fun malformedCursorResponseIsRejected() = runTest {
        val api = JniNativeWalletApi(libraryLoader = {}, paymentHistoryPageAsyncBinding = { _, _, _, callback ->
            callback.onSuccess(33, page("raw-operation-id")); 33
        })
        assertEquals("invalid_native_response", (api.paymentHistoryPageAsync(1, null, 10) as NativeResult.Failure).error.code)
    }

    @Test fun cancellationPropagatesAndLatePageIsSuppressed() = runTest {
        var callback: NativeRequestCallback? = null
        var cancelled = 0L
        val api = JniNativeWalletApi(
            libraryLoader = {},
            paymentHistoryPageAsyncBinding = { _, _, _, value -> callback = value; 34 },
            cancelRequestBinding = { cancelled = it; """{"cancelled":true}""" },
        )
        val job = launch { api.paymentHistoryPageAsync(1, null, 10) }
        testScheduler.runCurrent()
        job.cancelAndJoin()
        assertEquals(34L, cancelled)
        callback!!.onSuccess(34, page())
        assertTrue(job.isCancelled)
    }

}
