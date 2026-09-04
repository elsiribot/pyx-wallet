package cash.pyx.app.data

import cash.pyx.app.nativeapi.AndroidError
import cash.pyx.app.nativeapi.FederationDetails
import cash.pyx.app.nativeapi.GuardianConnectionSnapshot
import cash.pyx.app.nativeapi.NativeResult
import cash.pyx.app.nativeapi.NativeWalletApi
import cash.pyx.app.nativeapi.WalletSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/** Federation feature boundary. Handles are deliberately client-scoped. */
interface FederationRepository {
    suspend fun snapshot(factoryHandle: Long): NativeResult<WalletSnapshot>
    suspend fun select(factoryHandle: Long, federationId: String): NativeResult<WalletSnapshot>
    suspend fun connection(clientHandle: Long): NativeResult<GuardianConnectionSnapshot>
    fun connectionEvents(clientHandle: Long): Flow<GuardianConnectionSnapshot>
    suspend fun details(clientHandle: Long): NativeResult<FederationDetails>
    suspend fun leave(factoryHandle: Long, federationId: String): NativeResult<*>
    suspend fun join(factoryHandle: Long, invite: String): NativeResult<WalletSnapshot>
    suspend fun recover(factoryHandle: Long, invite: String): NativeResult<WalletSnapshot>
}

