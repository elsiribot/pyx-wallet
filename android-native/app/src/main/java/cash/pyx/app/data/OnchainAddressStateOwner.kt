package cash.pyx.app.data

import cash.pyx.app.nativeapi.AddressRechecked
import cash.pyx.app.nativeapi.AndroidError
import cash.pyx.app.nativeapi.NativeResult
import cash.pyx.app.nativeapi.OnchainAddress
import cash.pyx.app.nativeapi.OnchainAddresses
import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Client-scoped reads and mutations for the on-chain address-history screen. */
interface OnchainAddressRepository {
    suspend fun addresses(clientHandle: Long): NativeResult<OnchainAddresses>
    suspend fun recheck(clientHandle: Long, tweakIndex: Long): NativeResult<AddressRechecked>
}

data class OnchainAddressState(
    val clientHandle: Long? = null,
    val addresses: List<OnchainAddress> = emptyList(),
    val initialized: Boolean = false,
    val loading: Boolean = false,
    val recheckingTweakIndex: Long? = null,
    val error: AndroidError? = null,
)

/**
 * Prevents an address or post-mutation refresh owned by one federation client
 * from becoming visible after another client is selected.
 */
class OnchainAddressStateOwner(
    private val repository: OnchainAddressRepository,
    private val scope: CoroutineScope,
) : Closeable {
    private val mutableState = MutableStateFlow(OnchainAddressState())
    val state: StateFlow<OnchainAddressState> = mutableState.asStateFlow()

    private var loadGeneration = 0L
    private var mutationGeneration = 0L
    private var loadJob: Job? = null
    private var mutationJob: Job? = null
    private var closed = false

    /** Rebinding is synchronous so the previous federation's addresses disappear immediately. */
    fun selectClient(clientHandle: Long?) {
        if (closed || mutableState.value.clientHandle == clientHandle) return
        loadGeneration++
        mutationGeneration++
        loadJob?.cancel()
        mutationJob?.cancel()
        loadJob = null
        mutationJob = null
        mutableState.value = OnchainAddressState(clientHandle = clientHandle)
    }

    fun load(
        force: Boolean = false,
        completed: (NativeResult<OnchainAddresses>) -> Unit = {},
    ): Job? {
        if (closed) return null
        val current = mutableState.value
        val client = current.clientHandle?.takeIf { it > 0 } ?: return null
        if (!force && loadJob?.isActive == true) return null
        if (force) loadJob?.cancel()
        val generation = ++loadGeneration
        mutableState.value = current.copy(loading = true, error = null)
        return scope.launch {
            try {
                when (val result = repository.addresses(client)) {
                    is NativeResult.Success -> if (ownsLoad(client, generation)) {
                        mutableState.value = mutableState.value.copy(
                            addresses = result.value.addresses,
                            initialized = true,
                            loading = false,
                            error = null,
                        )
                        completed(result)
                    }
                    is NativeResult.Failure -> if (ownsLoad(client, generation)) {
                        mutableState.value = mutableState.value.copy(loading = false, error = result.error)
                        completed(result)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (ownsLoad(client, generation)) {
                    val result = NativeResult.Failure(UNEXPECTED_ERROR)
                    mutableState.value = mutableState.value.copy(loading = false, error = result.error)
                    completed(result)
                }
            } finally {
                if (ownsLoad(client, generation)) loadJob = null
            }
        }.also { loadJob = it }
    }

    fun recheck(
        tweakIndex: Long,
        completed: (NativeResult<AddressRechecked>) -> Unit = {},
    ): Job? {
        if (closed || tweakIndex < 0 || mutationJob?.isActive == true) return null
        val client = mutableState.value.clientHandle?.takeIf { it > 0 } ?: return null
        val generation = ++mutationGeneration
        mutableState.value = mutableState.value.copy(recheckingTweakIndex = tweakIndex, error = null)
        return scope.launch {
            try {
                when (val result = repository.recheck(client, tweakIndex)) {
                    is NativeResult.Success -> if (ownsMutation(client, generation)) {
                        mutableState.value = mutableState.value.copy(recheckingTweakIndex = null)
                        completed(result)
                        // The mutation response contains no address snapshot. Refresh under the
                        // same client ownership; a later client switch invalidates this request.
                        load(force = true)
                    }
                    is NativeResult.Failure -> if (ownsMutation(client, generation)) {
                        mutableState.value = mutableState.value.copy(recheckingTweakIndex = null, error = result.error)
                        completed(result)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (ownsMutation(client, generation)) {
                    val result = NativeResult.Failure(UNEXPECTED_ERROR)
                    mutableState.value = mutableState.value.copy(recheckingTweakIndex = null, error = result.error)
                    completed(result)
                }
            } finally {
                if (ownsMutation(client, generation)) mutationJob = null
            }
        }.also { mutationJob = it }
    }

    /** Refresh after address generation, but only if [clientHandle] is still selected. */
    fun refreshAfterMutation(clientHandle: Long) {
        if (!closed && mutableState.value.clientHandle == clientHandle) load(force = true)
    }

    private fun ownsLoad(client: Long, generation: Long) =
        !closed && loadGeneration == generation && mutableState.value.clientHandle == client

    private fun ownsMutation(client: Long, generation: Long) =
        !closed && mutationGeneration == generation && mutableState.value.clientHandle == client

    override fun close() {
        if (closed) return
        closed = true
        loadGeneration++
        mutationGeneration++
        loadJob?.cancel()
        mutationJob?.cancel()
        loadJob = null
        mutationJob = null
    }

    private companion object {
        val UNEXPECTED_ERROR = AndroidError(
            "address_unavailable",
            "On-chain addresses are temporarily unavailable.",
            true,
        )
    }
}
