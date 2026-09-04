package cash.pyx.app.ui

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeRefreshRetryPolicyTest {
    @Test fun exponentialRetryIsDeterministicAndCapped() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L),
            (1..8).map(HomeRefreshRetryPolicy::delayMillis))
        assertEquals(10_000L, HomeRefreshRetryPolicy.delayMillis(0))
    }

    @Test fun retryDelayUsesCoroutineVirtualTime() = runTest {
        var completed = false
        backgroundScope.launch {
            delay(HomeRefreshRetryPolicy.delayMillis(4))
            completed = true
        }
        advanceTimeBy(7_999)
        assertEquals(false, completed)
        advanceTimeBy(1)
        testScheduler.runCurrent()
        assertEquals(true, completed)
    }
}
