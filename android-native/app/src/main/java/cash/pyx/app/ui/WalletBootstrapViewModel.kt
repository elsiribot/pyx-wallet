package cash.pyx.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.pyx.app.data.BootstrapState
import cash.pyx.app.data.WalletBootstrapRepository
import cash.pyx.app.data.SendFlowRepository
import cash.pyx.app.data.ActivityRepository
import cash.pyx.app.data.ActivityStateOwner
import cash.pyx.app.data.ContactsRepository
import cash.pyx.app.data.ContactsStateOwner
import cash.pyx.app.data.CurrencySettingsRepository
import cash.pyx.app.data.CurrencySettingsStateOwner
import cash.pyx.app.data.FederationMutation
import cash.pyx.app.data.FederationRepository
import cash.pyx.app.data.FederationState
import cash.pyx.app.data.FederationStateOwner
import cash.pyx.app.data.EcashQrRepository
import cash.pyx.app.data.EcashQrStateOwner
import cash.pyx.app.data.LnurlRepository
import cash.pyx.app.data.LnurlStateOwner
import cash.pyx.app.data.OnchainAddressRepository
import cash.pyx.app.data.OnchainAddressStateOwner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CancellationException
import cash.pyx.app.nativeapi.NativeResult
import cash.pyx.app.nativeapi.DurableOperationKind
import cash.pyx.app.nativeapi.DurableOperationStatus
import cash.pyx.app.nativeapi.EcashClaimed
import cash.pyx.app.nativeapi.EcashCreated
import cash.pyx.app.security.SeedBackupState
import cash.pyx.app.security.IrreversibleOperationJournal
import cash.pyx.app.security.IrreversibleOperationReconciliation
import cash.pyx.app.security.PendingIrreversibleOperation
import cash.pyx.app.security.secureOperationCorrelationId
import cash.pyx.app.security.ConsumedDeepLink
import cash.pyx.app.security.DeepLinkConsumptionStore
import java.security.MessageDigest

sealed interface WalletOperation {
    data object Idle : WalletOperation
    data object Submitting : WalletOperation
    data class Success(
        val title: String,
        val detail: String,
        val sensitive: Boolean = false,
        val shareable: Boolean = true,
        val expiresAtEpochSeconds: Long? = null,
    ) : WalletOperation
    data class Failure(val message: String) : WalletOperation
    data class LnurlPrepared(val sessionHandle: Long, val minSat: Long, val maxSat: Long, val fixedAmount: Boolean) : WalletOperation
    data class LightningPrepared(val quote: cash.pyx.app.nativeapi.LightningQuote) : WalletOperation
    data class OnchainPrepared(val quote: cash.pyx.app.nativeapi.OnchainQuote) : WalletOperation
    data class FiatConverted(val amountSat: Long, val currencyCode: String) : WalletOperation
    data class SuccessorInvite(val payload: String) : WalletOperation
    data class BitcoinParsed(val destination: String, val payment: cash.pyx.app.nativeapi.BitcoinPayment) : WalletOperation
}
data class PaymentNotice(val identity: String, val message: String)

sealed interface HomeRefreshStatus {
    val lastSuccessEpochMillis: Long?
    data class Fresh(override val lastSuccessEpochMillis: Long) : HomeRefreshStatus
    data class Degraded(override val lastSuccessEpochMillis: Long?, val consecutiveFailures: Int) : HomeRefreshStatus
    data class Offline(override val lastSuccessEpochMillis: Long?, val consecutiveFailures: Int) : HomeRefreshStatus
}

internal object HomeRefreshRetryPolicy {
    const val HEALTHY_REFRESH_MILLIS = 10_000L
    const val MAX_RETRY_MILLIS = 60_000L
    fun delayMillis(consecutiveFailures: Int): Long {
        if (consecutiveFailures <= 0) return HEALTHY_REFRESH_MILLIS
        val shift = (consecutiveFailures - 1).coerceAtMost(6)
        return (1_000L shl shift).coerceAtMost(MAX_RETRY_MILLIS)
    }
}

