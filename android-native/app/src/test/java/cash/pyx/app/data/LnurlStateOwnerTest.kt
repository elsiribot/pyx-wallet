package cash.pyx.app.data

import cash.pyx.app.nativeapi.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LnurlStateOwnerTest {
    @Test fun `prepared session is closed exactly once by repeated clear`() = runTest {
        val repository = FakeRepository()
        val owner = LnurlStateOwner(repository, backgroundScope)
        owner.prepare("alice@example.com")
        runCurrent()
        assertTrue(owner.state.value is LnurlState.Prepared)
        owner.clear(); owner.clear()
        assertEquals(listOf(41L), repository.closedSessions)
        assertEquals(LnurlState.Idle, owner.state.value)
    }

    @Test fun `late cancellation ignoring prepare closes stale session and cannot replace current`() = runTest {
        val first = CompletableDeferred<NativeResult<LnurlSession>>()
        val repository = FakeRepository(prepareCall = { request ->
            if (request == "first") withContext(NonCancellable) { first.await() }
            else NativeResult.Success(session(42))
        })
        val owner = LnurlStateOwner(repository, backgroundScope)
        owner.prepare("first")
        runCurrent()
        owner.prepare("second")
        runCurrent()
        assertEquals(42L, (owner.state.value as LnurlState.Prepared).session.sessionHandle)
        first.complete(NativeResult.Success(session(41)))
        runCurrent()
        assertEquals(listOf(41L), repository.closedSessions)
        assertEquals(42L, (owner.state.value as LnurlState.Prepared).session.sessionHandle)
    }

    @Test fun `successful quote closes session once and transfers quote ownership`() = runTest {
        val repository = FakeRepository()
        val owner = LnurlStateOwner(repository, backgroundScope)
        var result: NativeResult<LightningQuote>? = null
        owner.prepare("request")
        runCurrent()
        owner.prepareQuote(9, 41, 100) { result = it }
        runCurrent()
        assertTrue(result is NativeResult.Success)
        assertEquals(listOf(41L), repository.closedSessions)
        assertTrue(repository.closedQuotes.isEmpty())
        owner.clear()
        assertEquals(listOf(41L), repository.closedSessions)
    }

    @Test fun `quote failure closes session and publishes failure`() = runTest {
        val repository = FakeRepository(quoteCall = { _, _, _ -> failure("Quote failed") })
        val owner = LnurlStateOwner(repository, backgroundScope)
        var result: NativeResult<LightningQuote>? = null
        owner.prepare("request")
        runCurrent()
        owner.prepareQuote(9, 41, 100) { result = it }
        runCurrent()
        assertTrue(result is NativeResult.Failure)
        assertEquals("Quote failed", (owner.state.value as LnurlState.Failure).message)
        assertEquals(listOf(41L), repository.closedSessions)
    }

    @Test fun `clear during cancellation ignoring quote closes session and late quote exactly once`() = runTest {
        val quote = CompletableDeferred<NativeResult<LightningQuote>>()
        val repository = FakeRepository(quoteCall = { _, _, _ -> withContext(NonCancellable) { quote.await() } })
        val owner = LnurlStateOwner(repository, backgroundScope)
        var callbacks = 0
        owner.prepare("request")
        runCurrent()
        owner.prepareQuote(9, 41, 100) { callbacks++ }
        runCurrent()
        owner.clear()
        assertEquals(listOf(41L), repository.closedSessions)
        quote.complete(NativeResult.Success(lightningQuote(71)))
        runCurrent()
        assertEquals(0, callbacks)
        assertEquals(listOf(71L), repository.closedQuotes)
        assertEquals(listOf(41L), repository.closedSessions)
        assertEquals(LnurlState.Idle, owner.state.value)
    }

    @Test fun `double quote action and mismatched handle cannot duplicate native call`() = runTest {
        val quote = CompletableDeferred<NativeResult<LightningQuote>>()
        var quoteCalls = 0
        val repository = FakeRepository(quoteCall = { _, _, _ -> quoteCalls++; quote.await() })
        val owner = LnurlStateOwner(repository, backgroundScope)
        owner.prepare("request")
        runCurrent()
        owner.prepareQuote(9, 999, 100)
        owner.prepareQuote(9, 41, 100)
        owner.prepareQuote(9, 41, 100)
        runCurrent()
        assertEquals(1, quoteCalls)
        quote.complete(NativeResult.Success(lightningQuote(71)))
        runCurrent()
    }

    @Test fun `invalid request fails without native call`() = runTest {
        var prepareCalls = 0
        val repository = FakeRepository(prepareCall = { prepareCalls++; NativeResult.Success(session(41)) })
        val owner = LnurlStateOwner(repository, backgroundScope)
        var result: NativeResult<LnurlSession>? = null
        owner.prepare(" ") { result = it }
        assertEquals(0, prepareCalls)
        assertTrue(result is NativeResult.Failure)
        assertEquals("Enter a valid LNURL or Lightning address.", (owner.state.value as LnurlState.Failure).message)
    }

    private class FakeRepository(
        private val prepareCall: suspend (String) -> NativeResult<LnurlSession> = { NativeResult.Success(session(41)) },
        private val quoteCall: suspend (Long, Long, Long) -> NativeResult<LightningQuote> = { _, _, _ ->
            NativeResult.Success(lightningQuote(71))
        },
    ) : LnurlRepository {
        val closedSessions = mutableListOf<Long>()
        val closedQuotes = mutableListOf<Long>()
        override suspend fun prepareLnurl(request: String) = prepareCall(request)
        override suspend fun prepareLnurlQuote(clientHandle: Long, sessionHandle: Long, amountSat: Long) =
            quoteCall(clientHandle, sessionHandle, amountSat)
        override fun closeLnurlHandle(handle: Long): NativeResult<TransientClosed> {
            closedSessions += handle
            return NativeResult.Success(TransientClosed(true))
        }
        override fun closeQuoteHandle(handle: Long): NativeResult<TransientClosed> {
            closedQuotes += handle
            return NativeResult.Success(TransientClosed(true))
        }
    }

    private companion object {
        fun session(handle: Long) = LnurlSession(handle, 1, 1_000, false)
        fun lightningQuote(handle: Long) = LightningQuote(handle, 100, 1, "https://gateway", false)
        fun failure(message: String) = NativeResult.Failure(AndroidError("lnurl", message, true))
    }
}
