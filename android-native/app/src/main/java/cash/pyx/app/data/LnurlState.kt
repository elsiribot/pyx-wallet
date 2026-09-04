package cash.pyx.app.data

import cash.pyx.app.nativeapi.AndroidError
import cash.pyx.app.nativeapi.LightningQuote
import cash.pyx.app.nativeapi.LnurlSession
import cash.pyx.app.nativeapi.NativeResult
import cash.pyx.app.nativeapi.TransientClosed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

interface LnurlRepository {
    suspend fun prepareLnurl(request: String): NativeResult<LnurlSession>
    suspend fun prepareLnurlQuote(clientHandle: Long, sessionHandle: Long, amountSat: Long): NativeResult<LightningQuote>
    fun closeLnurlHandle(handle: Long): NativeResult<TransientClosed>
    fun closeQuoteHandle(handle: Long): NativeResult<TransientClosed>
}

sealed interface LnurlState {
    data object Idle : LnurlState
    data object Preparing : LnurlState
    data class Prepared(val session: LnurlSession) : LnurlState
    data class Quoting(val session: LnurlSession) : LnurlState
    data class Failure(val message: String) : LnurlState
}

/** Owns LNURL session handles until a current quote is safely transferred to the send flow. */
class LnurlStateOwner(private val repository: LnurlRepository, private val scope: CoroutineScope) {
    private val mutableState = MutableStateFlow<LnurlState>(LnurlState.Idle)
    val state: StateFlow<LnurlState> = mutableState.asStateFlow()
    private val handleLock = Any()
    private var sessionHandle = 0L
    @Volatile private var generation = 0L
    private var job: Job? = null

    fun prepare(request: String, onResult: (NativeResult<LnurlSession>) -> Unit = {}) {
        if (request.isBlank() || request.length > MAX_REQUEST_CHARS) {
            val failure = NativeResult.Failure(AndroidError("invalid_lnurl", "Enter a valid LNURL or Lightning address.", false))
            mutableState.value = LnurlState.Failure(failure.error.userMessage)
            onResult(failure)
            return
        }
        val requestGeneration = ++generation
        job?.cancel(); job = null
        closeSession()
        mutableState.value = LnurlState.Preparing
        job = scope.launch {
            try {
                when (val result = repository.prepareLnurl(request)) {
                    is NativeResult.Success -> if (synchronized(handleLock) {
                        if (requestGeneration == generation) { sessionHandle = result.value.sessionHandle; true } else false
                    }) {
                        mutableState.value = LnurlState.Prepared(result.value)
                        onResult(result)
                    } else repository.closeLnurlHandle(result.value.sessionHandle)
                    is NativeResult.Failure -> if (requestGeneration == generation) {
                        mutableState.value = LnurlState.Failure(result.error.userMessage)
                        onResult(result)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (requestGeneration == generation) {
                    val failure = unexpectedFailure()
                    mutableState.value = LnurlState.Failure(failure.error.userMessage)
                    onResult(failure)
                }
            } finally {
                if (requestGeneration == generation) job = null
            }
        }
    }

    fun prepareQuote(
        clientHandle: Long,
        requestedSessionHandle: Long,
        amountSat: Long,
        onResult: (NativeResult<LightningQuote>) -> Unit = {},
    ): Boolean {
        val prepared = mutableState.value as? LnurlState.Prepared ?: return false
        if (prepared.session.sessionHandle != requestedSessionHandle || clientHandle <= 0 || amountSat <= 0 || job?.isActive == true) return false
        val requestGeneration = ++generation
        mutableState.value = LnurlState.Quoting(prepared.session)
        job = scope.launch {
            try {
                when (val result = repository.prepareLnurlQuote(clientHandle, requestedSessionHandle, amountSat)) {
                    is NativeResult.Success -> if (requestGeneration == generation) {
                        // Ownership of this quote transfers to the send confirmation flow.
                        mutableState.value = LnurlState.Idle
                        onResult(result)
                    } else repository.closeQuoteHandle(result.value.quoteHandle)
                    is NativeResult.Failure -> if (requestGeneration == generation) {
                        mutableState.value = LnurlState.Failure(result.error.userMessage)
                        onResult(result)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (requestGeneration == generation) {
                    val failure = unexpectedFailure()
                    mutableState.value = LnurlState.Failure(failure.error.userMessage)
                    onResult(failure)
                }
            } finally {
                closeSession(requestedSessionHandle)
                if (requestGeneration == generation) job = null
            }
        }
        return true
    }

    fun clear() {
        generation++
        job?.cancel(); job = null
        closeSession()
        mutableState.value = LnurlState.Idle
    }

    fun close() = clear()

    private fun closeSession(expected: Long? = null) {
        val handle = synchronized(handleLock) {
            if (expected != null && sessionHandle != expected) return
            sessionHandle.also { sessionHandle = 0L }
        }
        if (handle > 0) repository.closeLnurlHandle(handle)
    }

    private fun unexpectedFailure() = NativeResult.Failure(
        AndroidError("lnurl_unexpected", "LNURL processing failed unexpectedly. Try again.", true),
    )

    private companion object { const val MAX_REQUEST_CHARS = 16 * 1024 }
}