/** De-duplicates Android's repeated delivery to a singleTop Activity instance. */
internal class IntentConsumptionGate(
    private val store: DeepLinkConsumptionStore = object : DeepLinkConsumptionStore {
        private var value: ConsumedDeepLink? = null
        override fun read() = value
        override fun write(value: ConsumedDeepLink): Boolean { this.value = value; return true }
    },
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    private var lastFingerprint: String? = null

    fun consume(uri: String?): String? {
        // Reject before hashing/persisting: exported deep-link Activities must not perform work
        // proportional to an attacker-controlled oversized URI.
        if (uri.isNullOrBlank() || uri.length > MAX_DEEP_LINK_CHARS) return null
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(uri.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        if (fingerprint == lastFingerprint) return null
        val now = nowEpochMillis()
        val persisted = store.read()
        if (persisted?.fingerprint == fingerprint && now - persisted.consumedAtEpochMillis in 0..DUPLICATE_WINDOW_MILLIS) {
            lastFingerprint = fingerprint
            return null
        }
        if (!store.write(ConsumedDeepLink(fingerprint, now))) return null
        lastFingerprint = fingerprint
        return uri
    }

    private companion object {
        const val DUPLICATE_WINDOW_MILLIS = 10 * 60 * 1_000L
        const val MAX_DEEP_LINK_CHARS = 16 * 1024
    }
}

class WalletBootstrapViewModel internal constructor(
    private val repository: WalletBootstrapRepository,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val seedBackupState: SeedBackupState = object : SeedBackupState {
        private var pending = false
        override suspend fun isPending() = pending
        override suspend fun setPending(value: Boolean) { pending = value }
    },
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val operationReconciliation: IrreversibleOperationReconciliation = IrreversibleOperationReconciliation(
        object : IrreversibleOperationJournal {
            override fun read(): PendingIrreversibleOperation? = null
            override fun begin(operation: PendingIrreversibleOperation) = true
            override fun clear() = true
        },
        nowEpochMillis,
    ),
    private val correlationIdGenerator: () -> String = ::secureOperationCorrelationId,
    private val intentConsumptionGate: IntentConsumptionGate = IntentConsumptionGate(),
    private val sendRepository: SendFlowRepository = repository,
    private val applicationStateOwner: cash.pyx.app.data.WalletApplicationStateOwner = cash.pyx.app.data.WalletApplicationStateOwner(),
    private val homeRepository: cash.pyx.app.data.HomeStreamRepository = repository,
    private val receiveRepository: cash.pyx.app.data.ReceiveRepository = repository,
    private val recoveryRepository: cash.pyx.app.data.RecoveryRepository = repository,
    activityRepository: ActivityRepository = repository,
    contactsRepository: ContactsRepository = repository,
    currencySettingsRepository: CurrencySettingsRepository = repository,
    federationRepository: FederationRepository = repository,
    ecashQrRepository: EcashQrRepository = repository,
    lnurlRepository: LnurlRepository = repository,
    onchainAddressRepository: OnchainAddressRepository = repository,
    private val contactsStateOwner: ContactsStateOwner = ContactsStateOwner(contactsRepository),
    private val currencySettingsStateOwner: CurrencySettingsStateOwner = CurrencySettingsStateOwner(currencySettingsRepository),
    private val ecashQrStateOwner: EcashQrStateOwner = EcashQrStateOwner(ecashQrRepository),
) : ViewModel() {
    private val featureScope = CoroutineScope(viewModelScope.coroutineContext + workerDispatcher)
    private val activityStateOwner = ActivityStateOwner(activityRepository, featureScope)
    private val federationStateOwner = FederationStateOwner(federationRepository, featureScope)
    private val lnurlStateOwner = LnurlStateOwner(lnurlRepository, featureScope)
    private val onchainAddressStateOwner = OnchainAddressStateOwner(onchainAddressRepository, featureScope)
    val lnAddressStateOwner = LnAddressStateOwner(repository.nativeApi, featureScope)
    private var refreshJob: Job? = null
    private var regularOperationJob: Job? = null
    private var irreversibleJob: Job? = null
    private var transientJob: Job? = null
    private var startupJob: Job? = null
    private var reconciliationJob: Job? = null
    @Volatile private var startupGeneration = 0L
    @Volatile private var refreshGeneration = 0L
    @Volatile private var resumed = false
    @Volatile private var operationGeneration = 0L
    @Volatile private var seedConfirmationRedactedForBackground = false
    private val mutableState = MutableStateFlow<BootstrapState>(BootstrapState.Loading)
    val state: StateFlow<BootstrapState> = mutableState.asStateFlow()
    val applicationState: StateFlow<cash.pyx.app.data.WalletApplicationState> = applicationStateOwner.state
    private val mutableOperation = MutableStateFlow<WalletOperation>(WalletOperation.Idle)
    val operation: StateFlow<WalletOperation> = mutableOperation.asStateFlow()
    val pendingIrreversibleOperation = operationReconciliation.pending
    private val mutableInvite = MutableStateFlow<String?>(null)
    val pendingInvite: StateFlow<String?> = mutableInvite.asStateFlow()
    private val mutableSendPayload = MutableStateFlow<String?>(null)
    val pendingSendPayload: StateFlow<String?> = mutableSendPayload.asStateFlow()
    val contactsState = contactsStateOwner.state
    val federationState = federationStateOwner.state
    val federationMutation = federationStateOwner.mutation
    val connection = federationState.map { (it as? FederationState.Ready)?.connection }
        .stateIn(featureScope, SharingStarted.Eagerly, null)
    private val mutableRefreshStatus = MutableStateFlow<HomeRefreshStatus?>(null)
    val refreshStatus = mutableRefreshStatus.asStateFlow()
    private var consecutiveRefreshFailures = 0
    val currencySettingsState = currencySettingsStateOwner.state
    val onchainAddressState = onchainAddressStateOwner.state
    val addresses = onchainAddressState.map { it.addresses }
        .stateIn(featureScope, SharingStarted.Eagerly, emptyList())
    private val mutableFiatBalance = MutableStateFlow<cash.pyx.app.nativeapi.FiatDisplay?>(null)
    val fiatBalance = mutableFiatBalance.asStateFlow()
    private val mutableRecovery = MutableStateFlow<cash.pyx.app.nativeapi.RecoveryEvent?>(null)
    val recovery = mutableRecovery.asStateFlow()
    private val mutableRecoveryExpiry = MutableStateFlow<cash.pyx.app.nativeapi.RecoveryExpirySnapshot?>(null)
    val recoveryExpiry = mutableRecoveryExpiry.asStateFlow()
    private val mutablePaymentNotice = MutableStateFlow<PaymentNotice?>(null)
    val paymentNotice = mutablePaymentNotice.asStateFlow()
    val activityState = activityStateOwner.state
    private var lastPaymentNoticeIdentity: String? = null
    @Volatile private var seedValidationGeneration = 0L
    @Volatile private var seedSuggestionGeneration = 0L
    private val mutableSeedValidation = MutableStateFlow<cash.pyx.app.nativeapi.SeedPhraseValidation?>(null)
    val seedValidation = mutableSeedValidation.asStateFlow()
    private val mutableSeedSuggestions = MutableStateFlow<List<String>>(emptyList())
    val seedSuggestions = mutableSeedSuggestions.asStateFlow()
    val ecashQrState = ecashQrStateOwner.state
    val classifiedInput = ecashQrState.map { it.classifiedInput }
        .stateIn(featureScope, SharingStarted.Eagerly, null)
    val ecashFrame = ecashQrState.map { it.displayFrame }
        .stateIn(featureScope, SharingStarted.Eagerly, null)
    val ecashDecodeProgress = ecashQrState.map { it.decodeProgress }
        .stateIn(featureScope, SharingStarted.Eagerly, 0)

    init { refresh() }

    private fun publishState(next: BootstrapState) {
        mutableState.value = next
        applicationStateOwner.accept(next)
        when (next) {
            is BootstrapState.Home -> activityStateOwner.selectClient(next.snapshot.selected?.clientHandle)
            is BootstrapState.Onboarding, is BootstrapState.Error, is BootstrapState.Unavailable ->
                activityStateOwner.selectClient(null)
            else -> Unit // Preserve cached history across transient loading states.
        }
        onchainAddressStateOwner.selectClient((next as? BootstrapState.Home)?.snapshot?.selected?.clientHandle)
    }

    fun refresh() {
        val generation = ++startupGeneration
        startupJob?.cancel()
        publishState(BootstrapState.Loading)
        startupJob = viewModelScope.launch(workerDispatcher) {
            val boot = repository.bootstrapAsync()
            if (generation != startupGeneration) return@launch
            when (boot) {
                is BootstrapState.Home -> if (seedBackupState.isPending()) restoreSeedConfirmation(boot, generation) else {
                    markRefreshSuccess()
                    publishState(boot)
                    reconcilePendingOperation(boot)
                }
                is BootstrapState.Onboarding -> { seedBackupState.setPending(false); publishState(boot) }
                else -> publishState(boot)
            }
            if (mutableState.value is BootstrapState.Home) startLiveRefresh()
        }
    }

    fun createWallet(databaseHandle: Long) {
        if (startupJob?.isActive == true && mutableState.value is BootstrapState.Creating) return
        val generation = ++startupGeneration
        startupJob?.cancel()
        publishState(BootstrapState.Creating)
        startupJob = viewModelScope.launch(workerDispatcher) {
            // Persist before native creation so process death cannot bypass seed confirmation.
            seedBackupState.setPending(true)
            val result = repository.createAsync(databaseHandle)
            if (generation != startupGeneration) return@launch
            when (result) {
                is NativeResult.Success -> publishState(BootstrapState.SeedConfirmation(
                    result.value.factoryHandle,
                    result.value.seedWords,
                ))
                is NativeResult.Failure -> { seedBackupState.setPending(false); showError(result) }
            }
        }
    }

    fun restoreWallet(databaseHandle: Long, input: String) {
        val words = input.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        if (words.size != 12) {
            publishState(BootstrapState.Error("Enter all 12 recovery words.", false))
            return
        }
        val generation = ++startupGeneration
        startupJob?.cancel()
        publishState(BootstrapState.Restoring)
        startupJob = viewModelScope.launch(workerDispatcher) {
            val result = recoveryRepository.restore(databaseHandle, words)
            if (generation != startupGeneration) return@launch
            when (result) {
                is NativeResult.Success -> loadSnapshot(result.value.factoryHandle, generation)
                is NativeResult.Failure -> showError(result)
            }
        }
    }
    fun validateSeed(words: List<String>) {
        val generation = ++seedValidationGeneration
        viewModelScope.launch(workerDispatcher) {
            val value = (repository.validateSeed(words) as? NativeResult.Success)?.value
            if (generation == seedValidationGeneration) mutableSeedValidation.value = value
        }
    }
    fun suggestSeed(prefix: String) {
        val generation = ++seedSuggestionGeneration
        viewModelScope.launch(workerDispatcher) {
            val value = (repository.seedSuggestions(prefix) as? NativeResult.Success)?.value?.suggestions.orEmpty()
            if (generation == seedSuggestionGeneration) mutableSeedSuggestions.value = value
        }
    }
    fun clearSeedAssistance() {
        seedValidationGeneration++; seedSuggestionGeneration++
        mutableSeedValidation.value = null; mutableSeedSuggestions.value = emptyList()
    }

    fun acknowledgeSeed(factoryHandle: Long) {
        publishState(BootstrapState.LoadingWallet)
        viewModelScope.launch(workerDispatcher) {
            // Persist acknowledgement before exposing the wallet home.
            seedBackupState.setPending(false)
            loadSnapshot(factoryHandle)
        }
    }

    private suspend fun restoreSeedConfirmation(home: BootstrapState.Home, generation: Long = startupGeneration) {
        val result = repository.seedWordsAsync(home.factoryHandle)
        if (generation != startupGeneration) return
        when (result) {
            is NativeResult.Success -> publishState(BootstrapState.SeedConfirmation(home.factoryHandle, result.value.seedWords))
            is NativeResult.Failure -> showError(result)
        }
    }

    private suspend fun loadSnapshot(factoryHandle: Long, generation: Long? = null) {
        publishState(BootstrapState.LoadingWallet)
        val result = repository.snapshotAsync(factoryHandle)
        if (generation != null && generation != startupGeneration) return
        when (result) {
            is NativeResult.Success -> applyHome(factoryHandle, result.value)
            is NativeResult.Failure -> showError(result)
        }
    }

    private fun showError(result: NativeResult.Failure) {
        publishState(BootstrapState.Error(result.error.userMessage, result.error.retryable))
    }

    fun clearOperation() {
        operationGeneration++
        val previous = mutableOperation.value
        val federationUnknown = federationMutation.value is FederationMutation.Running || federationState.value is FederationState.Selecting
        val unknown = irreversibleJob?.isActive == true || federationUnknown
        if (federationMutation.value is FederationMutation.Running) federationStateOwner.cancelMutation()
        if (federationState.value is FederationState.Selecting) federationStateOwner.pause()
        irreversibleJob?.cancel(); irreversibleJob = null
        lnurlStateOwner.clear()
        transientJob?.cancel(); transientJob = null
        regularOperationJob?.cancel(); regularOperationJob = null
        mutableOperation.value = if (unknown) WalletOperation.Failure("Operation status is unknown. Refresh wallet state before trying again.") else WalletOperation.Idle
        viewModelScope.launch(workerDispatcher) { closeTransient(previous) }
    }
    fun operationFailure(message: String) { mutableOperation.value = WalletOperation.Failure(message) }
    fun receiveLightning(client: Long, amount: Long) = operate({ receiveRepository.lightning(client, amount) }) {
        WalletOperation.Success(
            "Lightning invoice",
            "${it.payload}\nFee: ${it.feeSat} sats",
            expiresAtEpochSeconds = it.expiresAtEpochSeconds,
        )
    }
    fun receiveOnchain(client: Long) = operate({ receiveRepository.onchain(client) }) {
        onchainAddressStateOwner.refreshAfterMutation(client)
        WalletOperation.Success("On-chain address", it.payload)
    }
    fun createEcash(client: Long, amount: Long) = journaledIrreversibleOperate(PendingIrreversibleOperation.Kind.ECASH_CREATE, client, { correlation -> repository.createEcashAsync(client, amount, correlation) }, EcashCreated::correlationId) {
        WalletOperation.Success("Ecash token", it.payload, sensitive = true, shareable = false)
    }
    fun claimEcash(client: Long, payload: String) = journaledIrreversibleOperate(PendingIrreversibleOperation.Kind.ECASH_CLAIM, client, { correlation -> repository.claimEcashAsync(client, payload, correlation) }, EcashClaimed::correlationId) {
        WalletOperation.Success("Ecash claimed", "${it.amountSat} sats")
    }

    private fun <T> operate(call: suspend () -> NativeResult<T>, success: suspend (T) -> WalletOperation) {
        if (mutableOperation.value is WalletOperation.Submitting) return
        val generation = ++operationGeneration
        mutableOperation.value = WalletOperation.Submitting
        regularOperationJob?.cancel()
        regularOperationJob = viewModelScope.launch(workerDispatcher) {
            try {
                val result = call()
                if (generation != operationGeneration) return@launch
                val next = when (result) {
                    is NativeResult.Success -> success(result.value).also { refreshHomeSnapshotIfPresent() }
                    is NativeResult.Failure -> WalletOperation.Failure(result.error.userMessage)
                }
                if (generation == operationGeneration) mutableOperation.value = next
            } finally {
                if (generation == operationGeneration) regularOperationJob = null
            }
        }
    }
    private fun <T> irreversibleOperate(call: suspend () -> NativeResult<T>, success: suspend (T) -> WalletOperation) {
        if (mutableOperation.value is WalletOperation.Submitting || irreversibleJob?.isActive == true) return
        val generation = ++operationGeneration
        mutableOperation.value = WalletOperation.Submitting
        irreversibleJob = viewModelScope.launch(workerDispatcher) {
            try {
                val result = call()
                if (generation == operationGeneration) mutableOperation.value = when (result) {
                    is NativeResult.Success -> success(result.value).also { refreshHomeSnapshotIfPresent() }
                    is NativeResult.Failure -> WalletOperation.Failure(result.error.userMessage)
                }
            } catch (_: CancellationException) {
                if (generation == operationGeneration) mutableOperation.value = WalletOperation.Failure("Operation status is unknown. Refresh wallet state before trying again.")
                throw CancellationException()
            } finally { irreversibleJob = null }
        }
    }

    private fun <T> journaledIrreversibleOperate(
        kind: PendingIrreversibleOperation.Kind,
        client: Long,
        call: suspend (String) -> NativeResult<T>,
        resultCorrelation: (T) -> String,
        success: suspend (T) -> WalletOperation,
    ) {
        if (mutableOperation.value is WalletOperation.Submitting || irreversibleJob?.isActive == true) return
        val selected = (mutableState.value as? BootstrapState.Home)?.snapshot?.selected
        if (selected == null || selected.clientHandle != client) {
            mutableOperation.value = WalletOperation.Failure("The selected federation changed. Prepare the payment again.")
            return
        }
        val correlationId = correlationIdGenerator()
        if (!operationReconciliation.begin(kind, correlationId, selected.federationId)) {
            mutableOperation.value = WalletOperation.Failure(
                "A previous operation still requires automatic reconciliation. Do not retry it.",
            )
            return
        }
        val generation = ++operationGeneration
        mutableOperation.value = WalletOperation.Submitting
        irreversibleJob = viewModelScope.launch(workerDispatcher) {
            try {
                val result = call(correlationId)
                if (generation == operationGeneration) mutableOperation.value = when (result) {
                    is NativeResult.Success -> {
                        if (resultCorrelation(result.value) != correlationId) {
                            operationReconciliation.updateStatus(correlationId, PendingIrreversibleOperation.ReconciliationStatus.AMBIGUOUS)
                            WalletOperation.Failure("The wallet returned a mismatched payment safety identifier. Do not retry; contact support.")
                        } else {
                            success(result.value).also {
                                // Surface the result before clearing either durable record.
                                mutableOperation.value = it
                                kotlinx.coroutines.yield()
                                clearConclusiveOperation(client, correlationId)
                                refreshHomeSnapshotIfPresent()
                            }
                        }
                    }
                    is NativeResult.Failure -> {
                        reconcilePendingOperationFor(client, selected.federationId) ?: WalletOperation.Failure(
                            result.error.userMessage + " The operation will be reconciled before another payment is allowed.",
                        )
                    }
                }
            } catch (_: CancellationException) {
                if (generation == operationGeneration) mutableOperation.value = WalletOperation.Failure(
                    "Operation status is unknown. Review Activity before trying again.",
                )
                throw CancellationException()
            } catch (_: Throwable) {
                // No conclusive native result: retain the journal across process recreation.
                if (generation == operationGeneration) mutableOperation.value = WalletOperation.Failure(
                    "Operation status is unknown. Review Activity before trying again.",
                )
            } finally { irreversibleJob = null }
        }
    }

    fun refreshOperationReconciliation() {
        val home = mutableState.value as? BootstrapState.Home ?: return
        reconcilePendingOperation(home, replaceRunning = true)
    }

    private fun reconcilePendingOperation(home: BootstrapState.Home, replaceRunning: Boolean = false) {
        val selected = home.snapshot.selected ?: return
        if (reconciliationJob?.isActive == true) {
            if (!replaceRunning) return
            reconciliationJob?.cancel()
        }
        reconciliationJob = viewModelScope.launch(workerDispatcher) {
            reconcilePendingOperationFor(selected.clientHandle, selected.federationId)?.let { mutableOperation.value = it }
        }
    }

    /** Returns a message only when reconciliation itself has a user-visible conclusion/problem. */
    private suspend fun reconcilePendingOperationFor(client: Long, federationId: String): WalletOperation? {
        var local = operationReconciliation.pending.value
        if (local?.federationId != null && local.federationId != federationId) return null

        val nativePending = when (val result = sendRepository.pendingOperationsAsync(client)) {
            is NativeResult.Success -> result.value.operations
            is NativeResult.Failure -> return if (local != null) WalletOperation.Failure(
                "Payment reconciliation is temporarily unavailable. Do not retry the previous operation.",
            ) else null
        }
        if (local == null && nativePending.isNotEmpty()) {
            val native = nativePending.first()
            val adopted = PendingIrreversibleOperation(
                native.kind.toJournalKind(), nowEpochMillis(), native.correlationId, federationId,
                if (nativePending.size > 1) PendingIrreversibleOperation.ReconciliationStatus.AMBIGUOUS
                else PendingIrreversibleOperation.ReconciliationStatus.LOCAL_ONLY,
            )
            if (!operationReconciliation.adopt(adopted)) return WalletOperation.Failure(
                "A native payment record could not be saved locally. Payments remain disabled; contact support.",
            )
            local = adopted
            if (nativePending.size > 1) return WalletOperation.Failure(
                "Multiple unresolved native payment records were found. Do not retry; contact support.",
            )
        }
        local ?: return null

        // A v1 record has no correlation/federation ownership. Never turn a manual review into retry authority.
        val correlationId = local.correlationId ?: return WalletOperation.Failure(
            "A payment from an older Pyx version cannot be reconciled automatically. Do not retry; contact support.",
        )
        if (local.federationId == null) return WalletOperation.Failure(
            "The previous payment has no federation ownership record. Do not retry; contact support.",
        )
        if (local.federationId != federationId) return null
        if (nativePending.any { it.correlationId != correlationId }) {
            operationReconciliation.updateStatus(correlationId, PendingIrreversibleOperation.ReconciliationStatus.AMBIGUOUS)
            return WalletOperation.Failure("Payment records disagree. Do not retry; contact support with the safety identifier.")
        }

        val reconciled = when (val result = sendRepository.reconcileOperationAsync(client, correlationId, local.kind.toNativeKind())) {
            is NativeResult.Success -> result.value
            is NativeResult.Failure -> return WalletOperation.Failure(
                "Payment reconciliation failed. Do not retry until Pyx can determine the result.",
            )
        }
        if (reconciled.correlationId != correlationId || reconciled.kind.toJournalKind() != local.kind) {
            operationReconciliation.updateStatus(correlationId, PendingIrreversibleOperation.ReconciliationStatus.AMBIGUOUS)
            return WalletOperation.Failure("Payment reconciliation returned mismatched records. Do not retry; contact support.")
        }
        return when (reconciled.status) {
            DurableOperationStatus.NOT_SUBMITTED -> surfaceThenClear(client, correlationId, WalletOperation.Failure(
                "The previous operation was definitely not submitted. You may prepare a new payment.",
            ))
            DurableOperationStatus.SUCCEEDED -> surfaceThenClear(client, correlationId, WalletOperation.Success(
                "Previous operation succeeded",
                reconciled.operationId?.let { "Operation $it" } ?: "The wallet confirmed completion.",
                shareable = false,
            ))
            DurableOperationStatus.FAILED -> surfaceThenClear(client, correlationId, WalletOperation.Failure(
                "The previous operation failed. It is safe to prepare a new payment.",
            ))
            DurableOperationStatus.IN_FLIGHT -> blockedStatus(correlationId, PendingIrreversibleOperation.ReconciliationStatus.IN_FLIGHT,
                "The previous operation is still being submitted. Pyx will not retry it.")
            DurableOperationStatus.PENDING, DurableOperationStatus.SUBMITTED -> blockedStatus(correlationId,
                PendingIrreversibleOperation.ReconciliationStatus.PENDING,
                "The previous operation is pending. Wait for a final result; do not retry it.")
            DurableOperationStatus.AMBIGUOUS -> blockedStatus(correlationId,
                PendingIrreversibleOperation.ReconciliationStatus.AMBIGUOUS,
                "The previous operation has an ambiguous result. Do not retry; contact support.")
        }
    }

    private fun blockedStatus(
        correlationId: String,
        status: PendingIrreversibleOperation.ReconciliationStatus,
        message: String,
    ): WalletOperation {
        operationReconciliation.updateStatus(correlationId, status)
        return WalletOperation.Failure(message)
    }

    private suspend fun surfaceThenClear(client: Long, correlationId: String, conclusion: WalletOperation): WalletOperation {
        mutableOperation.value = conclusion
        kotlinx.coroutines.yield()
        return if (clearConclusiveOperation(client, correlationId)) conclusion else WalletOperation.Failure(
            "The previous result was confirmed, but its safety lock could not be cleared. Do not retry; refresh or contact support.",
        )
    }

    private suspend fun clearConclusiveOperation(client: Long, correlationId: String): Boolean {
        val cleared = sendRepository.clearOperationAsync(client, correlationId)
        return cleared is NativeResult.Success && cleared.value.cleared && operationReconciliation.resolved(correlationId)
    }

    private fun DurableOperationKind.toJournalKind(): PendingIrreversibleOperation.Kind = when (this) {
        DurableOperationKind.LIGHTNING -> PendingIrreversibleOperation.Kind.LIGHTNING_SEND
        DurableOperationKind.ONCHAIN -> PendingIrreversibleOperation.Kind.ONCHAIN_SEND
        DurableOperationKind.ECASH_CREATE -> PendingIrreversibleOperation.Kind.ECASH_CREATE
        DurableOperationKind.ECASH_CLAIM -> PendingIrreversibleOperation.Kind.ECASH_CLAIM
    }

    private fun PendingIrreversibleOperation.Kind.toNativeKind(): DurableOperationKind = when (this) {
        PendingIrreversibleOperation.Kind.LIGHTNING_SEND -> DurableOperationKind.LIGHTNING
        PendingIrreversibleOperation.Kind.ONCHAIN_SEND -> DurableOperationKind.ONCHAIN
        PendingIrreversibleOperation.Kind.ECASH_CREATE -> DurableOperationKind.ECASH_CREATE
        PendingIrreversibleOperation.Kind.ECASH_CLAIM -> DurableOperationKind.ECASH_CLAIM
    }

    private suspend fun refreshHomeSnapshotIfPresent() {
        val home = mutableState.value as? BootstrapState.Home ?: return
        val refreshed = homeRepository.snapshotAsync(home.factoryHandle)
        if (refreshed is NativeResult.Success) applyHome(home.factoryHandle, refreshed.value)
    }

    fun setResumed(value: Boolean) {
        resumed = value
        if (value) {
            if (seedConfirmationRedactedForBackground) {
                seedConfirmationRedactedForBackground = false
                refresh()
                return
            }
            (mutableState.value as? BootstrapState.Home)?.let { federationStateOwner.observe(it.snapshot) }
            startLiveRefresh()
            (mutableState.value as? BootstrapState.Home)?.let { reconcilePendingOperation(it) }
        } else {
            if (mutableState.value is BootstrapState.SeedConfirmation ||
                mutableState.value is BootstrapState.Creating ||
                mutableState.value is BootstrapState.Restoring
            ) {
                seedConfirmationRedactedForBackground = true
                startupGeneration++
                startupJob?.cancel(); startupJob = null
                publishState(BootstrapState.Loading)
            }
            federationStateOwner.pause(); stopLiveRefresh(); stopEcashDecoder()
        }
    }

    fun refreshHome() {
        val home = mutableState.value as? BootstrapState.Home ?: return
        if (mutableOperation.value is WalletOperation.Submitting) return
        mutableOperation.value = WalletOperation.Submitting
        viewModelScope.launch(workerDispatcher) {
            when (val result = homeRepository.snapshotAsync(home.factoryHandle)) {
                is NativeResult.Success -> {
                    markRefreshSuccess()
                    applyHome(home.factoryHandle, result.value)
                    mutableOperation.value = WalletOperation.Idle
                }
                is NativeResult.Failure -> {
                    markRefreshFailure()
                    mutableOperation.value = WalletOperation.Failure(result.error.userMessage)
                }
            }
        }
    }

    fun loadActivityPage(client: Long, reset: Boolean = false) {
        if (client <= 0) return
        if (activityState.value.clientHandle != client) activityStateOwner.selectClient(client)
        activityStateOwner.loadNextPage(reset)
    }

    private fun startLiveRefresh() {
        if (!resumed || refreshJob?.isActive == true || mutableState.value !is BootstrapState.Home) return
        val generation = ++refreshGeneration
        refreshJob = viewModelScope.launch(workerDispatcher) {
            coroutineScope {
                val initial = (mutableState.value as? BootstrapState.Home)?.snapshot?.selected
                initial?.let { selected ->
                    launch {
                        homeRepository.balanceEvents(selected.clientHandle).catch { /* polling remains the fallback */ }.collect { balance ->
                            val home = mutableState.value as? BootstrapState.Home ?: return@collect
                            val current = home.snapshot.selected ?: return@collect
                            if (generation == refreshGeneration && resumed && current.clientHandle == selected.clientHandle) {
                                publishState(home.copy(snapshot = home.snapshot.copy(selected = current.copy(balanceSat = balance))))
                            }
                        }
                    }
                    launch {
                        homeRepository.paymentEvents(selected.clientHandle).catch { /* retain cached payments */ }.collect { update ->
                            val home = mutableState.value as? BootstrapState.Home ?: return@collect
                            val current = home.snapshot.selected ?: return@collect
                            if (generation != refreshGeneration || !resumed || current.clientHandle != selected.clientHandle) return@collect
                            publishState(home.copy(snapshot = home.snapshot.copy(selected = current.copy(payments = update.payments))))
                            activityStateOwner.acceptLivePayments(selected.clientHandle, update.payments)
                            PaymentNotificationPresentation.notice(update, lastPaymentNoticeIdentity)?.let { notice ->
                                lastPaymentNoticeIdentity = notice.identity
                                mutablePaymentNotice.value = notice
                            }
                        }
                    }
                    launch {
                        val expiry = recoveryRepository.expiry(selected.clientHandle)
                        if (expiry is NativeResult.Success && generation == refreshGeneration) mutableRecoveryExpiry.value = expiry.value
                        recoveryRepository.events(selected.clientHandle).catch { }.collect { progress ->
                            if (generation == refreshGeneration && resumed &&
                                (mutableState.value as? BootstrapState.Home)?.snapshot?.selected?.clientHandle == selected.clientHandle
                            ) {
                                mutableRecovery.value = progress
                                applicationStateOwner.acceptRecovery(progress)
                                if (progress.allFinished) {
                                    val home = mutableState.value as? BootstrapState.Home
                                    val refreshed = home?.let { homeRepository.snapshotAsync(it.factoryHandle) }
                                    if (refreshed is NativeResult.Success) applyHome(home.factoryHandle, refreshed.value)
                                }
                            }
                        }
                    }
                }
                while (true) {
                    delay(HomeRefreshRetryPolicy.delayMillis(consecutiveRefreshFailures))
                    if (generation != refreshGeneration || !resumed || mutableOperation.value is WalletOperation.Submitting) continue
                    val home = mutableState.value as? BootstrapState.Home ?: break
                    val snapshot = homeRepository.snapshotAsync(home.factoryHandle)
                    if (generation != refreshGeneration || !resumed || mutableState.value !is BootstrapState.Home) continue
                    if (snapshot is NativeResult.Success) {
                        markRefreshSuccess()
                        applyHome(home.factoryHandle, snapshot.value)
                    } else if (snapshot is NativeResult.Failure) {
                        markRefreshFailure()
                    }
                }
            }
        }
    }

    private fun stopLiveRefresh() {
        refreshGeneration++
        refreshJob?.cancel()
        refreshJob = null
    }

    override fun onCleared() {
        startupGeneration++
        contactsStateOwner.invalidate()
        currencySettingsStateOwner.invalidate()
        lnurlStateOwner.close()
        activityStateOwner.close()
        onchainAddressStateOwner.close()
        federationStateOwner.close()
        stopLiveRefresh()
        startupJob?.cancel(); startupJob = null
        irreversibleJob?.cancel(); irreversibleJob = null
        transientJob?.cancel(); transientJob = null
        reconciliationJob?.cancel(); reconciliationJob = null
        ecashQrStateOwner.close()
        closeTransient(mutableOperation.value)
        repository.close()
        super.onCleared()
    }

    private fun applyHome(factory: Long, snapshot: cash.pyx.app.nativeapi.WalletSnapshot) {
        val priorClient = (mutableState.value as? BootstrapState.Home)?.snapshot?.selected?.clientHandle
        markRefreshSuccess()
        publishState(BootstrapState.Home(factory, snapshot))
        reconcilePendingOperation(mutableState.value as BootstrapState.Home)
        mutableFiatBalance.value = snapshot.selected?.let { selected ->
            (homeRepository.satsToFiat(selected.clientHandle, selected.balanceSat) as? NativeResult.Success)?.value
        }
        if (resumed) federationStateOwner.observe(snapshot)
        if (priorClient != null && priorClient != snapshot.selected?.clientHandle) stopLiveRefresh()
        startLiveRefresh()
    }

    private fun markRefreshSuccess() {
        consecutiveRefreshFailures = 0
        mutableRefreshStatus.value = HomeRefreshStatus.Fresh(nowEpochMillis())
    }

    private fun markRefreshFailure() {
        consecutiveRefreshFailures++
        val lastSuccess = mutableRefreshStatus.value?.lastSuccessEpochMillis
        mutableRefreshStatus.value = if (consecutiveRefreshFailures >= 3) {
            HomeRefreshStatus.Offline(lastSuccess, consecutiveRefreshFailures)
        } else {
            HomeRefreshStatus.Degraded(lastSuccess, consecutiveRefreshFailures)
        }
    }

    private fun closeTransient(operation: WalletOperation) {
        when (operation) {
            is WalletOperation.LightningPrepared -> repository.closeTransient(operation.quote.quoteHandle, "quote")
            is WalletOperation.OnchainPrepared -> repository.closeTransient(operation.quote.quoteHandle, "quote")
            // LNURL session handles are owned and closed exactly once by LnurlStateOwner.
            is WalletOperation.LnurlPrepared -> Unit
            else -> Unit
        }
    }

    fun switchFederation(factory: Long, id: String) {
        if (mutableOperation.value is WalletOperation.Submitting) return
        val generation = ++operationGeneration
        mutableOperation.value = WalletOperation.Submitting
        federationStateOwner.select(factory, id) { result ->
            if (generation != operationGeneration) return@select
            when (result) {
                is NativeResult.Success -> if (generation == operationGeneration) { applyHome(factory, result.value); mutableOperation.value = WalletOperation.Idle }
                is NativeResult.Failure -> if (generation == operationGeneration) mutableOperation.value = WalletOperation.Failure(result.error.userMessage)
            }
        }
    }
    fun setCurrency(factory: Long, code: String) {
        currencySettingsStateOwner.select(featureScope, factory, code) { refresh() }
    }
    fun backup(factory: Long) = operate({ repository.seedWordsAsync(factory) }) {
        WalletOperation.Success("Recovery words", it.seedWords.joinToString(" "), sensitive = true, shareable = false)
    }
    fun leave(factory: Long, id: String) {
        if (mutableOperation.value is WalletOperation.Submitting || federationMutation.value is FederationMutation.Running) return
        val generation = ++operationGeneration
        mutableOperation.value = WalletOperation.Submitting
        federationStateOwner.leave(factory, id) { result ->
            if (generation != operationGeneration) return@leave
            mutableOperation.value = when (result) {
                is FederationMutation.Left -> {
                    applyHome(factory, result.snapshot)
                    WalletOperation.Success("Federation left", "Wallet list refreshed")
                }
                is FederationMutation.Failed -> WalletOperation.Failure(result.error.userMessage)
                else -> WalletOperation.Failure("Federation leave status is unknown. Refresh the wallet list before trying again.")
            }
        }
    }
    fun details(client: Long) = loadFederationDetails(client)
    fun queueInvite(uri: String?) {
        intentConsumptionGate.consume(uri)?.let(::classifyInput)
    }
    fun clearInvite() { mutableInvite.value = null }
    fun clearSendPayload() { mutableSendPayload.value = null }
    fun classifyInput(payload: String) {
        ecashQrStateOwner.classify(featureScope, payload)
    }
    fun consumeClassifiedInput() = ecashQrStateOwner.consumeClassifiedInput()
    fun join(factory: Long, invite: String, recover: Boolean) {
        val normalized = invite.trim()
        if (normalized.isEmpty() || normalized.length > 16 * 1024) {
            mutableOperation.value = WalletOperation.Failure("Enter a valid federation invite.")
            return
        }
        if (recover) irreversibleOperate({ recoveryRepository.recoverFederation(factory, normalized) }) {
            mutableInvite.value = null
            applyHome(factory, it)
            WalletOperation.Idle
        } else {
            val generation = ++operationGeneration
            mutableOperation.value = WalletOperation.Submitting
            federationStateOwner.join(factory, normalized) { result ->
                if (generation != operationGeneration) return@join
                mutableOperation.value = when (result) {
                    is FederationMutation.Joined -> { mutableInvite.value = null; applyHome(factory, result.snapshot); WalletOperation.Idle }
                    is FederationMutation.Failed -> WalletOperation.Failure(result.error.userMessage)
                    else -> WalletOperation.Failure("Federation join status is unknown. Refresh before retrying.")
                }
            }
        }
    }
    fun prepareLnurl(request: String) {
        if (mutableOperation.value is WalletOperation.Submitting) return
        val previous = mutableOperation.value
        val generation = ++operationGeneration
        mutableOperation.value = WalletOperation.Submitting
        if (previous !is WalletOperation.LnurlPrepared) featureScope.launch { closeTransient(previous) }
        lnurlStateOwner.prepare(request) { result ->
            if (generation != operationGeneration) return@prepare
            mutableOperation.value = when (result) {
                is NativeResult.Success -> WalletOperation.LnurlPrepared(
                    result.value.sessionHandle,
                    result.value.minSat,
                    result.value.maxSat,
                    result.value.fixedAmount,
                )
                is NativeResult.Failure -> WalletOperation.Failure(result.error.userMessage)
            }
        }
    }
    fun prepareLnurlQuote(client: Long, session: Long, amount: Long) {
        if (mutableOperation.value !is WalletOperation.LnurlPrepared) return
        val prepared = mutableOperation.value as WalletOperation.LnurlPrepared
        val generation = ++operationGeneration
        mutableOperation.value = WalletOperation.Submitting
        val started = lnurlStateOwner.prepareQuote(client, session, amount) { result ->
            if (generation != operationGeneration) return@prepareQuote
            mutableOperation.value = when (result) {
                is NativeResult.Success -> WalletOperation.LightningPrepared(result.value)
                is NativeResult.Failure -> WalletOperation.Failure(result.error.userMessage)
            }
        }
        if (!started && generation == operationGeneration) mutableOperation.value = prepared
    }
    fun loadContacts(factory: Long) { contactsStateOwner.load(featureScope, factory) }
    fun saveContact(factory: Long, lnurl: String, name: String) {
        contactsStateOwner.save(featureScope, factory, lnurl, name)
    }
    fun deleteContact(factory: Long, lnurl: String) {
        contactsStateOwner.delete(featureScope, factory, lnurl)
    }
    fun clearContactsMessage() = contactsStateOwner.clearMessage()
    fun clearCurrencyMessage() = currencySettingsStateOwner.clearMessage()
    fun loadConnection(client: Long) = loadFederationDetails(client)
    fun loadFederationDetails(client: Long) {
        val home = mutableState.value as? BootstrapState.Home ?: return
        if (home.snapshot.selected?.clientHandle != client) return
        federationStateOwner.observe(home.snapshot)
    }
    fun paymentDetails(client: Long, operationId: String) {
        if (activityState.value.clientHandle != client) activityStateOwner.selectClient(client)
        activityStateOwner.openDetail(operationId)
    }
    fun dismissPaymentDetails() = activityStateOwner.dismissDetail()
    fun loadCurrencies() { currencySettingsStateOwner.load(featureScope) }
    fun convertFiat(client: Long, amount: String) {
        if (mutableOperation.value is WalletOperation.Submitting) return
        val generation = ++operationGeneration
        mutableOperation.value = WalletOperation.Submitting
        regularOperationJob?.cancel()
        regularOperationJob = viewModelScope.launch(workerDispatcher) {
            val next = when (val result = repository.fiatToSats(client, amount)) {
                is NativeResult.Success -> WalletOperation.FiatConverted(result.value.amountSat, result.value.currencyCode)
                is NativeResult.Failure -> WalletOperation.Failure(result.error.userMessage)
            }
            if (generation == operationGeneration) { mutableOperation.value = next; regularOperationJob = null }
        }
    }
    fun parseBitcoin(payload: String) = operate({ repository.parseBitcoin(payload) }) {
        WalletOperation.BitcoinParsed(payload, it)
    }
    fun showSuccessorInvite() {
        val invite = mutableRecoveryExpiry.value?.successorInvite ?: return
        mutableOperation.value = WalletOperation.SuccessorInvite(invite)
    }
    fun consumePaymentNotice() { mutablePaymentNotice.value = null }
    fun startEcashDisplay(payload: String, animate: Boolean) {
        ecashQrStateOwner.startDisplay(
            featureScope,
            payload,
            EcashFountainPresentation.useStatic(payload.toByteArray(Charsets.UTF_8).size, !animate),
            EcashFountainPresentation.FRAME_MILLIS,
        )
    }
    fun stopEcashDisplay() = ecashQrStateOwner.stopDisplay()
    fun feedEcashFrame(fragment: String) {
        ecashQrStateOwner.feedFrame(featureScope, fragment)
    }
    fun stopEcashDecoder() = ecashQrStateOwner.stopDecoder()
    fun loadAddresses(client: Long) {
        if (client <= 0 || mutableOperation.value is WalletOperation.Submitting) return
        if (onchainAddressState.value.clientHandle != client) onchainAddressStateOwner.selectClient(client)
        val generation = ++operationGeneration
        mutableOperation.value = WalletOperation.Submitting
        val job = onchainAddressStateOwner.load { result ->
            if (generation != operationGeneration) return@load
            mutableOperation.value = when (result) {
                is NativeResult.Success -> WalletOperation.Success("Address history", "${result.value.addresses.size} addresses")
                is NativeResult.Failure -> WalletOperation.Failure(result.error.userMessage)
            }
        }
        if (job == null && generation == operationGeneration) mutableOperation.value = WalletOperation.Idle
    }
    fun recheckAddress(client: Long, tweak: Long) {
        if (client <= 0 || mutableOperation.value is WalletOperation.Submitting) return
        if (onchainAddressState.value.clientHandle != client) onchainAddressStateOwner.selectClient(client)
        val generation = ++operationGeneration
        mutableOperation.value = WalletOperation.Submitting
        val job = onchainAddressStateOwner.recheck(tweak) { result ->
            if (generation != operationGeneration) return@recheck
            mutableOperation.value = when (result) {
                is NativeResult.Success -> WalletOperation.Success("Address checked", "Address history refreshed")
                is NativeResult.Failure -> WalletOperation.Failure(result.error.userMessage)
            }
        }
        if (job == null && generation == operationGeneration) mutableOperation.value = WalletOperation.Idle
    }
    fun prepareLightning(client: Long, invoice: String) = prepareQuote({ sendRepository.prepareLightningAsync(client, invoice) }) {
        WalletOperation.LightningPrepared(it)
    }
    fun prepareOnchain(client: Long, address: String, amount: Long) = prepareQuote({ sendRepository.prepareOnchainAsync(client, address, amount) }) {
        WalletOperation.OnchainPrepared(it)
    }
    private fun <T> prepareQuote(call: suspend () -> NativeResult<T>, success: suspend (T) -> WalletOperation) {
        if (mutableOperation.value is WalletOperation.Submitting) return
        val previous = mutableOperation.value
        val generation = ++operationGeneration
        mutableOperation.value = WalletOperation.Submitting
        transientJob?.cancel()
        transientJob = viewModelScope.launch(workerDispatcher) {
            closeTransient(previous)
            when (val result = call()) {
                is NativeResult.Success -> {
                    val prepared = success(result.value)
                    if (generation == operationGeneration) mutableOperation.value = prepared else closeTransient(prepared)
                }
                is NativeResult.Failure -> if (generation == operationGeneration) mutableOperation.value = WalletOperation.Failure(result.error.userMessage)
            }
        }
    }
    fun executeLightning(client: Long, quote: Long) = journaledIrreversibleOperate(PendingIrreversibleOperation.Kind.LIGHTNING_SEND, client, { correlation -> sendRepository.executeLightningAsync(client, quote, correlation) }, cash.pyx.app.nativeapi.LightningSend::correlationId) {
        WalletOperation.Success("Payment submitted", "Operation ${it.operationId}; fee ${it.feeSat} sats")
    }
    fun executeOnchain(client: Long, quote: Long) = journaledIrreversibleOperate(PendingIrreversibleOperation.Kind.ONCHAIN_SEND, client, { correlation -> sendRepository.executeOnchainAsync(client, quote, correlation) }, cash.pyx.app.nativeapi.OnchainSend::correlationId) {
        WalletOperation.Success("Payment submitted", "Fee ${it.feeSat} sats")
    }
    fun receiveLnurl(client: Long) = operate({ receiveRepository.lnurl(client) }) {
        WalletOperation.Success("LNURL receive", it.payload)
    }
}
