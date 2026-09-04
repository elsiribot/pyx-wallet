package cash.pyx.app.data

import cash.pyx.app.nativeapi.NativeSubscriptionCallback
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WalletSubscriptionsTest {
    @Test fun `synchronous first event is retained and cancellation closes exactly once`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var callback: NativeSubscriptionCallback? = null
        var closes = 0
        val values = mutableListOf<Long>()
        val flow = subscriptionFlow(
            subscribe = {
                callback = it
                it.onEvent(7, "{\"balanceSat\":41}")
                7
            },
            closeNative = { closes++ },
            parse = SubscriptionDtos::balance,
            cleanupScope = this,
        )
        val job = launch(dispatcher) { flow.collect { values += it } }
        advanceUntilIdle()
        assertEquals(listOf(41L), values)
        job.cancelAndJoin()
        callback!!.onError(7, "late", "late", false)
        callback!!.onEvent(7, "{\"balanceSat\":99}")
        advanceUntilIdle()
        assertEquals(1, closes)
        assertEquals(listOf(41L), values)
    }

    @Test fun `slow consumer receives latest balance without unbounded backlog`() = runTest {
        lateinit var callback: NativeSubscriptionCallback
        val values = mutableListOf<Long>()
        val flow = subscriptionFlow({ callback = it; 9 }, {}, SubscriptionDtos::balance, this)
        val job = launch(StandardTestDispatcher(testScheduler)) { flow.take(2).collect { values += it; delay(100) } }
        testScheduler.runCurrent()
        callback.onEvent(9, "{\"balanceSat\":0}")
        testScheduler.runCurrent()
        repeat(999) { callback.onEvent(9, "{\"balanceSat\":${it + 1}}") }
        advanceUntilIdle()
        assertEquals(listOf(0L, 999L), values)
        job.cancelAndJoin()
    }

    @Test fun `synchronous subscription error terminates and still closes`() = runTest {
        var closes = 0
        var failure: Throwable? = null
        val flow = subscriptionFlow<Long>(
            subscribe = { it.onError(11, "offline", "not connected", true); 11 },
            closeNative = { closes++ },
            parse = SubscriptionDtos::balance,
            cleanupScope = this,
        )
        flow.catch { failure = it }.collect()
        advanceUntilIdle()
        assertTrue(failure is IllegalStateException)
        assertEquals(1, closes)
    }

    @Test fun `strict DTO parsing rejects extra fields and inconsistent guardians`() {
        assertEquals(12L, SubscriptionDtos.balance("{\"balanceSat\":12}"))
        assertTrue(runCatching { SubscriptionDtos.balance("{\"balanceSat\":12,\"delta\":1}") }.exceptionOrNull() is JSONException)
        val invalid = """{"guardians":[{"name":"A","connected":true}],"onlineCount":0,"totalCount":1,"requiredCount":1,"state":"connected"}"""
        assertTrue(runCatching { SubscriptionDtos.connection(invalid) }.exceptionOrNull() is JSONException)
        val valid = """{"guardians":[{"name":"A","connected":true},{"name":"B","connected":true},{"name":"C","connected":false}],"onlineCount":2,"totalCount":3,"requiredCount":3,"state":"offline"}"""
        assertEquals(3, SubscriptionDtos.connection(valid).requiredCount)
        val wrongQuorum = valid.replace("\"requiredCount\":3", "\"requiredCount\":2")
        assertTrue(runCatching { SubscriptionDtos.connection(wrongQuorum) }.exceptionOrNull() is JSONException)
    }

    @Test fun `bounded payment delivery retains notification updates and closes once`() = runTest {
        lateinit var callback: NativeSubscriptionCallback
        var closes = 0
        val received = mutableListOf<Boolean>()
        val flow = subscriptionFlow(
            subscribe = { callback = it; 21 }, closeNative = { closes++ }, parse = SubscriptionDtos::payments,
            cleanupScope = this, capacity = 16,
        )
        val job = launch(StandardTestDispatcher(testScheduler)) {
            flow.take(2).collect { received += it.notification!!.success; delay(100) }
        }
        testScheduler.runCurrent()
        fun event(success: Boolean, status: String) = """{"payments":[{"operationId":"op","type":"lightning","direction":"incoming","amountSat":1,"feeSat":null,"timestampMillis":1,"status":"$status","fiatAmount":null,"fiatCurrencyCode":null}],"notification":{"direction":"incoming","success":$success,"amountSat":1,"type":"lightning"}}"""
        callback.onEvent(21, event(false, "failed"))
        callback.onEvent(21, event(true, "succeeded"))
        advanceUntilIdle()
        assertEquals(listOf(false, true), received)
        job.cancelAndJoin(); advanceUntilIdle()
        assertEquals(1, closes)
    }
}
