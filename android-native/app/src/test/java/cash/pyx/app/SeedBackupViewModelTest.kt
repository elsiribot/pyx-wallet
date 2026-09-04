package cash.pyx.app

import cash.pyx.app.data.*
import cash.pyx.app.nativeapi.*
import cash.pyx.app.security.SeedBackupState
import cash.pyx.app.ui.WalletBootstrapViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class SeedBackupViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun cleanup() = Dispatchers.resetMain()

    @Test fun pendingSurvivesRecreationAndBlocksHomeUntilAcknowledged() = runTest(dispatcher) {
        val pending = MemorySeedState(true)
        val api = SeedApi()
        val first = viewModel(api, pending)
        advanceUntilIdle()
        assertTrue(first.state.value is BootstrapState.SeedConfirmation)

        val recreated = viewModel(api, pending)
        advanceUntilIdle()
        assertTrue(recreated.state.value is BootstrapState.SeedConfirmation)
        recreated.acknowledgeSeed(9)
        advanceUntilIdle()
        assertFalse(pending.pending)
        assertTrue(recreated.state.value is BootstrapState.Home)
    }

    @Test fun backgroundRedactsSeedWordsAndResumeRefetchesOnlyWhilePending() = runTest(dispatcher) {
        val pending = MemorySeedState(true)
        val api = SeedApi()
        val viewModel = viewModel(api, pending)
        advanceUntilIdle()
        assertTrue((viewModel.state.value as BootstrapState.SeedConfirmation).words.any { it.startsWith("word") })

        viewModel.setResumed(false)
        assertTrue(viewModel.state.value is BootstrapState.Loading)
        assertFalse(viewModel.state.value.toString().contains("word0"))

        viewModel.setResumed(true)
        advanceUntilIdle()
        assertTrue(viewModel.state.value is BootstrapState.SeedConfirmation)
        assertTrue(pending.pending)
    }

    @Test fun acknowledgedSeedIsNotRefetchedAfterBackgroundResume() = runTest(dispatcher) {
        val pending = MemorySeedState(true)
        val api = SeedApi()
        val viewModel = viewModel(api, pending)
        advanceUntilIdle()
        viewModel.acknowledgeSeed(9)
        advanceUntilIdle()
        assertFalse(pending.pending)
        val reads = api.seedReads

        viewModel.setResumed(false)
        viewModel.setResumed(true)
        runCurrent()

        assertTrue(viewModel.state.value is BootstrapState.Home)
        assertEquals(reads, api.seedReads)
        viewModel.setResumed(false)
    }

    @Test fun createMarksPendingBeforeNativeCall() = runTest(dispatcher) {
        val pending = MemorySeedState(false)
        val api = SeedApi(initialized = false, onCreate = { assertTrue(pending.pending) })
        val viewModel = viewModel(api, pending)
        advanceUntilIdle()
        viewModel.createWallet(7)
        advanceUntilIdle()
        assertTrue(viewModel.state.value is BootstrapState.SeedConfirmation)
        assertTrue(pending.pending)
    }

    @Test fun duplicateCreateTapStartsOnlyOneCreate() = runTest(dispatcher) {
        val api = SeedApi(initialized = false)
        val viewModel = viewModel(api, MemorySeedState(false))
        advanceUntilIdle()
        viewModel.createWallet(7)
        viewModel.createWallet(7)
        advanceUntilIdle()
        assertEquals(1, api.createCalls)
        assertTrue(viewModel.state.value is BootstrapState.SeedConfirmation)
    }

    @Test fun liveRefreshStartsOnlyWhenResumedAndKeepsHomeVisible() = runTest(dispatcher) {
        val api = SeedApi()
        val viewModel = viewModel(api, MemorySeedState(false))
        advanceUntilIdle()
        assertTrue(viewModel.state.value is BootstrapState.Home)
        val initial = api.snapshotCalls
        advanceTimeBy(20_000)
        assertEquals(initial, api.snapshotCalls)
        viewModel.setResumed(true)
        advanceTimeBy(10_001)
        assertTrue(api.snapshotCalls > initial)
        assertTrue(viewModel.state.value is BootstrapState.Home)
        viewModel.setResumed(false)
        val paused = api.snapshotCalls
        advanceTimeBy(20_000)
        assertEquals(paused, api.snapshotCalls)
    }

    @Test fun generationSuppressesLateRefreshAfterPause() = runTest(dispatcher) {
        val api = SeedApi()
        lateinit var viewModel: WalletBootstrapViewModel
        api.onRefreshSnapshot = { viewModel.setResumed(false) }
        viewModel = viewModel(api, MemorySeedState(false))
        advanceUntilIdle()
        viewModel.setResumed(true)
        advanceTimeBy(10_001)
        assertEquals("EUR", (viewModel.state.value as BootstrapState.Home).snapshot.currencyCode)
    }

    private fun viewModel(api: NativeWalletApi, pending: SeedBackupState) = WalletBootstrapViewModel(
        WalletBootstrapRepository(api, "/files"), dispatcher, pending,
    )
    private class MemorySeedState(var pending: Boolean) : SeedBackupState {
        override suspend fun isPending() = pending
        override suspend fun setPending(value: Boolean) { pending = value }
    }

    private class SeedApi(var initialized: Boolean = true, val onCreate: () -> Unit = {}) : NativeWalletApi {
        private val snapshot = WalletSnapshot("EUR", emptyList(), null)
        var snapshotCalls = 0
        var createCalls = 0
        var seedReads = 0
        var onRefreshSnapshot: (() -> Unit)? = null
        override suspend fun bootstrapAsync(filesDir: String) = NativeResult.Success<BootstrapSession>(if (initialized) BootstrapSession.Ready("1", 7, 9) else BootstrapSession.Uninitialized("1", 7))
        override suspend fun createWalletAsync(databaseHandle: Long): NativeResult<CreatedWallet> { createCalls++; onCreate(); initialized = true; return NativeResult.Success(CreatedWallet(9, List(12) { "word$it" })) }
        override suspend fun seedWordsAsync(factoryHandle: Long): NativeResult<SeedWords> {
            seedReads++
            return NativeResult.Success(SeedWords(List(12) { "word$it" }))
        }
        override suspend fun walletSnapshotAsync(factoryHandle: Long): NativeResult<WalletSnapshot> {
            snapshotCalls++
            if (snapshotCalls > 1) onRefreshSnapshot?.invoke()
            return NativeResult.Success(if (snapshotCalls > 1) snapshot.copy(currencyCode = "USD") else snapshot)
        }
        override fun listFiatCurrencies() = error("unused")
        override fun classifyInput(payload: String) = error("unused")
        override fun closeTransientHandle(handle: Long, kind: String) = NativeResult.Success(TransientClosed(true))
    }
}