/** Production adapter; each connection collector owns and closes its JNI subscription. */
class NativeFederationRepository(
    private val api: NativeWalletApi,
    private val subscriptions: WalletSubscriptionBindings = JniWalletSubscriptionBindings,
    private val cleanupScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : FederationRepository {
    override suspend fun snapshot(factoryHandle: Long) = api.walletSnapshotAsync(factoryHandle)
    override suspend fun select(factoryHandle: Long, federationId: String) = api.walletSnapshotForAsync(factoryHandle, federationId)
    override suspend fun connection(clientHandle: Long) = api.connectionStatusAsync(clientHandle)
    override fun connectionEvents(clientHandle: Long) = subscriptionFlow(
        { callback -> subscriptions.subscribeConnection(clientHandle, callback) },
        subscriptions::close,
        SubscriptionDtos::connection,
        cleanupScope,
    )
    override suspend fun details(clientHandle: Long) = api.federationDetailsAsync(clientHandle)
    override suspend fun leave(factoryHandle: Long, federationId: String) = api.leaveFederationAsync(factoryHandle, federationId)
    override suspend fun join(factoryHandle: Long, invite: String) = api.joinFederationAsync(factoryHandle, invite, false)
    override suspend fun recover(factoryHandle: Long, invite: String) = api.joinFederationAsync(factoryHandle, invite, true)
}

sealed interface FederationState {
    data object Idle : FederationState
    data class Selecting(val federationId: String) : FederationState
    data class Ready(
        val snapshot: WalletSnapshot,
        val details: FederationDetails? = null,
        val detailsError: AndroidError? = null,
        val connection: GuardianConnectionSnapshot? = null,
        val connectionError: AndroidError? = null,
    ) : FederationState
    data class Failed(val error: AndroidError) : FederationState
}

sealed interface FederationMutation {
    data object Idle : FederationMutation
    data class Running(val kind: Kind) : FederationMutation
    data class Joined(val snapshot: WalletSnapshot, val recovered: Boolean) : FederationMutation
    data class Left(val snapshot: WalletSnapshot, val reconciledAfterError: Boolean) : FederationMutation
    data class Failed(val error: AndroidError) : FederationMutation
    data class Unknown(val message: String) : FederationMutation
    enum class Kind { JOIN, RECOVER, LEAVE }
}

/**
 * Generation-guarded state owner for federation screens. Poll reads never
 * overwrite a newer live connection event, and destructive leave is conclusive
 * only after a fresh factory snapshot.
 */
class FederationStateOwner(
    private val repository: FederationRepository,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow<FederationState>(FederationState.Idle)
    val state: StateFlow<FederationState> = mutableState.asStateFlow()
    private val mutableMutation = MutableStateFlow<FederationMutation>(FederationMutation.Idle)
    val mutation: StateFlow<FederationMutation> = mutableMutation.asStateFlow()

    private var selectionGeneration = 0L
    private var mutationGeneration = 0L
    private var connectionRevision = 0L
    private var selectionJob: Job? = null
    private var mutationJob: Job? = null
    private var liveJob: Job? = null

    fun select(factoryHandle: Long, federationId: String, completed: (NativeResult<WalletSnapshot>) -> Unit = {}) {
        val generation = ++selectionGeneration
        selectionJob?.cancel()
        liveJob?.cancel()
        mutableState.value = FederationState.Selecting(federationId)
        selectionJob = scope.launch {
            when (val selected = repository.select(factoryHandle, federationId)) {
                is NativeResult.Failure -> if (generation == selectionGeneration) {
                    mutableState.value = FederationState.Failed(selected.error)
                    completed(selected)
                }
                is NativeResult.Success -> {
                    val wallet = selected.value.selected
                    if (generation != selectionGeneration || wallet == null || wallet.federationId != federationId) return@launch
                    startOwnedReads(selected.value, generation)
                    if (generation == selectionGeneration) completed(selected)
                }
            }
        }
    }

    /** Bind a snapshot already loaded by bootstrap/home polling without selecting it again. */
    fun observe(snapshot: WalletSnapshot) {
        val wallet = snapshot.selected
        if (wallet == null) {
            selectionGeneration++
            selectionJob?.cancel(); liveJob?.cancel()
            mutableState.value = FederationState.Idle
            return
        }
        val current = mutableState.value as? FederationState.Ready
        if (current?.snapshot?.selected?.clientHandle == wallet.clientHandle && liveJob?.isActive == true) {
            mutableState.value = current.copy(snapshot = snapshot)
            return
        }
        val generation = ++selectionGeneration
        selectionJob?.cancel(); liveJob?.cancel()
        selectionJob = scope.launch { startOwnedReads(snapshot, generation) }
    }

    fun pause() {
        selectionGeneration++
        selectionJob?.cancel(); selectionJob = null
        liveJob?.cancel(); liveJob = null
    }

    fun join(factoryHandle: Long, invite: String, completed: (FederationMutation) -> Unit = {}) =
        joinOrRecover(factoryHandle, invite, false, completed)
    fun recover(factoryHandle: Long, invite: String, completed: (FederationMutation) -> Unit = {}) =
        joinOrRecover(factoryHandle, invite, true, completed)

    private fun joinOrRecover(factoryHandle: Long, invite: String, recover: Boolean, completed: (FederationMutation) -> Unit) {
        val generation = ++mutationGeneration
        mutationJob?.cancel()
        mutableMutation.value = FederationMutation.Running(if (recover) FederationMutation.Kind.RECOVER else FederationMutation.Kind.JOIN)
        mutationJob = scope.launch {
            val result = if (recover) repository.recover(factoryHandle, invite) else repository.join(factoryHandle, invite)
            if (generation != mutationGeneration) return@launch
            mutableMutation.value = when (result) {
                is NativeResult.Success -> FederationMutation.Joined(result.value, recover)
                is NativeResult.Failure -> FederationMutation.Failed(result.error)
            }
            completed(mutableMutation.value)
        }
    }

    fun leave(factoryHandle: Long, federationId: String, completed: (FederationMutation) -> Unit = {}) {
        val generation = ++mutationGeneration
        mutationJob?.cancel()
        mutableMutation.value = FederationMutation.Running(FederationMutation.Kind.LEAVE)
        mutationJob = scope.launch {
            val native = repository.leave(factoryHandle, federationId)
            val fresh = repository.snapshot(factoryHandle)
            if (generation != mutationGeneration) return@launch
            mutableMutation.value = when (fresh) {
                is NativeResult.Failure -> FederationMutation.Unknown("Could not confirm whether the federation was removed.")
                is NativeResult.Success -> {
                    val remains = fresh.value.federations.any { it.id == federationId }
                    when {
                        !remains -> FederationMutation.Left(fresh.value, native is NativeResult.Failure)
                        native is NativeResult.Failure -> FederationMutation.Failed(native.error)
                        else -> FederationMutation.Unknown("The leave request succeeded but the federation is still present.")
                    }
                }
            }
            completed(mutableMutation.value)
        }
    }

    fun cancelMutation() {
        mutationGeneration++
        mutationJob?.cancel()
        mutationJob = null
        mutableMutation.value = FederationMutation.Unknown("Operation status is unknown; refresh before retrying.")
    }

    fun close() {
        selectionGeneration++
        mutationGeneration++
        selectionJob?.cancel()
        liveJob?.cancel()
        mutationJob?.cancel()
    }

    private inline fun updateReady(generation: Long, client: Long, update: (FederationState.Ready) -> FederationState.Ready) {
        if (generation != selectionGeneration) return
        val current = mutableState.value as? FederationState.Ready ?: return
        if (current.snapshot.selected?.clientHandle != client) return
        mutableState.value = update(current)
    }

    private suspend fun kotlinx.coroutines.CoroutineScope.startOwnedReads(snapshot: WalletSnapshot, generation: Long) {
        val wallet = snapshot.selected ?: return
        val previous = mutableState.value as? FederationState.Ready
        if (generation != selectionGeneration) return
        mutableState.value = FederationState.Ready(
            snapshot,
            details = previous?.takeIf { it.snapshot.selected?.clientHandle == wallet.clientHandle }?.details,
            connection = previous?.takeIf { it.snapshot.selected?.clientHandle == wallet.clientHandle }?.connection,
        )
        val client = wallet.clientHandle
        liveJob = launch {
            repository.connectionEvents(client).catch {
                updateReady(generation, client) { state -> state.copy(
                    connectionError = AndroidError("connection_stream_failed", "Live connection updates stopped.", true),
                ) }
            }.collect { value ->
                updateReady(generation, client) { connectionRevision++; it.copy(connection = value, connectionError = null) }
            }
        }
        launch {
            val details = repository.details(client)
            updateReady(generation, client) {
                when (details) {
                    is NativeResult.Success -> it.copy(details = details.value, detailsError = null)
                    is NativeResult.Failure -> it.copy(detailsError = details.error)
                }
            }
        }
        launch {
            val revision = connectionRevision
            val connection = repository.connection(client)
            updateReady(generation, client) {
                if (revision != connectionRevision) it else when (connection) {
                    is NativeResult.Success -> it.copy(
                        connection = connection.value,
                        connectionError = it.connectionError?.takeIf { error -> error.code == "connection_stream_failed" },
                    )
                    is NativeResult.Failure -> it.copy(connectionError = connection.error)
                }
            }
        }
    }
}
