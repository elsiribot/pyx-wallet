package cash.pyx.app.data

import cash.pyx.app.nativeapi.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

sealed interface BootstrapState {
    data object Loading : BootstrapState
    data class Onboarding(val session: BootstrapSession.Uninitialized) : BootstrapState
    data object Creating : BootstrapState
    data object Restoring : BootstrapState
    data class SeedConfirmation(val factoryHandle: Long, val words: List<String>) : BootstrapState
    data object LoadingWallet : BootstrapState
    data class Home(val factoryHandle: Long, val snapshot: WalletSnapshot) : BootstrapState
    data class Unavailable(val message: String) : BootstrapState
    data class Error(val message: String, val retryable: Boolean) : BootstrapState
}

/**
 * Narrow payment seam used by the send state machine. Keeping this contract
 * separate lets deterministic tests control quote/submission races without a
 * fake JNI layer or a live federation.
 */
interface SendFlowRepository {
    suspend fun prepareLightningAsync(client: Long, invoice: String): NativeResult<LightningQuote>
    suspend fun prepareOnchainAsync(client: Long, address: String, amount: Long): NativeResult<OnchainQuote>
    suspend fun executeLightningAsync(client: Long, quote: Long, correlationId: String): NativeResult<LightningSend>
    suspend fun executeOnchainAsync(client: Long, quote: Long, correlationId: String): NativeResult<OnchainSend>
    suspend fun pendingOperationsAsync(client: Long): NativeResult<PendingNativeOperations>
    suspend fun reconcileOperationAsync(
        client: Long,
        correlationId: String,
        kind: DurableOperationKind,
    ): NativeResult<OperationReconciliationResult>
    suspend fun clearOperationAsync(client: Long, correlationId: String): NativeResult<DurableOperationCleared>
}

/** Receive-only operations, isolated from bootstrap and federation management. */
interface ReceiveRepository {
    suspend fun lightning(client: Long, amountSat: Long): NativeResult<LightningReceive>
    suspend fun onchain(client: Long): NativeResult<OnchainReceive>
    suspend fun lnurl(client: Long): NativeResult<LnurlReceive>
}

/** Recovery operations and the lifecycle-bound native recovery stream. */
interface RecoveryRepository {
    suspend fun restore(databaseHandle: Long, words: List<String>): NativeResult<RestoredWallet>
    suspend fun recoverFederation(factoryHandle: Long, invite: String): NativeResult<WalletSnapshot>
    suspend fun expiry(client: Long): NativeResult<RecoveryExpirySnapshot>
    fun events(client: Long): Flow<RecoveryEvent>
}

/**
 * Narrow seam for the long-lived home snapshot and stream reconciliation loop.
 *
 * Production delegates to [WalletBootstrapRepository]. Tests can control
 * reconnects and polling time without manufacturing JNI callbacks or a live
 * federation client.
 */
interface HomeStreamRepository {
    suspend fun snapshotAsync(factory: Long): NativeResult<WalletSnapshot>
    fun balanceEvents(client: Long): Flow<Long>
    fun paymentEvents(client: Long): Flow<PaymentUpdate>
    fun satsToFiat(client: Long, amount: Long): NativeResult<FiatDisplay?>
}

