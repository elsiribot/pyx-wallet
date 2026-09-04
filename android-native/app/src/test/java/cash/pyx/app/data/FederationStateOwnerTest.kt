package cash.pyx.app.data

import cash.pyx.app.nativeapi.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FederationStateOwnerTest {
    @Test fun `stale selection cannot replace newer client and reads use selected client ownership`() = runTest {
        val repository = FakeFederationRepository().apply { suspendSelections = true }
        val owner = FederationStateOwner(repository, backgroundScope)

        owner.select(7, "old"); runCurrent()
        owner.select(7, "new"); runCurrent()
        repository.selection("new").complete(NativeResult.Success(wallet("new", 22)))
        runCurrent()
        repository.selection("old").complete(NativeResult.Success(wallet("old", 11)))
        runCurrent()

        val ready = owner.state.value as FederationState.Ready
        assertEquals("new", ready.snapshot.selected?.federationId)
        assertEquals(listOf(22L), repository.detailClients)
        assertEquals(listOf(22L), repository.connectionClients)
    }

    @Test fun `details completes independently while connection poll is hung`() = runTest {
        val repository = FakeFederationRepository().apply { suspendConnection = true }
        val owner = FederationStateOwner(repository, backgroundScope)
        owner.select(7, "fed"); runCurrent()

        val ready = owner.state.value as FederationState.Ready
        assertEquals("fed details", ready.details?.name)
        assertNull(ready.connection)
    }

    @Test fun `newer live connection wins over stale poll completion`() = runTest {
        val repository = FakeFederationRepository().apply { suspendConnection = true }
        val owner = FederationStateOwner(repository, backgroundScope)
        owner.select(7, "fed"); runCurrent()
        repository.live.emit(connection(ConnectionState.CONNECTED)); runCurrent()
        repository.connectionGate.complete(NativeResult.Success(connection(ConnectionState.OFFLINE))); runCurrent()

        assertEquals(ConnectionState.CONNECTED, (owner.state.value as FederationState.Ready).connection?.state)
    }

    @Test fun `leave reconciliation classifies all conclusive and unknown outcomes`() = runTest {
        suspend fun outcome(native: NativeResult<FederationLeft>, snapshot: NativeResult<WalletSnapshot>): FederationMutation {
            val repository = FakeFederationRepository().apply { leaveResult = native; snapshotResult = snapshot }
            val owner = FederationStateOwner(repository, backgroundScope)
            owner.leave(7, "fed"); runCurrent()
            return owner.mutation.value
        }
        val failed = NativeResult.Failure(AndroidError("offline", "Offline.", true))
        assertTrue(outcome(NativeResult.Success(FederationLeft(true)), NativeResult.Success(wallet(null, null))) is FederationMutation.Left)
        val reconciled = outcome(failed, NativeResult.Success(wallet(null, null))) as FederationMutation.Left
        assertTrue(reconciled.reconciledAfterError)
        assertTrue(outcome(failed, NativeResult.Success(wallet("fed", 9))) is FederationMutation.Failed)
        assertTrue(outcome(NativeResult.Success(FederationLeft(true)), NativeResult.Success(wallet("fed", 9))) is FederationMutation.Unknown)
        assertTrue(outcome(failed, NativeResult.Failure(AndroidError("offline", "Offline.", true))) is FederationMutation.Unknown)
    }

    @Test fun `cancelled leave ignores non cooperative late reconciliation`() = runTest {
        val repository = FakeFederationRepository().apply { suspendSnapshot = true }
        val owner = FederationStateOwner(repository, backgroundScope)
        owner.leave(7, "fed"); runCurrent()
        owner.cancelMutation(); runCurrent()
        repository.snapshotGate.complete(NativeResult.Success(wallet(null, null))); runCurrent()

        assertTrue(repository.snapshotCancelled)
        assertTrue(owner.mutation.value is FederationMutation.Unknown)
    }

    @Test fun `join and recover remain distinct repository operations`() = runTest {
        val repository = FakeFederationRepository()
        val owner = FederationStateOwner(repository, backgroundScope)
        owner.join(7, "join-invite"); runCurrent()
        assertEquals(listOf(7L to "join-invite"), repository.joinCalls)
        assertTrue((owner.mutation.value as FederationMutation.Joined).recovered.not())

        owner.recover(7, "recovery-invite"); runCurrent()
        assertEquals(listOf(7L to "recovery-invite"), repository.recoverCalls)
        assertTrue((owner.mutation.value as FederationMutation.Joined).recovered)
    }

    @Test fun `selection callback rebinding does not invalidate owned live reads`() = runTest {
        val repository = FakeFederationRepository()
        val owner = FederationStateOwner(repository, backgroundScope)
        owner.select(7, "fed") { result ->
            if (result is NativeResult.Success) owner.observe(result.value)
        }
        runCurrent()
        repository.live.emit(connection(ConnectionState.CONNECTED)); runCurrent()

        assertEquals(ConnectionState.CONNECTED, (owner.state.value as FederationState.Ready).connection?.state)
        assertEquals(1, repository.liveCollectors)
    }

    @Test fun `same client observation after pause recreates live collector`() = runTest {
        val repository = FakeFederationRepository()
        val owner = FederationStateOwner(repository, backgroundScope)
        val snapshot = wallet("fed", 9)
        owner.observe(snapshot); runCurrent()
        owner.pause(); runCurrent()
        owner.observe(snapshot); runCurrent()
        repository.live.emit(connection(ConnectionState.CONNECTED)); runCurrent()

        assertEquals(2, repository.liveCollectors)
        assertEquals(ConnectionState.CONNECTED, (owner.state.value as FederationState.Ready).connection?.state)
    }

    @Test fun `live stream failure is retained as retryable typed error`() = runTest {
        val repository = FakeFederationRepository().apply { failLive = true }
        val owner = FederationStateOwner(repository, backgroundScope)
        owner.observe(wallet("fed", 9)); runCurrent()

        val error = (owner.state.value as FederationState.Ready).connectionError
        assertEquals("connection_stream_failed", error?.code)
        assertTrue(error?.retryable == true)
    }

    private class FakeFederationRepository : FederationRepository {
        private val selections = mutableMapOf<String, CompletableDeferred<NativeResult<WalletSnapshot>>>()
        var suspendSelections = false
        var suspendConnection = false
        var suspendSnapshot = false
        var snapshotCancelled = false
        val connectionGate = CompletableDeferred<NativeResult<GuardianConnectionSnapshot>>()
        val snapshotGate = CompletableDeferred<NativeResult<WalletSnapshot>>()
        val live = MutableSharedFlow<GuardianConnectionSnapshot>(extraBufferCapacity = 2)
        var liveCollectors = 0
        var failLive = false
        val detailClients = mutableListOf<Long>()
        val connectionClients = mutableListOf<Long>()
        val joinCalls = mutableListOf<Pair<Long, String>>()
        val recoverCalls = mutableListOf<Pair<Long, String>>()
        var leaveResult: NativeResult<FederationLeft> = NativeResult.Success(FederationLeft(true))
        var snapshotResult: NativeResult<WalletSnapshot> = NativeResult.Success(wallet(null, null))

        fun selection(id: String) = selections.getOrPut(id) { CompletableDeferred() }
        override suspend fun select(factoryHandle: Long, federationId: String): NativeResult<WalletSnapshot> =
            if (suspendSelections) try { selection(federationId).await() } catch (_: CancellationException) {
                withContext(NonCancellable) { selection(federationId).await() }
            } else NativeResult.Success(wallet(federationId, if (federationId == "new") 22 else 9))
        override suspend fun snapshot(factoryHandle: Long): NativeResult<WalletSnapshot> {
            if (suspendSnapshot) try { return snapshotGate.await() } catch (_: CancellationException) {
                snapshotCancelled = true
                return withContext(NonCancellable) { snapshotGate.await() }
            }
            return snapshotResult
        }
        override suspend fun connection(clientHandle: Long): NativeResult<GuardianConnectionSnapshot> {
            connectionClients += clientHandle
            return if (suspendConnection) connectionGate.await() else NativeResult.Success(connection(ConnectionState.DEGRADED))
        }
        override fun connectionEvents(clientHandle: Long): Flow<GuardianConnectionSnapshot> = flow {
            liveCollectors++
            if (failLive) error("stream failed")
            live.collect { emit(it) }
        }
        override suspend fun details(clientHandle: Long): NativeResult<FederationDetails> {
            detailClients += clientHandle
            return NativeResult.Success(FederationDetails("fed details", "fed", "EUR", null))
        }
        override suspend fun leave(factoryHandle: Long, federationId: String) = leaveResult
        override suspend fun join(factoryHandle: Long, invite: String): NativeResult<WalletSnapshot> {
            joinCalls += factoryHandle to invite
            return NativeResult.Success(wallet("joined", 31))
        }
        override suspend fun recover(factoryHandle: Long, invite: String): NativeResult<WalletSnapshot> {
            recoverCalls += factoryHandle to invite
            return NativeResult.Success(wallet("recovered", 32))
        }
    }

    private companion object {
        fun wallet(id: String?, client: Long?): WalletSnapshot = WalletSnapshot(
            "EUR",
            if (id == null) emptyList() else listOf(FederationSummary(id, id, 1)),
            if (id == null || client == null) null else SelectedWallet(client, id, id, 100, emptyList()),
        )
        fun connection(state: ConnectionState) = GuardianConnectionSnapshot(
            emptyList(), if (state == ConnectionState.CONNECTED) 1 else 0, 1, 1, state,
        )
    }
}
