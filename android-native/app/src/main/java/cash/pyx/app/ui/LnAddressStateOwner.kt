package cash.pyx.app.ui

import cash.pyx.app.nativeapi.LnAddress
import cash.pyx.app.nativeapi.LnaddrMutation
import cash.pyx.app.nativeapi.LnaddrServer
import cash.pyx.app.nativeapi.NativeResult
import cash.pyx.app.nativeapi.NativeWalletApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LnAddressState(
    val addresses: List<LnAddress> = emptyList(),
    val servers: List<LnaddrServer> = emptyList(),
    val loading: Boolean = false,
    val message: String? = null,
)

/**
 * Owns lightning-address reads (snapshot + discovered servers) and the claim / primary /
 * release / repoint mutations that act on them.
 *
 * Refreshes are stale-while-revalidate: the previous [LnAddressState.addresses] and
 * [LnAddressState.servers] stay visible while a new refresh is in flight, and a discovery
 * failure alone never wipes a previously-discovered server list — only a successful
 * discovery replaces it. A later refresh's [refreshGeneration] wins over an earlier one
 * that completes out of order.
 */
class LnAddressStateOwner(
    private val api: NativeWalletApi,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(LnAddressState())
    val state: StateFlow<LnAddressState> = mutableState.asStateFlow()

    private var refreshGeneration = 0L

    fun refresh(factoryHandle: Long) {
        val generation = ++refreshGeneration
        // A new refresh supersedes whatever message an earlier operation left behind — it's
        // about to establish the current truth, so a stale error shouldn't outlive it.
        mutableState.update { it.copy(loading = true, message = null) }
        scope.launch {
            val outcome = try {
                coroutineScope {
                    val snapshotDeferred = async { api.lnaddrSnapshotAsync(factoryHandle) }
                    val discoveryDeferred = async { api.lnaddrDiscoverAsync(factoryHandle) }
                    Pair(snapshotDeferred.await(), discoveryDeferred.await())
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (refreshGeneration != generation) return@launch
            if (outcome == null) {
                mutableState.update { it.copy(loading = false, message = UNEXPECTED_MESSAGE) }
                return@launch
            }
            val (snapshotResult, discoveryResult) = outcome
            mutableState.update { current ->
                var next = when (snapshotResult) {
                    is NativeResult.Success -> current.copy(addresses = snapshotResult.value.addresses, message = null)
                    is NativeResult.Failure ->
                        current.copy(message = LnaddrClaimPresentation.clockHint(snapshotResult.error.userMessage))
                }
                next = when (discoveryResult) {
                    is NativeResult.Success -> next.copy(servers = discoveryResult.value.servers)
                    // Discovery is supplementary: keep whatever server list (previous or default)
                    // is already shown rather than blanking it out over a transient failure.
                    is NativeResult.Failure -> next
                }
                next.copy(loading = false)
            }
        }
    }

    /** Recovers claimed addresses from the server. A success refreshes so the recovered
     * addresses become visible; a failure means nothing changed remotely, so there is nothing
     * to refresh — it only sets the message (clock-hinted where applicable, e.g. an
     * unauthorized 401 from a device clock that has drifted), and that message must stay
     * visible rather than being wiped by a refresh the failure didn't earn. */
    fun recover(factoryHandle: Long) {
        scope.launch {
            when (val result = api.lnaddrRecoverAsync(factoryHandle)) {
                is NativeResult.Success -> refresh(factoryHandle)
                is NativeResult.Failure -> mutableState.update {
                    it.copy(message = LnaddrClaimPresentation.clockHint(result.error.userMessage))
                }
            }
        }
    }

    fun claim(
        clientHandle: Long,
        factoryHandle: Long,
        origin: String,
        domain: String,
        username: String,
        onDone: (Boolean) -> Unit,
    ) {
        scope.launch {
            when (val result = api.lnaddrClaimAsync(clientHandle, origin, domain, username)) {
                is NativeResult.Success -> {
                    refresh(factoryHandle)
                    onDone(true)
                }
                is NativeResult.Failure -> {
                    mutableState.update {
                        it.copy(message = LnaddrClaimPresentation.clockHint(result.error.userMessage))
                    }
                    onDone(false)
                }
            }
        }
    }

    /** Checks whether [username]@[domain] is claimable on the server at [origin]. Pure
     * request/response — no state mutation — so the claim sheet can call it directly from
     * a debounced `LaunchedEffect` without going through `scope.launch`. */
    suspend fun quote(factoryHandle: Long, origin: String, domain: String, username: String): ClaimCheck =
        when (val result = api.lnaddrQuoteAsync(factoryHandle, origin, domain, username)) {
            is NativeResult.Success -> LnaddrClaimPresentation.checkFromQuote(result.value)
            is NativeResult.Failure -> ClaimCheck.Error(LnaddrClaimPresentation.clockHint(result.error.userMessage))
        }

    fun setPrimary(factoryHandle: Long, a: LnAddress) = mutate(factoryHandle) {
        api.lnaddrSetPrimaryAsync(factoryHandle, a.domain, a.username)
    }

    fun release(factoryHandle: Long, a: LnAddress) = mutate(factoryHandle) {
        api.lnaddrReleaseAsync(factoryHandle, a.domain, a.username)
    }

    fun repoint(clientHandle: Long, factoryHandle: Long, a: LnAddress) = mutate(factoryHandle) {
        api.lnaddrRepointAsync(clientHandle, a.domain, a.username)
    }

    /** The primary address within [federationId], or null if there isn't one (including when
     * [federationId] itself is null — an address with no federation has no "primary within it"). */
    fun primaryFor(federationId: String?): LnAddress? {
        if (federationId == null) return null
        return mutableState.value.addresses.firstOrNull { it.federationId == federationId && it.isPrimary }
    }

    fun clearMessage() {
        mutableState.update { it.copy(message = null) }
    }

    private fun mutate(factoryHandle: Long, call: suspend () -> NativeResult<LnaddrMutation>) {
        scope.launch {
            when (val result = call()) {
                is NativeResult.Success -> refresh(factoryHandle)
                is NativeResult.Failure -> mutableState.update {
                    it.copy(message = LnaddrClaimPresentation.clockHint(result.error.userMessage))
                }
            }
        }
    }

    private companion object {
        const val UNEXPECTED_MESSAGE = "Lightning addresses are temporarily unavailable."
    }
}
