package cash.pyx.app.ui

import cash.pyx.app.data.BootstrapState
import cash.pyx.app.data.WalletBootstrapRepository
import cash.pyx.app.data.WalletSubscriptionBindings
import cash.pyx.app.nativeapi.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WalletLifecycleRegressionTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `recovery subscription is closed on stop and recreated with fresh callback`() = runTest(dispatcher) {
        val api = LifecycleApi()
        val subscriptions = RecordingSubscriptions()
        val first = viewModel(api, subscriptions)
        runCurrent()

        first.setResumed(true)
        runCurrent()
        val firstRecovery = subscriptions.latestRecovery()
        firstRecovery.callback.onEvent(firstRecovery.handle, recoveryJson(1))
        runCurrent()
        assertEquals(1L, first.recovery.value?.complete)

        first.setResumed(false)
        runCurrent()
        assertTrue(subscriptions.closed.containsAll(firstRecovery.generationHandles))

        val recreated = viewModel(api, subscriptions)
        runCurrent()
        recreated.setResumed(true)
        runCurrent()
        val secondRecovery = subscriptions.latestRecovery()
        assertTrue(secondRecovery.handle > firstRecovery.handle)
        secondRecovery.callback.onEvent(secondRecovery.handle, recoveryJson(2))
        runCurrent()

        assertEquals(1L, first.recovery.value?.complete)
        assertEquals(2L, recreated.recovery.value?.complete)
        assertFalse(subscriptions.closed.contains(secondRecovery.handle))
        recreated.setResumed(false)
        runCurrent()
    }

    @Test fun `clear operation closes displayed quote and cancels in-flight LNURL quote with its session`() = runTest(dispatcher) {
        val api = LifecycleApi()
        val viewModel = viewModel(api, RecordingSubscriptions())
        runCurrent()

        viewModel.prepareLightning(9, "lnbc1invoice")
        runCurrent()
        assertTrue(viewModel.operation.value is WalletOperation.LightningPrepared)
        viewModel.clearOperation()
        runCurrent()
        assertTrue(71L to "quote" in api.closedTransient)

        viewModel.prepareLnurl("lnurl1request")
        runCurrent()
        val session = viewModel.operation.value as WalletOperation.LnurlPrepared
        api.suspendLnurlQuote = true
        viewModel.prepareLnurlQuote(9, session.sessionHandle, 100)
        runCurrent()
        assertTrue(api.lnurlQuoteStarted)

        viewModel.clearOperation() // Mirrors PyxApp's ON_STOP callback.
        runCurrent()
        assertTrue(api.lnurlQuoteCancelled)
        assertTrue(session.sessionHandle to "lnurl" in api.closedTransient)
        assertTrue(viewModel.operation.value is WalletOperation.Idle)
    }

    @Test fun `leave failure after native mutation reconciles as success from fresh snapshot`() = runTest(dispatcher) {
        val api = LifecycleApi()
        val viewModel = viewModel(api, RecordingSubscriptions())
        runCurrent()
        assertTrue((viewModel.state.value as BootstrapState.Home).snapshot.federations.any { it.id == "fed-a" })

        viewModel.leave(7, "fed-a")
        runCurrent()

        assertTrue(api.leaveMutatedBeforeFailure)
        assertTrue((viewModel.state.value as BootstrapState.Home).snapshot.federations.none { it.id == "fed-a" })
        assertEquals("Federation left", (viewModel.operation.value as WalletOperation.Success).title)
    }

    @Test fun `background clear suppresses a late non cooperative seed backup result`() = runTest(dispatcher) {
        val api = LifecycleApi().apply { suspendSeedBackup = true }
        val viewModel = viewModel(api, RecordingSubscriptions())
        runCurrent()

        viewModel.backup(7)
        runCurrent()
        assertTrue(api.seedBackupStarted)
        assertTrue(viewModel.operation.value is WalletOperation.Submitting)

        viewModel.clearOperation() // Mirrors the Activity ON_STOP secret clear.
        runCurrent()

        assertTrue(api.seedBackupObservedCancellation)
        assertTrue(viewModel.operation.value is WalletOperation.Idle)
        assertFalse(viewModel.operation.value is WalletOperation.Success)
    }

    @Test fun `receive failures and invoice expiry stay explicit`() = runTest(dispatcher) {
        val api = LifecycleApi()
        val viewModel = viewModel(api, RecordingSubscriptions())
        runCurrent()

        api.receiveLightningResult = NativeResult.Failure(AndroidError("invoice_failed", "Invoice generation failed.", true))
        viewModel.receiveLightning(9, 100)
        runCurrent()
        assertEquals("Invoice generation failed.", (viewModel.operation.value as WalletOperation.Failure).message)

        api.receiveLightningResult = NativeResult.Success(LightningReceive("lnbc1opaque", 100, 1, "https://gateway", 1))
        viewModel.clearOperation()
        viewModel.receiveLightning(9, 100)
        runCurrent()
        assertEquals(1L, (viewModel.operation.value as WalletOperation.Success).expiresAtEpochSeconds)

        api.receiveLnurlResult = NativeResult.Failure(AndroidError("unsupported", "LNURL receive is unavailable for this federation.", false))
        viewModel.clearOperation()
        viewModel.receiveLnurl(9)
        runCurrent()
        assertEquals("LNURL receive is unavailable for this federation.", (viewModel.operation.value as WalletOperation.Failure).message)

        api.receiveOnchainResult = NativeResult.Failure(AndroidError("address_failed", "Address generation failed.", true))
        viewModel.clearOperation()
        viewModel.receiveOnchain(9)
        runCurrent()
        assertEquals("Address generation failed.", (viewModel.operation.value as WalletOperation.Failure).message)
    }

    @Test fun `background suppresses late receive result`() = runTest(dispatcher) {
        val api = LifecycleApi().apply { suspendLightningReceive = true }
        val viewModel = viewModel(api, RecordingSubscriptions())
        runCurrent()

        viewModel.receiveLightning(9, 100)
        runCurrent()
        assertTrue(api.lightningReceiveStarted)
        viewModel.clearOperation() // Mirrors ON_STOP and Activity recreation.
        runCurrent()

        assertTrue(api.lightningReceiveObservedCancellation)
        assertTrue(viewModel.operation.value is WalletOperation.Idle)
    }

    @Test fun `animated ecash accepts shuffled frames and suppresses duplicate terminal token`() = runTest(dispatcher) {
        val api = LifecycleApi()
        val viewModel = viewModel(api, RecordingSubscriptions())
        runCurrent()

        viewModel.feedEcashFrame("fedimint1part-b")
        viewModel.feedEcashFrame("fedimint1part-a")
        viewModel.feedEcashFrame("fedimint1part-b")
        runCurrent()

        assertEquals(listOf("fedimint1part-b", "fedimint1part-a"), api.ecashFrames)
        assertEquals(InputType.ECASH, viewModel.classifiedInput.value?.type)
        viewModel.feedEcashFrame("fedimint1part-a")
        runCurrent()
        assertEquals(2, api.ecashFrames.size)
        assertEquals(1, api.decoderCreations)
    }

    private fun viewModel(api: LifecycleApi, subscriptions: RecordingSubscriptions) = WalletBootstrapViewModel(
        WalletBootstrapRepository(
            api,
            "/files",
            subscriptions,
            CoroutineScope(SupervisorJob() + dispatcher),
        ),
        dispatcher,
    )

    private fun recoveryJson(complete: Long) =
        """{"moduleId":1,"complete":$complete,"total":3,"finished":false,"aggregateComplete":$complete,"aggregateTotal":3,"allFinished":false}"""

    private class LifecycleApi : NativeWalletApi {
        var removed = false
        var leaveMutatedBeforeFailure = false
        var suspendLnurlQuote = false
        var lnurlQuoteStarted = false
        var lnurlQuoteCancelled = false
        var suspendSeedBackup = false
        var seedBackupStarted = false
        var seedBackupObservedCancellation = false
        var suspendLightningReceive = false
        var lightningReceiveStarted = false
        var lightningReceiveObservedCancellation = false
        var receiveLightningResult: NativeResult<LightningReceive> = NativeResult.Success(
            LightningReceive("lnbc1opaque", 100, 1, "https://gateway", 1_800_000_000),
        )
        var receiveLnurlResult: NativeResult<LnurlReceive> = NativeResult.Success(LnurlReceive("lnurl1opaque"))
        var receiveOnchainResult: NativeResult<OnchainReceive> = NativeResult.Success(OnchainReceive("bc1opaque"))
        val ecashFrames = mutableListOf<String>()
        var decoderCreations = 0
        val closedTransient = mutableListOf<Pair<Long, String>>()

        private fun snapshot() = WalletSnapshot(
            "EUR",
            if (removed) emptyList() else listOf(FederationSummary("fed-a", "Federation A", 3)),
            SelectedWallet(9, "fed-a", "Federation A", 500, emptyList()),
        )

        override suspend fun bootstrapAsync(filesDir: String) =
            NativeResult.Success<BootstrapSession>(BootstrapSession.Ready("1", 3, 7))
        override suspend fun walletSnapshotAsync(factoryHandle: Long) = NativeResult.Success(snapshot())
        override suspend fun recoveryExpirySnapshotAsync(clientHandle: Long) = NativeResult.Success(
            RecoveryExpirySnapshot(true, null, false, null),
        )
        override suspend fun prepareLightningSendAsync(clientHandle: Long, invoice: String) = NativeResult.Success(
            LightningQuote(71, 100, 1, "https://gateway", false),
        )
        override suspend fun receiveLightningAsync(clientHandle: Long, amountSat: Long): NativeResult<LightningReceive> {
            lightningReceiveStarted = true
            if (suspendLightningReceive) try {
                awaitCancellation()
            } catch (_: CancellationException) {
                lightningReceiveObservedCancellation = true
                return NativeResult.Success(LightningReceive("late", amountSat, 1, "https://gateway", 1_800_000_000))
            }
            return receiveLightningResult
        }
        override suspend fun receiveLnurlAsync(clientHandle: Long) = receiveLnurlResult
        override suspend fun receiveOnchainAsync(clientHandle: Long) = receiveOnchainResult
        override fun createEcashDecoder(): NativeResult<EcashCodecHandle> {
            decoderCreations++
            return NativeResult.Success(EcashCodecHandle(91))
        }
        override fun addEcashFragment(handle: Long, fragment: String): NativeResult<EcashDecodeResult> {
            ecashFrames += fragment
            return NativeResult.Success(EcashDecodeResult(ecashFrames.size == 2, if (ecashFrames.size == 2) "fedimint1token" else null))
        }
        override fun closeEcashCodec(handle: Long, kind: String) = NativeResult.Success(EcashCodecClosed(true))
        override suspend fun prepareLnurlAsync(request: String) = NativeResult.Success(LnurlSession(81, 1, 1_000, false))
        override suspend fun seedWordsAsync(factoryHandle: Long): NativeResult<SeedWords> {
            seedBackupStarted = true
            if (suspendSeedBackup) try {
                awaitCancellation()
            } catch (_: CancellationException) {
                seedBackupObservedCancellation = true
                return NativeResult.Success(SeedWords(List(12) { "late-secret-$it" }))
            }
            return NativeResult.Success(SeedWords(List(12) { "word-$it" }))
        }
        override suspend fun prepareLnurlQuoteAsync(clientHandle: Long, sessionHandle: Long, amountSat: Long): NativeResult<LightningQuote> {
            lnurlQuoteStarted = true
            if (suspendLnurlQuote) try {
                awaitCancellation()
            } finally {
                lnurlQuoteCancelled = true
            }
            return NativeResult.Success(LightningQuote(82, amountSat, 1, "https://gateway", false))
        }
        override suspend fun leaveFederationAsync(factoryHandle: Long, federationId: String): NativeResult<FederationLeft> {
            removed = true
            leaveMutatedBeforeFailure = true
            return NativeResult.Failure(AndroidError("ambiguous", "Connection was lost.", true))
        }
        override fun closeTransientHandle(handle: Long, kind: String): NativeResult<TransientClosed> {
            closedTransient += handle to kind
            return NativeResult.Success(TransientClosed(true))
        }
        override fun satsToFiat(clientHandle: Long, amountSat: Long): NativeResult<FiatDisplay?> = NativeResult.Success(null)
        override fun listFiatCurrencies(): NativeResult<FiatCurrencies> = error("unused")
        override fun classifyInput(payload: String): NativeResult<InputType> = error("unused")
    }

    private class RecordingSubscriptions : WalletSubscriptionBindings {
        data class Record(val handle: Long, val callback: NativeSubscriptionCallback, val generationHandles: Set<Long>)
        private var nextHandle = 100L
        private var currentGeneration = linkedSetOf<Long>()
        private val recoveries = mutableListOf<Record>()
        val closed = linkedSetOf<Long>()

        override fun subscribeBalance(client: Long, callback: NativeSubscriptionCallback) = allocate()
        override fun subscribeConnection(client: Long, callback: NativeSubscriptionCallback) = allocate()
        override fun subscribePayments(client: Long, callback: NativeSubscriptionCallback) = allocate()
        override fun subscribeRecovery(client: Long, callback: NativeSubscriptionCallback): Long {
            val handle = allocate()
            recoveries += Record(handle, callback, currentGeneration)
            currentGeneration = linkedSetOf()
            return handle
        }
        override fun close(handle: Long) { closed += handle }
        fun latestRecovery() = recoveries.last()
        private fun allocate(): Long = nextHandle++.also(currentGeneration::add)
    }
}
