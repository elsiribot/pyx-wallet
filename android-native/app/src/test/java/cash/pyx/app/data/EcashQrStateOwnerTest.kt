package cash.pyx.app.data

import cash.pyx.app.nativeapi.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EcashQrStateOwnerTest {
    @Test fun `static display never creates native encoder and stop clears secret`() = runTest {
        val repository = FakeRepository()
        val owner = EcashQrStateOwner(repository)
        owner.startDisplay(this, TOKEN, useStaticQr = true, frameDelayMillis = 350)
        assertEquals(TOKEN, owner.state.value.displayFrame)
        assertEquals(0, repository.encoderCreations)
        owner.stopDisplay()
        assertNull(owner.state.value.displayFrame)
    }

    @Test fun `animated display emits frames and closes encoder exactly once`() = runTest {
        val repository = FakeRepository()
        val owner = EcashQrStateOwner(repository)
        owner.startDisplay(this, TOKEN, useStaticQr = false, frameDelayMillis = 350)
        runCurrent()
        assertEquals("fedimint1frame-1", owner.state.value.displayFrame)
        advanceTimeBy(350)
        runCurrent()
        assertEquals("fedimint1frame-2", owner.state.value.displayFrame)
        owner.stopDisplay()
        runCurrent()
        assertEquals(listOf(11L to "encoder"), repository.closed)
        assertNull(owner.state.value.displayFrame)
        assertFalse(owner.state.value.encoding)
    }

    @Test fun `encoder failure is feature scoped and does not expose a frame`() = runTest {
        val repository = FakeRepository().apply { encoderFailure = failure("Animation unavailable") }
        val owner = EcashQrStateOwner(repository)
        owner.startDisplay(this, TOKEN, useStaticQr = false, frameDelayMillis = 350)
        runCurrent()
        assertEquals("Animation unavailable", owner.state.value.error)
        assertNull(owner.state.value.displayFrame)
        assertFalse(owner.state.value.encoding)
    }

    @Test fun `shuffled fragments ignore duplicates and terminal token publishes once`() = runTest {
        val repository = FakeRepository(completeAfter = 2)
        val owner = EcashQrStateOwner(repository)
        val decoded = mutableListOf<String>()
        owner.feedFrame(this, "fedimint1part-b", decoded::add)
        owner.feedFrame(this, "fedimint1part-a", decoded::add)
        owner.feedFrame(this, "fedimint1part-b", decoded::add)
        runCurrent()
        assertEquals(listOf("fedimint1part-b", "fedimint1part-a"), repository.fragments)
        assertEquals(listOf(TOKEN), decoded)
        assertEquals(TOKEN, owner.state.value.decodedPayload)
        assertEquals(0, owner.state.value.decodeProgress)
        owner.feedFrame(this, "fedimint1part-a", decoded::add)
        runCurrent()
        assertEquals(2, repository.fragments.size)
        assertEquals(1, repository.decoderCreations)
    }

    @Test fun `invalid and oversized frames perform no native work`() = runTest {
        val repository = FakeRepository()
        val owner = EcashQrStateOwner(repository)
        owner.feedFrame(this, "https://example.com")
        owner.feedFrame(this, "fedimint1" + "x".repeat(4 * 1024))
        runCurrent()
        assertEquals(0, repository.decoderCreations)
        assertTrue(repository.fragments.isEmpty())
    }

    @Test fun `decoder failure closes handle resets progress and permits fresh decoder`() = runTest {
        val repository = FakeRepository().apply { fragmentFailure = failure("Unreadable QR") }
        val owner = EcashQrStateOwner(repository)
        owner.feedFrame(this, "fedimint1bad")
        runCurrent()
        assertEquals("Unreadable QR", owner.state.value.error)
        assertEquals(listOf(21L to "decoder"), repository.closed)
        assertEquals(0, owner.state.value.decodeProgress)
        repository.fragmentFailure = null
        owner.feedFrame(this, "fedimint1good")
        runCurrent()
        assertEquals(2, repository.decoderCreations)
    }

    @Test fun `stop decoder invalidates queued frames and closes active handle`() = runTest {
        val repository = FakeRepository(completeAfter = 9)
        val owner = EcashQrStateOwner(repository)
        owner.feedFrame(this, "fedimint1first")
        runCurrent()
        assertEquals(1, owner.state.value.decodeProgress)
        owner.feedFrame(this, "fedimint1queued")
        owner.stopDecoder()
        runCurrent()
        assertEquals(listOf("fedimint1first"), repository.fragments)
        assertEquals(listOf(21L to "decoder"), repository.closed)
        assertEquals(0, owner.state.value.decodeProgress)
        assertNull(owner.state.value.decodedPayload)
    }

    @Test fun `late cancellation ignoring classification cannot replace newer scan`() = runTest {
        val first = CompletableDeferred<NativeResult<InputType>>()
        val repository = FakeRepository(classifier = { payload ->
            if (payload == "first") withContext(NonCancellable) { first.await() }
            else NativeResult.Success(InputType.BITCOIN)
        })
        val owner = EcashQrStateOwner(repository)
        owner.classify(this, "first")
        runCurrent()
        owner.classify(this, "second")
        runCurrent()
        assertEquals("second", owner.state.value.classifiedInput?.payload)
        first.complete(NativeResult.Success(InputType.LIGHTNING))
        runCurrent()
        assertEquals(InputType.BITCOIN, owner.state.value.classifiedInput?.type)
        assertEquals("second", owner.state.value.classifiedInput?.payload)
    }

    @Test fun `unknown classification reports scoped error without routing`() = runTest {
        val owner = EcashQrStateOwner(FakeRepository(classifier = { NativeResult.Success(InputType.UNKNOWN) }))
        owner.classify(this, "unknown")
        runCurrent()
        assertNull(owner.state.value.classifiedInput)
        assertEquals("This QR code or link is not supported.", owner.state.value.error)
        assertFalse(owner.state.value.classifying)
    }

    private class FakeRepository(
        private val completeAfter: Int = Int.MAX_VALUE,
        private val classifier: suspend (String) -> NativeResult<InputType> = { NativeResult.Success(InputType.ECASH) },
    ) : EcashQrRepository {
        var encoderCreations = 0
        var decoderCreations = 0
        var encoderFailure: NativeResult.Failure? = null
        var fragmentFailure: NativeResult.Failure? = null
        val fragments = mutableListOf<String>()
        val closed = mutableListOf<Pair<Long, String>>()
        private var frame = 0

        override suspend fun classify(payload: String) = classifier(payload)
        override fun createEncoder(payload: String): NativeResult<EcashCodecHandle> {
            encoderCreations++
            return encoderFailure ?: NativeResult.Success(EcashCodecHandle(11))
        }
        override fun nextFragment(handle: Long) = NativeResult.Success(EcashFragment("fedimint1frame-${++frame}"))
        override fun createDecoder(): NativeResult<EcashCodecHandle> {
            decoderCreations++
            return NativeResult.Success(EcashCodecHandle(21))
        }
        override fun addFragment(handle: Long, fragment: String): NativeResult<EcashDecodeResult> {
            fragmentFailure?.let { return it }
            fragments += fragment
            val complete = fragments.size >= completeAfter
            return NativeResult.Success(EcashDecodeResult(complete, TOKEN.takeIf { complete }))
        }
        override fun closeCodec(handle: Long, kind: String): NativeResult<EcashCodecClosed> {
            closed += handle to kind
            return NativeResult.Success(EcashCodecClosed(true))
        }
    }

    private companion object {
        const val TOKEN = "fedimint1token"
        fun failure(message: String) = NativeResult.Failure(AndroidError("codec", message, true))
    }
}