class WalletBootstrapRepository(
    private val nativeApi: NativeWalletApi,
    private val filesDir: String,
    private val subscriptions: WalletSubscriptionBindings = JniWalletSubscriptionBindings,
    private val subscriptionCleanupScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : Closeable, SendFlowRepository, ReceiveRepository, RecoveryRepository, HomeStreamRepository,
    FederationRepository, ActivityRepository, ContactsRepository, CurrencySettingsRepository, EcashQrRepository,
    OnchainAddressRepository, LnurlRepository, ApplicationBootstrapRepository {
    private val closed = AtomicBoolean(false)
    /** Process/application-owner teardown only. Never call for Activity rotation. */
    suspend fun shutdownAndroidSession() = nativeApi.shutdownAndroidSession()
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            subscriptionCleanupScope.launch { nativeApi.shutdownAndroidSession() }
        }
    }
    override suspend fun load(): BootstrapState = bootstrapAsync()

    suspend fun bootstrapAsync(): BootstrapState = when (val result = nativeApi.bootstrapAsync(filesDir)) {
        is NativeResult.Success -> when (val session = result.value) {
            is BootstrapSession.Uninitialized -> BootstrapState.Onboarding(session)
            is BootstrapSession.Ready -> when (val snapshot = nativeApi.walletSnapshotAsync(session.factoryHandle)) {
                is NativeResult.Success -> BootstrapState.Home(session.factoryHandle, snapshot.value)
                is NativeResult.Failure -> snapshot.toState()
            }
        }
        is NativeResult.Failure -> result.toState()
    }

    private fun NativeResult.Failure.toState(): BootstrapState = when (error.code) {
            "native_library_unavailable", "native_api_unavailable" ->
                BootstrapState.Unavailable(error.userMessage)
            else -> BootstrapState.Error(error.userMessage, error.retryable)
    }

    suspend fun createAsync(databaseHandle: Long) = nativeApi.createWalletAsync(databaseHandle)
    override suspend fun restore(databaseHandle: Long, words: List<String>) = nativeApi.restoreWalletAsync(databaseHandle, words)
    override suspend fun lightning(client: Long, amountSat: Long) = nativeApi.receiveLightningAsync(client, amountSat)
    override suspend fun onchain(client: Long) = nativeApi.receiveOnchainAsync(client)
    suspend fun createEcashAsync(client: Long, amount: Long, correlationId: String) = nativeApi.createEcashAsync(client, amount, correlationId)
    override fun createEncoder(payload: String) = nativeApi.createEcashEncoder(payload)
    override suspend fun classify(payload: String) = nativeApi.classifyInput(payload)
    override fun nextFragment(handle: Long) = nativeApi.nextEcashFragment(handle)
    override fun createDecoder() = nativeApi.createEcashDecoder()
    override fun addFragment(handle: Long, fragment: String) = nativeApi.addEcashFragment(handle, fragment)
    override fun closeCodec(handle: Long, kind: String) = nativeApi.closeEcashCodec(handle, kind)
    suspend fun claimEcashAsync(client: Long, payload: String, correlationId: String) = nativeApi.claimEcashAsync(client, payload, correlationId)
    override suspend fun setCurrency(factoryHandle: Long, code: String) = nativeApi.setCurrencyAsync(factoryHandle, code)
    suspend fun seedWordsAsync(factory: Long) = nativeApi.seedWordsAsync(factory)
    fun seedSuggestions(prefix: String) = nativeApi.seedWordSuggestions(prefix)
    fun validateSeed(words: List<String>) = nativeApi.validateSeedPhrase(words)
    override suspend fun prepareLnurl(request: String) = nativeApi.prepareLnurlAsync(request)
    override suspend fun prepareLnurlQuote(clientHandle: Long, sessionHandle: Long, amountSat: Long) =
        nativeApi.prepareLnurlQuoteAsync(clientHandle, sessionHandle, amountSat)
    override fun closeLnurlHandle(handle: Long) = nativeApi.closeTransientHandle(handle, "lnurl")
    override fun closeQuoteHandle(handle: Long) = nativeApi.closeTransientHandle(handle, "quote")
    override suspend fun listContacts(factoryHandle: Long) = nativeApi.listContactsAsync(factoryHandle)
    override suspend fun saveContact(factoryHandle: Long, lnurl: String, name: String) = nativeApi.saveContactAsync(factoryHandle, lnurl, name)
    override suspend fun deleteContact(factoryHandle: Long, lnurl: String) = nativeApi.deleteContactAsync(factoryHandle, lnurl)
    override suspend fun details(clientHandle: Long, operationId: String) =
        nativeApi.paymentDetailsAsync(clientHandle, operationId)
    override suspend fun page(clientHandle: Long, cursor: String?, pageSize: Int) =
        nativeApi.paymentHistoryPageAsync(clientHandle, cursor, pageSize)
    override fun listFiatCurrencies() = nativeApi.listFiatCurrencies()
    override suspend fun addresses(clientHandle: Long) = nativeApi.onchainAddressesAsync(clientHandle)
    override suspend fun recheck(clientHandle: Long, tweakIndex: Long) =
        nativeApi.recheckOnchainAddressAsync(clientHandle, tweakIndex)
    override suspend fun prepareLightningAsync(client: Long, invoice: String) = nativeApi.prepareLightningSendAsync(client, invoice)
    override suspend fun executeLightningAsync(client: Long, quote: Long, correlationId: String) = nativeApi.executeLightningSendAsync(client, quote, correlationId)
    override suspend fun prepareOnchainAsync(client: Long, address: String, amount: Long) = nativeApi.prepareOnchainSendAsync(client, address, amount)
    override suspend fun executeOnchainAsync(client: Long, quote: Long, correlationId: String) = nativeApi.executeOnchainSendAsync(client, quote, correlationId)
    override suspend fun pendingOperationsAsync(client: Long) = nativeApi.pendingOperationsAsync(client)
    override suspend fun reconcileOperationAsync(client: Long, correlationId: String, kind: DurableOperationKind) = nativeApi.reconcileOperationAsync(client, correlationId, kind)
    override suspend fun clearOperationAsync(client: Long, correlationId: String) = nativeApi.clearOperationAsync(client, correlationId)
    override suspend fun lnurl(client: Long) = nativeApi.receiveLnurlAsync(client)
    fun closeTransient(handle: Long, kind: String) = nativeApi.closeTransientHandle(handle, kind)
    override suspend fun snapshotAsync(factory: Long) = nativeApi.walletSnapshotAsync(factory)
    override suspend fun snapshot(factoryHandle: Long) = nativeApi.walletSnapshotAsync(factoryHandle)
    override suspend fun select(factoryHandle: Long, federationId: String) = nativeApi.walletSnapshotForAsync(factoryHandle, federationId)
    override suspend fun connection(clientHandle: Long) = nativeApi.connectionStatusAsync(clientHandle)
    override suspend fun details(clientHandle: Long) = nativeApi.federationDetailsAsync(clientHandle)
    override suspend fun leave(factoryHandle: Long, federationId: String) = nativeApi.leaveFederationAsync(factoryHandle, federationId)
    override suspend fun join(factoryHandle: Long, invite: String) = nativeApi.joinFederationAsync(factoryHandle, invite, false)
    override suspend fun recover(factoryHandle: Long, invite: String) = nativeApi.joinFederationAsync(factoryHandle, invite, true)
    override fun balanceEvents(client: Long) = subscriptionFlow(
        { callback -> subscriptions.subscribeBalance(client, callback) }, subscriptions::close, SubscriptionDtos::balance, subscriptionCleanupScope,
    )
    override fun connectionEvents(clientHandle: Long) = subscriptionFlow(
        { callback -> subscriptions.subscribeConnection(clientHandle, callback) }, subscriptions::close, SubscriptionDtos::connection, subscriptionCleanupScope,
    )
    override fun events(client: Long) = subscriptionFlow(
        { callback -> subscriptions.subscribeRecovery(client, callback) }, subscriptions::close, SubscriptionDtos::recovery, subscriptionCleanupScope,
    )
    override fun paymentEvents(client: Long) = subscriptionFlow(
        { callback -> subscriptions.subscribePayments(client, callback) }, subscriptions::close, SubscriptionDtos::payments,
        subscriptionCleanupScope, capacity = 16,
    )
    override suspend fun expiry(client: Long) = nativeApi.recoveryExpirySnapshotAsync(client)
    override suspend fun recoverFederation(factoryHandle: Long, invite: String) =
        nativeApi.joinFederationAsync(factoryHandle, invite, true)
    override fun satsToFiat(client: Long, amount: Long) = nativeApi.satsToFiat(client, amount)
    suspend fun fiatToSats(client: Long, amount: String) = nativeApi.fiatToSatsAsync(client, amount)
    fun parseBitcoin(payload: String) = nativeApi.parseBitcoinPayment(payload)
}
