package cash.pyx.app.nativeapi

import org.json.JSONException
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

interface NativeWalletApi {
    private fun <T> unavailable(): NativeResult<T> = NativeResult.Failure(AndroidError("native_api_unavailable", "Wallet operation is unavailable.", false))
    fun createEcashEncoder(payload: String): NativeResult<EcashCodecHandle> = NativeResult.Failure(AndroidError("native_api_unavailable", "Animated ecash QR is unavailable.", false))
    fun nextEcashFragment(handle: Long): NativeResult<EcashFragment> = NativeResult.Failure(AndroidError("native_api_unavailable", "Animated ecash QR is unavailable.", false))
    fun createEcashDecoder(): NativeResult<EcashCodecHandle> = NativeResult.Failure(AndroidError("native_api_unavailable", "Ecash scanner is unavailable.", false))
    fun addEcashFragment(handle: Long, fragment: String): NativeResult<EcashDecodeResult> = NativeResult.Failure(AndroidError("native_api_unavailable", "Ecash scanner is unavailable.", false))
    fun closeEcashCodec(handle: Long, kind: String): NativeResult<EcashCodecClosed> = NativeResult.Failure(AndroidError("native_api_unavailable", "Ecash scanner is unavailable.", false))
    fun seedWordSuggestions(prefix: String): NativeResult<SeedWordSuggestions> =
        NativeResult.Failure(AndroidError("native_api_unavailable", "Seed suggestions are unavailable.", false))
    fun validateSeedPhrase(words: List<String>): NativeResult<SeedPhraseValidation> =
        NativeResult.Failure(AndroidError("native_api_unavailable", "Seed validation is unavailable.", false))
    fun listFiatCurrencies(): NativeResult<FiatCurrencies>
    fun classifyInput(payload: String): NativeResult<InputType>
    fun closeTransientHandle(handle: Long, kind: String): NativeResult<TransientClosed>
    suspend fun walletSnapshotAsync(factoryHandle: Long): NativeResult<WalletSnapshot> = unavailable()
    suspend fun connectionStatusAsync(clientHandle: Long): NativeResult<GuardianConnectionSnapshot> = unavailable()
    fun satsToFiat(clientHandle: Long, amountSat: Long): NativeResult<FiatDisplay?> = NativeResult.Success(null)
    suspend fun fiatToSatsAsync(clientHandle: Long, amountDecimal: String): NativeResult<FiatToSats> =
        NativeResult.Failure(AndroidError("native_api_unavailable", "Fiat conversion is unavailable.", false))
    suspend fun recoveryExpirySnapshotAsync(clientHandle: Long): NativeResult<RecoveryExpirySnapshot> =
        NativeResult.Failure(AndroidError("native_api_unavailable", "Recovery status is unavailable.", false))
    suspend fun prepareLightningSendAsync(clientHandle: Long, invoice: String): NativeResult<LightningQuote> = unavailable()
    suspend fun executeLightningSendAsync(clientHandle: Long, quoteHandle: Long, correlationId: String): NativeResult<LightningSend> = unavailable()
    suspend fun prepareOnchainSendAsync(clientHandle: Long, address: String, amountSat: Long): NativeResult<OnchainQuote> = unavailable()
    suspend fun executeOnchainSendAsync(clientHandle: Long, quoteHandle: Long, correlationId: String): NativeResult<OnchainSend> = unavailable()
    suspend fun receiveLightningAsync(clientHandle: Long, amountSat: Long): NativeResult<LightningReceive> = unavailable()
    suspend fun receiveOnchainAsync(clientHandle: Long): NativeResult<OnchainReceive> = unavailable()
    suspend fun createEcashAsync(clientHandle: Long, amountSat: Long, correlationId: String): NativeResult<EcashCreated> = unavailable()
    suspend fun claimEcashAsync(clientHandle: Long, payload: String, correlationId: String): NativeResult<EcashClaimed> = unavailable()
    suspend fun pendingOperationsAsync(clientHandle: Long): NativeResult<PendingNativeOperations> = unavailable()
    suspend fun reconcileOperationAsync(clientHandle: Long, correlationId: String, kind: DurableOperationKind): NativeResult<OperationReconciliationResult> = unavailable()
    suspend fun clearOperationAsync(clientHandle: Long, correlationId: String): NativeResult<DurableOperationCleared> = unavailable()
    suspend fun prepareLnurlQuoteAsync(clientHandle: Long, sessionHandle: Long, amountSat: Long): NativeResult<LightningQuote> = unavailable()
    suspend fun prepareLnurlAsync(request: String): NativeResult<LnurlSession> = unavailable()
    suspend fun joinFederationAsync(factoryHandle: Long, invite: String, recover: Boolean): NativeResult<WalletSnapshot> = unavailable()
    suspend fun walletSnapshotForAsync(factoryHandle: Long, federationId: String): NativeResult<WalletSnapshot> = unavailable()
    suspend fun leaveFederationAsync(factoryHandle: Long, federationId: String): NativeResult<FederationLeft> = unavailable()
    suspend fun federationDetailsAsync(clientHandle: Long): NativeResult<FederationDetails> = unavailable()
    suspend fun listContactsAsync(factoryHandle: Long): NativeResult<ContactsSnapshot> = unavailable()
    suspend fun saveContactAsync(factoryHandle: Long, lnurl: String, name: String): NativeResult<ContactSaved> = unavailable()
    suspend fun deleteContactAsync(factoryHandle: Long, lnurl: String): NativeResult<ContactDeleted> = unavailable()
    suspend fun setCurrencyAsync(factoryHandle: Long, code: String): NativeResult<CurrencyChanged> = unavailable()
    suspend fun onchainAddressesAsync(clientHandle: Long): NativeResult<OnchainAddresses> = unavailable()
    suspend fun recheckOnchainAddressAsync(clientHandle: Long, tweakIndex: Long): NativeResult<AddressRechecked> = unavailable()
    suspend fun paymentDetailsAsync(clientHandle: Long, operationId: String): NativeResult<PaymentDetails> = unavailable()
    suspend fun paymentHistoryPageAsync(clientHandle: Long, cursor: String?, pageSize: Int): NativeResult<PaymentPage> = unavailable()
    suspend fun bootstrapAsync(filesDir: String): NativeResult<BootstrapSession> = unavailable()
    suspend fun createWalletAsync(databaseHandle: Long): NativeResult<CreatedWallet> = unavailable()
    suspend fun restoreWalletAsync(databaseHandle: Long, words: List<String>): NativeResult<RestoredWallet> = unavailable()
    suspend fun seedWordsAsync(factoryHandle: Long): NativeResult<SeedWords> = unavailable()
    suspend fun receiveLnurlAsync(clientHandle: Long): NativeResult<LnurlReceive> = unavailable()
    suspend fun lnaddrSnapshotAsync(factoryHandle: Long): NativeResult<LnAddressSnapshot> = unavailable()
    suspend fun lnaddrDiscoverAsync(factoryHandle: Long): NativeResult<LnaddrDiscovery> = unavailable()
    suspend fun lnaddrQuoteAsync(factoryHandle: Long, origin: String, domain: String, username: String): NativeResult<LnaddrQuote> = unavailable()
    suspend fun lnaddrClaimAsync(clientHandle: Long, origin: String, domain: String, username: String): NativeResult<LnAddress> = unavailable()
    suspend fun lnaddrSetPrimaryAsync(factoryHandle: Long, domain: String, username: String): NativeResult<LnaddrMutation> = unavailable()
    suspend fun lnaddrReleaseAsync(factoryHandle: Long, domain: String, username: String): NativeResult<LnaddrMutation> = unavailable()
    suspend fun lnaddrRepointAsync(clientHandle: Long, domain: String, username: String): NativeResult<LnaddrMutation> = unavailable()
    suspend fun lnaddrRecoverAsync(factoryHandle: Long): NativeResult<LnaddrRecovery> = unavailable()
    suspend fun shutdownAndroidSession(): NativeResult<AndroidSessionShutdown> =
        NativeResult.Failure(AndroidError("native_api_unavailable", "Wallet shutdown is unavailable.", false))
    fun parseBitcoinPayment(payload: String): NativeResult<BitcoinPayment> =
        NativeResult.Failure(AndroidError("native_api_unavailable", "Bitcoin payment details are unavailable.", false))
}

class JniNativeWalletApi internal constructor(
    libraryLoader: () -> Unit = { System.loadLibrary("pyx") },
    private val createEcashEncoderBinding: (String) -> Long = NativeBindings::createEcashEncoder,
    private val nextEcashFragmentBinding: (Long) -> String = NativeBindings::nextEcashFragment,
    private val createEcashDecoderBinding: () -> Long = NativeBindings::createEcashDecoder,
    private val addEcashFragmentBinding: (Long, String) -> String = NativeBindings::addEcashFragment,
    private val closeEcashCodecBinding: (Long, String) -> String = NativeBindings::closeEcashCodec,
    private val seedWordSuggestionsBinding: (String) -> String = NativeBindings::seedWordSuggestions,
    private val validateSeedPhraseBinding: (String) -> String = NativeBindings::validateSeedPhrase,
    private val fiatCurrenciesBinding: () -> String = NativeBindings::listFiatCurrencies,
    private val classifyBinding: (String) -> String = NativeBindings::classifyInput,
    private val closeTransientBinding: (Long, String) -> String = NativeBindings::closeTransientHandle,
    private val walletSnapshotAsyncBinding: (Long, NativeRequestCallback) -> Long = NativeBindings::walletSnapshotAsync,
    private val connectionStatusAsyncBinding: (Long, NativeRequestCallback) -> Long = NativeBindings::connectionStatusAsync,
    private val cancelRequestBinding: (Long) -> String = NativeBindings::cancelRequest,
    private val satsToFiatBinding: (Long, Long) -> String? = NativeBindings::satsToFiat,
    private val fiatToSatsAsyncBinding: (Long, String, NativeRequestCallback) -> Long = NativeBindings::fiatToSatsAsync,
    private val recoveryExpiryAsyncBinding: (Long, NativeRequestCallback) -> Long = NativeBindings::recoveryExpirySnapshotAsync,
    private val prepareLightningAsyncBinding: (Long, String, NativeRequestCallback) -> Long = NativeBindings::prepareLightningSendAsync,
    private val executeLightningAsyncBinding: (Long, Long, String, NativeRequestCallback) -> Long = NativeBindings::executeLightningSendAsync,
    private val prepareOnchainAsyncBinding: (Long, String, Long, NativeRequestCallback) -> Long = NativeBindings::prepareOnchainSendAsync,
    private val executeOnchainAsyncBinding: (Long, Long, String, NativeRequestCallback) -> Long = NativeBindings::executeOnchainSendAsync,
    private val receiveLightningAsyncBinding: (Long, Long, NativeRequestCallback) -> Long = NativeBindings::receiveLightningAsync,
    private val receiveOnchainAsyncBinding: (Long, NativeRequestCallback) -> Long = NativeBindings::receiveOnchainAsync,
    private val createEcashAsyncBinding: (Long, Long, String, NativeRequestCallback) -> Long = NativeBindings::createEcashAsync,
    private val claimEcashAsyncBinding: (Long, String, String, NativeRequestCallback) -> Long = NativeBindings::claimEcashAsync,
    private val pendingOperationsAsyncBinding: (Long, NativeRequestCallback) -> Long = NativeBindings::pendingOperationsAsync,
    private val reconcileOperationAsyncBinding: (Long, String, String, NativeRequestCallback) -> Long = NativeBindings::reconcileOperationAsync,
    private val clearOperationAsyncBinding: (Long, String, NativeRequestCallback) -> Long = NativeBindings::clearOperationAsync,
    private val prepareLnurlQuoteAsyncBinding: (Long, Long, Long, NativeRequestCallback) -> Long = NativeBindings::prepareLnurlQuoteAsync,
    private val prepareLnurlAsyncBinding: (String, NativeRequestCallback) -> Long = NativeBindings::prepareLnurlAsync,
    private val joinAsyncBinding: (Long, String, Boolean, NativeRequestCallback) -> Long = NativeBindings::joinFederationAsync,
    private val snapshotForAsyncBinding: (Long, String, NativeRequestCallback) -> Long = NativeBindings::walletSnapshotForAsync,
    private val leaveAsyncBinding: (Long, String, NativeRequestCallback) -> Long = NativeBindings::leaveFederationAsync,
    private val detailsAsyncBinding: (Long, NativeRequestCallback) -> Long = NativeBindings::federationDetailsAsync,
    private val contactsAsyncBinding: (Long, NativeRequestCallback) -> Long = NativeBindings::listContactsAsync,
    private val saveContactAsyncBinding: (Long, String, String, NativeRequestCallback) -> Long = NativeBindings::saveContactAsync,
    private val deleteContactAsyncBinding: (Long, String, NativeRequestCallback) -> Long = NativeBindings::deleteContactAsync,
    private val setCurrencyAsyncBinding: (Long, String, NativeRequestCallback) -> Long = NativeBindings::setCurrencyAsync,
    private val addressesAsyncBinding: (Long, NativeRequestCallback) -> Long = NativeBindings::onchainAddressesAsync,
    private val recheckAddressAsyncBinding: (Long, Long, NativeRequestCallback) -> Long = NativeBindings::recheckOnchainAddressAsync,
    private val paymentDetailsAsyncBinding: (Long, String, NativeRequestCallback) -> Long = NativeBindings::paymentDetailsAsync,
    private val paymentHistoryPageAsyncBinding: (Long, String, Int, NativeRequestCallback) -> Long = NativeBindings::paymentHistoryPageAsync,
    private val bootstrapAsyncBinding: (String, NativeRequestCallback) -> Long = NativeBindings::bootstrapAsync,
    private val createAsyncBinding: (Long, NativeRequestCallback) -> Long = NativeBindings::createWalletAsync,
    private val restoreAsyncBinding: (Long, String, NativeRequestCallback) -> Long = NativeBindings::restoreWalletAsync,
    private val seedWordsAsyncBinding: (Long, NativeRequestCallback) -> Long = NativeBindings::seedWordsAsync,
    private val receiveLnurlAsyncBinding: (Long, NativeRequestCallback) -> Long = NativeBindings::receiveLnurlAsync,
    private val lnaddrSnapshotAsyncBinding: (Long, NativeRequestCallback) -> Long = NativeBindings::lnaddrSnapshotAsync,
    private val lnaddrDiscoverAsyncBinding: (Long, NativeRequestCallback) -> Long = NativeBindings::lnaddrDiscoverAsync,
    private val lnaddrQuoteAsyncBinding: (Long, String, String, String, NativeRequestCallback) -> Long = NativeBindings::lnaddrQuoteAsync,
    private val lnaddrClaimAsyncBinding: (Long, String, String, String, NativeRequestCallback) -> Long = NativeBindings::lnaddrClaimAsync,
    private val lnaddrSetPrimaryAsyncBinding: (Long, String, String, NativeRequestCallback) -> Long = NativeBindings::lnaddrSetPrimaryAsync,
    private val lnaddrReleaseAsyncBinding: (Long, String, String, NativeRequestCallback) -> Long = NativeBindings::lnaddrReleaseAsync,
    private val lnaddrRepointAsyncBinding: (Long, String, String, NativeRequestCallback) -> Long = NativeBindings::lnaddrRepointAsync,
    private val lnaddrRecoverAsyncBinding: (Long, NativeRequestCallback) -> Long = NativeBindings::lnaddrRecoverAsync,
    private val shutdownAndroidSessionBinding: (NativeRequestCallback) -> Long = NativeBindings::shutdownAndroidSession,
    private val parseBitcoinBinding: (String) -> String = NativeBindings::parseBitcoinPayment,
) : NativeWalletApi {
    private val loadFailure: Throwable? = runCatching(libraryLoader).exceptionOrNull()

    override fun createEcashEncoder(payload: String): NativeResult<EcashCodecHandle> = ecashPayload(payload) {
        EcashCodecHandle(createEcashEncoderBinding(payload).also { if (it <= 0) throw JSONException("handle") })
    }
    override fun nextEcashFragment(handle: Long): NativeResult<EcashFragment> = codecHandle(handle) {
        val fragment = boundedObject(nextEcashFragmentBinding(handle)).requiredString("fragment")
        if (fragment.length > 4 * 1024 || !fragment.startsWith("fedimint1")) throw JSONException("fragment")
        EcashFragment(fragment)
    }
    override fun createEcashDecoder(): NativeResult<EcashCodecHandle> = nativeCall {
        EcashCodecHandle(createEcashDecoderBinding().also { if (it <= 0) throw JSONException("handle") })
    }
    override fun addEcashFragment(handle: Long, fragment: String): NativeResult<EcashDecodeResult> = codecHandle(handle) {
        if (fragment.length > 4 * 1024 || !fragment.startsWith("fedimint1")) throw JSONException("fragment")
        val value = boundedObject(addEcashFragmentBinding(handle, fragment))
        val complete = value.get("complete") as? Boolean ?: throw JSONException("complete")
        val payload = if (value.has("payload") && !value.isNull("payload")) value.requiredString("payload") else null
        if (complete != (payload != null) || payload?.let { it.length > 64 * 1024 || !it.startsWith("fedimint1") } == true) throw JSONException("payload")
        EcashDecodeResult(complete, payload)
    }
    override fun closeEcashCodec(handle: Long, kind: String): NativeResult<EcashCodecClosed> {
        if (handle <= 0 || kind !in setOf("encoder", "decoder")) return failure("invalid_handle", "Ecash transfer session is invalid.", false)
        return nativeCall { EcashCodecClosed((boundedObject(closeEcashCodecBinding(handle, kind)).get("closed") as? Boolean) ?: throw JSONException("closed")) }
    }
    override fun seedWordSuggestions(prefix: String): NativeResult<SeedWordSuggestions> {
        if (prefix.length > 16 || prefix.any { it !in 'a'..'z' })
            return failure("invalid_prefix", "Enter lowercase English letters.", false)
        return nativeCall {
            val values = boundedObject(seedWordSuggestionsBinding(prefix)).getJSONArray("suggestions")
            if (values.length() > 8) throw JSONException("suggestions")
            SeedWordSuggestions(values.strictStrings(values.length()).also { words ->
                if (words.any { it.isEmpty() || it.length > 16 || it.any { char -> char !in 'a'..'z' } })
                    throw JSONException("suggestions")
            })
        }
    }
    override fun validateSeedPhrase(words: List<String>): NativeResult<SeedPhraseValidation> {
        if (words.size > 24) return failure("invalid_seed", "Recovery phrase input is invalid.", false)
        val json = JSONArray(words).toString()
        if (json.toByteArray(Charsets.UTF_8).size > 1024)
            return failure("invalid_seed", "Recovery phrase input is invalid.", false)
        return nativeCall {
            val value = boundedObject(validateSeedPhraseBinding(json))
            val count = value.requiredNonNegativeInt("wordCount")
            if (count != words.size) throw JSONException("wordCount")
            val indicesJson = value.getJSONArray("invalidIndices")
            if (indicesJson.length() > words.size) throw JSONException("invalidIndices")
            val indices = (0 until indicesJson.length()).map { index ->
                indicesJson.getInt(index).also { if (it !in words.indices) throw JSONException("invalidIndex") }
            }
            val valid = value.get("valid") as? Boolean ?: throw JSONException("valid")
            val checksum = value.get("checksumValid") as? Boolean ?: throw JSONException("checksum")
            if (valid != (count == EXACT_SEED_WORDS && indices.isEmpty() && checksum)) throw JSONException("valid")
            SeedPhraseValidation(valid, count, indices, checksum)
        }
    }
    private fun parseConnectionStatus(json: String): GuardianConnectionSnapshot {
        val value = boundedObject(json)
        val guardiansJson = value.getJSONArray("guardians")
        if (guardiansJson.length() > MAX_GUARDIANS) throw JSONException("guardians")
        val guardians = (0 until guardiansJson.length()).map { guardiansJson.getJSONObject(it).let { guardian ->
            val connected = guardian.get("connected") as? Boolean ?: throw JSONException("connected")
            GuardianStatus(guardian.requiredString("name"), connected)
        } }
        val online = value.requiredNonNegativeInt("onlineCount")
        val total = value.requiredNonNegativeInt("totalCount")
        val required = value.requiredNonNegativeInt("requiredCount")
        val expectedRequired = if (total > 0) total - (total - 1) / 3 else 0
        if (total != guardians.size || required != expectedRequired || online > total || guardians.count { it.connected } != online) throw JSONException("counts")
        val state = when (value.requiredString("state")) {
            "connected" -> ConnectionState.CONNECTED; "degraded" -> ConnectionState.DEGRADED
            "offline" -> ConnectionState.OFFLINE; else -> throw JSONException("state")
        }
        val expectedState = when { online == 0 -> ConnectionState.OFFLINE; online == total -> ConnectionState.CONNECTED; online >= required -> ConnectionState.DEGRADED; else -> ConnectionState.OFFLINE }
        if (state != expectedState) throw JSONException("state")
        return GuardianConnectionSnapshot(guardians, online, total, required, state)
    }
    override fun listFiatCurrencies() = nativeCall {
        val values = boundedObject(fiatCurrenciesBinding()).getJSONArray("currencies")
        if (values.length() > MAX_CURRENCIES) throw JSONException("currencies")
        FiatCurrencies((0 until values.length()).map { values.getJSONObject(it).let { currency ->
            val code = currency.requiredCurrency("code")
            val digits = currency.requiredNonNegativeInt("decimalDigits")
            if (digits > 8) throw JSONException("digits")
            FiatCurrency(code, currency.requiredString("name"), currency.requiredString("symbol"), digits)
        } })
    }
    private fun parseLightningQuote(value: JSONObject): LightningQuote {
        val direct = value.get("direct") as? Boolean ?: throw JSONException("direct")
        val gateway = value.getString("gatewayUrl").also {
            if (it.length > MAX_PAYLOAD_CHARS || (!direct && it.isBlank())) throw JSONException("gateway")
        }
        return LightningQuote(value.requiredPositiveHandle("quoteHandle"), value.requiredPositiveLong("amountSat"),
            value.requiredNonNegativeLong("feeSat"), gateway, direct)
    }
    override fun classifyInput(payload: String) = sensitiveInput(payload) {
        when (boundedObject(classifyBinding(payload)).requiredString("type")) {
            "ecash" -> InputType.ECASH; "invite" -> InputType.INVITE; "lightning" -> InputType.LIGHTNING
            "bitcoin" -> InputType.BITCOIN; "lnurl" -> InputType.LNURL; "unknown" -> InputType.UNKNOWN
            else -> throw JSONException("input type")
        }
    }
    override fun closeTransientHandle(handle: Long, kind: String): NativeResult<TransientClosed> {
        if (handle <= 0 || kind !in setOf("quote", "lnurl")) return failure("invalid_handle", "Payment session is invalid.", false)
        return nativeCall {
            val closed = boundedObject(closeTransientBinding(handle, kind)).get("closed")
            if (closed != true) throw JSONException("closed")
            TransientClosed(true)
        }
    }
    override suspend fun walletSnapshotAsync(factoryHandle: Long): NativeResult<WalletSnapshot> {
        if (loadFailure != null) return failure("native_library_unavailable", "Wallet services are not available in this build.", false)
        return awaitNative({ callback -> walletSnapshotAsyncBinding(factoryHandle, callback) }, ::parseSnapshot)
    }

    override suspend fun connectionStatusAsync(clientHandle: Long): NativeResult<GuardianConnectionSnapshot> {
        if (loadFailure != null) return failure("native_library_unavailable", "Wallet services are not available in this build.", false)
        return awaitNative({ callback -> connectionStatusAsyncBinding(clientHandle, callback) }, ::parseConnectionStatus)
    }

    override fun satsToFiat(clientHandle: Long, amountSat: Long): NativeResult<FiatDisplay?> = nativeCall {
        satsToFiatBinding(clientHandle, amountSat)?.let(::parseFiatDisplay)
    }

    override suspend fun fiatToSatsAsync(clientHandle: Long, amountDecimal: String): NativeResult<FiatToSats> {
        if (!FiatDecimal.isCanonical(amountDecimal)) return failure("invalid_amount", "Enter a valid fiat amount.", false)
        if (loadFailure != null) return failure("native_library_unavailable", "Wallet services are not available in this build.", false)
        return awaitNative({ callback -> fiatToSatsAsyncBinding(clientHandle, amountDecimal, callback) }, ::parseFiatToSats)
    }

    private fun parseFiatDisplay(json: String): FiatDisplay {
        val value = boundedObject(json)
        if (value.keys().asSequence().toSet() != setOf("amountDecimal", "currencyCode", "currencyName", "currencySymbol", "decimalDigits")) throw JSONException("fiat fields")
        val decimal = value.requiredString("amountDecimal")
        val digits = value.requiredNonNegativeInt("decimalDigits")
        if (!FiatDecimal.isCanonical(decimal, allowZero = true) || digits > 8 || decimal.substringAfter('.', "").length > digits) throw JSONException("amountDecimal")
        return FiatDisplay(decimal, value.requiredCurrency("currencyCode"), value.requiredString("currencyName"), value.requiredString("currencySymbol"), digits)
    }

    private fun parseFiatToSats(json: String): FiatToSats {
        val value = boundedObject(json)
        if (value.keys().asSequence().toSet() != setOf("amountSat", "currencyCode")) throw JSONException("fiat fields")
        return FiatToSats(value.requiredPositiveLong("amountSat"), value.requiredCurrency("currencyCode"))
    }

    override suspend fun recoveryExpirySnapshotAsync(clientHandle: Long): NativeResult<RecoveryExpirySnapshot> {
        if (loadFailure != null) return failure("native_library_unavailable", "Wallet services are not available in this build.", false)
        return awaitNative({ callback -> recoveryExpiryAsyncBinding(clientHandle, callback) }, ::parseRecoveryExpiry)
    }

    private fun parseRecoveryExpiry(json: String): RecoveryExpirySnapshot {
        val value = boundedObject(json)
        if (value.keys().asSequence().toSet() != setOf("hasPendingRecoveries", "expiresAtEpochSeconds", "successorInvitePresent", "successorInvite")) throw JSONException("recovery fields")
        val pending = value.get("hasPendingRecoveries") as? Boolean ?: throw JSONException("pending")
        val expires = if (value.isNull("expiresAtEpochSeconds")) null else value.requiredNonNegativeLong("expiresAtEpochSeconds")
        val invite = if (value.isNull("successorInvite")) null else value.requiredString("successorInvite").also { if (it.length > 64 * 1024) throw JSONException("invite") }
        val present = value.get("successorInvitePresent") as? Boolean ?: throw JSONException("present")
        if (present != (invite != null)) throw JSONException("successor")
        return RecoveryExpirySnapshot(pending, expires, present, invite)
    }

    override suspend fun prepareLightningSendAsync(clientHandle: Long, invoice: String): NativeResult<LightningQuote> {
        if (invoice.isBlank() || invoice.length > MAX_PAYLOAD_CHARS) return failure("invalid_input", "Enter a valid value.", false)
        return awaitNative({ prepareLightningAsyncBinding(clientHandle, invoice, it) }) { parseLightningQuote(boundedObject(it)) }
    }
    override suspend fun executeLightningSendAsync(clientHandle: Long, quoteHandle: Long, correlationId: String): NativeResult<LightningSend> {
        if (quoteHandle <= 0) return failure("invalid_quote", "Payment quote is no longer valid.", false)
        if (!validCorrelation(correlationId)) return invalidCorrelation()
        return awaitNative({ executeLightningAsyncBinding(clientHandle, quoteHandle, correlationId, it) }) { json -> boundedObject(json).let {
            LightningSend(it.requiredPayload("operationId"), it.requiredNonNegativeLong("feeSat"), it.requiredCorrelation())
        } }
    }
    override suspend fun prepareOnchainSendAsync(clientHandle: Long, address: String, amountSat: Long): NativeResult<OnchainQuote> {
        if (amountSat < 0 || address.isBlank() || address.length > MAX_PAYLOAD_CHARS) return failure("invalid_amount", "Enter a valid amount.", false)
        return awaitNative({ prepareOnchainAsyncBinding(clientHandle, address, amountSat, it) }, ::parseOnchainQuote)
    }
    override suspend fun executeOnchainSendAsync(clientHandle: Long, quoteHandle: Long, correlationId: String): NativeResult<OnchainSend> {
        if (quoteHandle <= 0) return failure("invalid_quote", "Payment quote is no longer valid.", false)
        if (!validCorrelation(correlationId)) return invalidCorrelation()
        return awaitNative({ executeOnchainAsyncBinding(clientHandle, quoteHandle, correlationId, it) }) { json -> boundedObject(json).let {
            OnchainSend(it.requiredNonNegativeLong("feeSat"), it.requiredCorrelation(), it.optionalPayload("operationId"))
        } }
    }
    override suspend fun receiveLightningAsync(clientHandle: Long, amountSat: Long): NativeResult<LightningReceive> {
        if (amountSat <= 0) return failure("invalid_amount", "Enter an amount greater than zero.", false)
        return awaitNative({ receiveLightningAsyncBinding(clientHandle, amountSat, it) }, ::parseLightningReceive)
    }
    override suspend fun receiveOnchainAsync(clientHandle: Long): NativeResult<OnchainReceive> =
        awaitNative({ receiveOnchainAsyncBinding(clientHandle, it) }, ::parseOnchainReceive)
    override suspend fun createEcashAsync(clientHandle: Long, amountSat: Long, correlationId: String): NativeResult<EcashCreated> {
        if (amountSat <= 0) return failure("invalid_amount", "Enter an amount greater than zero.", false)
        if (!validCorrelation(correlationId)) return invalidCorrelation()
        return awaitNative({ createEcashAsyncBinding(clientHandle, amountSat, correlationId, it) }) { json -> boundedObject(json).let {
            EcashCreated(it.requiredPayload("payload"), it.requiredNonNegativeLong("amountSat"), it.requiredCorrelation(), it.optionalPayload("operationId"))
        } }
    }
    override suspend fun claimEcashAsync(clientHandle: Long, payload: String, correlationId: String): NativeResult<EcashClaimed> {
        if (payload.isBlank() || payload.length > MAX_PAYLOAD_CHARS) return failure("invalid_input", "Enter a valid value.", false)
        if (!validCorrelation(correlationId)) return invalidCorrelation()
        return awaitNative({ claimEcashAsyncBinding(clientHandle, payload, correlationId, it) }) { json -> boundedObject(json).let {
            EcashClaimed(it.requiredNonNegativeLong("amountSat"), it.requiredCorrelation(), it.optionalPayload("operationId"))
        } }
    }
    override suspend fun pendingOperationsAsync(clientHandle: Long): NativeResult<PendingNativeOperations> =
        awaitNative({ pendingOperationsAsyncBinding(clientHandle, it) }, ::parsePendingOperations)

    override suspend fun reconcileOperationAsync(clientHandle: Long, correlationId: String, kind: DurableOperationKind): NativeResult<OperationReconciliationResult> {
        if (!validCorrelation(correlationId)) return invalidCorrelation()
        return awaitNative({ reconcileOperationAsyncBinding(clientHandle, correlationId, kind.nativeName(), it) }, ::parseOperationReconciliation)
    }

    override suspend fun clearOperationAsync(clientHandle: Long, correlationId: String): NativeResult<DurableOperationCleared> {
        if (!validCorrelation(correlationId)) return invalidCorrelation()
        return awaitNative({ clearOperationAsyncBinding(clientHandle, correlationId, it) }) { json ->
            DurableOperationCleared(boundedObject(json).get("cleared") as? Boolean ?: throw JSONException("cleared"))
        }
    }
    override suspend fun prepareLnurlQuoteAsync(clientHandle: Long, sessionHandle: Long, amountSat: Long): NativeResult<LightningQuote> {
        if (amountSat <= 0) return failure("invalid_amount", "Enter an amount greater than zero.", false)
        return awaitNative({ prepareLnurlQuoteAsyncBinding(clientHandle, sessionHandle, amountSat, it) }) { parseLightningQuote(boundedObject(it)) }
    }
    override suspend fun prepareLnurlAsync(request: String): NativeResult<LnurlSession> {
        if (request.isBlank() || request.length > MAX_PAYLOAD_CHARS)
            return failure("invalid_argument", "The LNURL payment request is invalid.", false)
        return awaitNative({ prepareLnurlAsyncBinding(request, it) }) { json ->
            val value = boundedObject(json)
            val min = value.requiredNonNegativeLong("minSat")
            val max = value.requiredNonNegativeLong("maxSat")
            val fixed = value.get("fixedAmount") as? Boolean ?: throw JSONException("fixed")
            if (min > max) throw JSONException("range")
            LnurlSession(value.requiredPositiveHandle("sessionHandle"), min, max, fixed)
        }
    }

    private fun parseLightningReceive(json: String): LightningReceive = boundedObject(json).let {
        if (it.requiredString("type") != "lightning") throw JSONException("type")
        LightningReceive(
            it.requiredPayload("payload"),
            it.requiredNonNegativeLong("amountSat"),
            it.requiredNonNegativeLong("feeSat"),
            it.requiredPayload("gatewayUrl"),
            it.requiredPositiveLong("expiresAtEpochSeconds"),
        )
    }
    private fun parseOnchainReceive(json: String): OnchainReceive = boundedObject(json).let {
        if (it.requiredString("type") != "onchain") throw JSONException("type")
        OnchainReceive(it.requiredPayload("payload"))
    }
    private fun parseOnchainQuote(json: String): OnchainQuote {
        val value = boundedObject(json)
        val uriAmount = if (value.isNull("uriAmountSat")) null else value.requiredPositiveLong("uriAmountSat")
        val locked = value.get("amountLocked") as? Boolean ?: throw JSONException("amountLocked")
        if (locked != (uriAmount != null)) throw JSONException("amountLocked")
        return OnchainQuote(value.requiredPositiveHandle("quoteHandle"), value.requiredPositiveLong("amountSat"), value.requiredNonNegativeLong("feeSat"),
            value.requiredPayload("address"), uriAmount, locked, value.optionalPayload("label"), value.optionalPayload("message"))
    }
    override suspend fun joinFederationAsync(factoryHandle: Long, invite: String, recover: Boolean): NativeResult<WalletSnapshot> {
        if (invite.isBlank() || invite.length > MAX_PAYLOAD_CHARS) return failure("invalid_input", "Enter a valid value.", false)
        return awaitNative({ joinAsyncBinding(factoryHandle, invite, recover, it) }, ::parseSnapshot)
    }
    override suspend fun walletSnapshotForAsync(factoryHandle: Long, federationId: String): NativeResult<WalletSnapshot> =
        awaitNative({ snapshotForAsyncBinding(factoryHandle, federationId, it) }, ::parseSnapshot)
    override suspend fun leaveFederationAsync(factoryHandle: Long, federationId: String): NativeResult<FederationLeft> =
        awaitNative({ leaveAsyncBinding(factoryHandle, federationId, it) }) { FederationLeft(boundedObject(it).get("left") as? Boolean ?: throw JSONException("left")) }
    override suspend fun federationDetailsAsync(clientHandle: Long): NativeResult<FederationDetails> =
        awaitNative({ detailsAsyncBinding(clientHandle, it) }, ::parseFederationDetails)
    override suspend fun listContactsAsync(factoryHandle: Long): NativeResult<ContactsSnapshot> =
        awaitNative({ contactsAsyncBinding(factoryHandle, it) }, ::parseContacts)
    override suspend fun saveContactAsync(factoryHandle: Long, lnurl: String, name: String): NativeResult<ContactSaved> =
        awaitNative({ saveContactAsyncBinding(factoryHandle, lnurl, name, it) }) { ContactSaved(boundedObject(it).get("saved") as? Boolean ?: throw JSONException("saved")) }
    override suspend fun deleteContactAsync(factoryHandle: Long, lnurl: String): NativeResult<ContactDeleted> =
        awaitNative({ deleteContactAsyncBinding(factoryHandle, lnurl, it) }) { ContactDeleted(boundedObject(it).get("deleted") as? Boolean ?: throw JSONException("deleted")) }
    override suspend fun setCurrencyAsync(factoryHandle: Long, code: String): NativeResult<CurrencyChanged> =
        awaitNative({ setCurrencyAsyncBinding(factoryHandle, code, it) }) { CurrencyChanged(boundedObject(it).requiredCurrency()) }
    override suspend fun onchainAddressesAsync(clientHandle: Long): NativeResult<OnchainAddresses> =
        awaitNative({ addressesAsyncBinding(clientHandle, it) }, ::parseAddresses)
    override suspend fun recheckOnchainAddressAsync(clientHandle: Long, tweakIndex: Long): NativeResult<AddressRechecked> =
        awaitNative({ recheckAddressAsyncBinding(clientHandle, tweakIndex, it) }) { AddressRechecked(boundedObject(it).get("rechecked") as? Boolean ?: throw JSONException("rechecked")) }
    override suspend fun paymentDetailsAsync(clientHandle: Long, operationId: String): NativeResult<PaymentDetails> =
        awaitNative({ paymentDetailsAsyncBinding(clientHandle, operationId, it) }) { PaymentDetails(boundedObject(it).getJSONObject("payment").toPayment()) }
    override suspend fun paymentHistoryPageAsync(clientHandle: Long, cursor: String?, pageSize: Int): NativeResult<PaymentPage> {
        if (clientHandle <= 0 || pageSize !in 1..50 || cursor?.let { it.length > 128 } == true)
            return failure("invalid_argument", "The activity page request is invalid.", false)
        return awaitNative({ paymentHistoryPageAsyncBinding(clientHandle, cursor.orEmpty(), pageSize, it) }, ::parsePaymentPage)
    }
    override suspend fun bootstrapAsync(filesDir: String): NativeResult<BootstrapSession> {
        if (filesDir.isBlank()) return failure("invalid_argument", "The application storage directory is invalid.", false)
        return awaitNative({ bootstrapAsyncBinding(filesDir, it) }) { json ->
            when (val parsed = parseBootstrap(json)) {
                is NativeResult.Success -> parsed.value
                is NativeResult.Failure -> throw JSONException("bootstrap")
            }
        }
    }

    override suspend fun shutdownAndroidSession(): NativeResult<AndroidSessionShutdown> =
        awaitNative({ shutdownAndroidSessionBinding(it) }) { json ->
            AndroidSessionShutdown(boundedObject(json).get("shutdown") as? Boolean ?: throw JSONException("shutdown"))
        }
    override suspend fun createWalletAsync(databaseHandle: Long): NativeResult<CreatedWallet> =
        awaitNative({ createAsyncBinding(databaseHandle, it) }) { json ->
            val value = boundedObject(json)
            CreatedWallet(value.requiredPositiveHandle("factoryHandle"), value.getJSONArray("seedWords").strictStrings(EXACT_SEED_WORDS))
        }
    override suspend fun restoreWalletAsync(databaseHandle: Long, words: List<String>): NativeResult<RestoredWallet> {
        if (words.size != EXACT_SEED_WORDS || words.any { it.isBlank() || it.length > MAX_WORD_CHARS }) return failure("invalid_seed", "Enter all 12 recovery words.", false)
        return awaitNative({ restoreAsyncBinding(databaseHandle, JSONArray(words).toString(), it) }) { RestoredWallet(boundedObject(it).requiredPositiveHandle("factoryHandle")) }
    }
    override suspend fun seedWordsAsync(factoryHandle: Long): NativeResult<SeedWords> =
        awaitNative({ seedWordsAsyncBinding(factoryHandle, it) }) { SeedWords(boundedObject(it).getJSONArray("seedWords").strictStrings(EXACT_SEED_WORDS)) }
    override suspend fun receiveLnurlAsync(clientHandle: Long): NativeResult<LnurlReceive> =
        awaitNative({ receiveLnurlAsyncBinding(clientHandle, it) }) { json ->
            val value = boundedObject(json)
            if (value.requiredString("type") != "lnurl") throw JSONException("type")
            LnurlReceive(value.requiredPayload("payload"))
        }

    override suspend fun lnaddrSnapshotAsync(factoryHandle: Long): NativeResult<LnAddressSnapshot> =
        awaitNative({ lnaddrSnapshotAsyncBinding(factoryHandle, it) }, ::parseLnAddressSnapshot)
    override suspend fun lnaddrDiscoverAsync(factoryHandle: Long): NativeResult<LnaddrDiscovery> =
        awaitNative({ lnaddrDiscoverAsyncBinding(factoryHandle, it) }, ::parseLnaddrDiscovery)
    override suspend fun lnaddrQuoteAsync(factoryHandle: Long, origin: String, domain: String, username: String): NativeResult<LnaddrQuote> =
        awaitNative({ lnaddrQuoteAsyncBinding(factoryHandle, origin, domain, username, it) }, ::parseLnaddrQuote)
    override suspend fun lnaddrClaimAsync(clientHandle: Long, origin: String, domain: String, username: String): NativeResult<LnAddress> =
        awaitNative({ lnaddrClaimAsyncBinding(clientHandle, origin, domain, username, it) }) { boundedObject(it).toLnAddress() }
    override suspend fun lnaddrSetPrimaryAsync(factoryHandle: Long, domain: String, username: String): NativeResult<LnaddrMutation> =
        awaitNative({ lnaddrSetPrimaryAsyncBinding(factoryHandle, domain, username, it) }, ::parseLnaddrMutation)
    override suspend fun lnaddrReleaseAsync(factoryHandle: Long, domain: String, username: String): NativeResult<LnaddrMutation> =
        awaitNative({ lnaddrReleaseAsyncBinding(factoryHandle, domain, username, it) }, ::parseLnaddrMutation)
    override suspend fun lnaddrRepointAsync(clientHandle: Long, domain: String, username: String): NativeResult<LnaddrMutation> =
        awaitNative({ lnaddrRepointAsyncBinding(clientHandle, domain, username, it) }, ::parseLnaddrMutation)
    override suspend fun lnaddrRecoverAsync(factoryHandle: Long): NativeResult<LnaddrRecovery> =
        awaitNative({ lnaddrRecoverAsyncBinding(factoryHandle, it) }, ::parseLnaddrRecovery)

    private fun JSONObject.toLnAddress(): LnAddress {
        if (keys().asSequence().toSet() != LNADDRESS_FIELDS) throw JSONException("address fields")
        return LnAddress(
            domain = requiredLnaddrDomain("domain"),
            username = requiredLnaddrUsername("username"),
            serverOrigin = requiredPayload("serverOrigin"),
            federationId = optionalPayload("federationId"),
            destination = requiredPayload("destination"),
            isPrimary = get("isPrimary") as? Boolean ?: throw JSONException("isPrimary"),
            claimedAtSecs = requiredNonNegativeLong("claimedAtSecs"),
        )
    }

    // lnaddrd's username cap (64 chars) is a policy limit; the domain cap (253
    // chars) follows RFC 1035's max DNS name length and matches Rust's own
    // request-side validate_domain in rust/src/android/lnaddr_requests.rs.
    private fun JSONObject.requiredLnaddrUsername(key: String): String = requiredString(key).also {
        if (it.length > MAX_LNADDR_USERNAME_CHARS) throw JSONException(key)
    }

    // Uses getString directly rather than requiredString: requiredString's own
    // MAX_STRING_CHARS (128) cap is shorter than RFC 1035's 253-char domain limit.
    private fun JSONObject.requiredLnaddrDomain(key: String): String = getString(key).also {
        if (it.isEmpty() || it.length > MAX_LNADDR_DOMAIN_CHARS) throw JSONException(key)
    }

    private fun parseLnAddressSnapshot(json: String): LnAddressSnapshot {
        val values = boundedObject(json).getJSONArray("addresses")
        if (values.length() > MAX_LNADDRESSES) throw JSONException("addresses")
        return LnAddressSnapshot((0 until values.length()).map { values.getJSONObject(it).toLnAddress() })
    }

    private fun JSONObject.toLnaddrServer(): LnaddrServer {
        if (keys().asSequence().toSet() != setOf("domains", "freeDomains", "name", "origin")) throw JSONException("server fields")
        fun domainList(key: String): List<String> {
            val array = getJSONArray(key)
            if (array.length() > MAX_LNADDR_DOMAINS_PER_SERVER) throw JSONException(key)
            return (0 until array.length()).map { index ->
                array.getString(index).also { if (it.isEmpty() || it.length > MAX_LNADDR_DOMAIN_CHARS) throw JSONException(key) }
            }
        }
        return LnaddrServer(requiredPayload("origin"), requiredString("name"), domainList("domains"), domainList("freeDomains"))
    }

    private fun parseLnaddrDiscovery(json: String): LnaddrDiscovery {
        val values = boundedObject(json).getJSONArray("servers")
        if (values.length() > MAX_LNADDR_SERVERS) throw JSONException("servers")
        return LnaddrDiscovery((0 until values.length()).map { values.getJSONObject(it).toLnaddrServer() })
    }

    private fun parseLnaddrQuote(json: String): LnaddrQuote {
        val value = boundedObject(json)
        if (!value.has("priceMsat") || !value.has("state") || !LNADDR_QUOTE_FIELDS.containsAll(value.keys().asSequence().toSet()))
            throw JSONException("quote fields")
        val priceMsat = if (value.isNull("priceMsat")) null else value.requiredPositiveLong("priceMsat")
        fun withoutPrice(quote: LnaddrQuote): LnaddrQuote {
            if (priceMsat != null) throw JSONException("quote")
            return quote
        }
        return when (value.requiredString("state")) {
            "free" -> withoutPrice(LnaddrQuote.Free)
            "paid" -> LnaddrQuote.Paid(priceMsat ?: throw JSONException("quote"))
            "taken" -> withoutPrice(LnaddrQuote.Taken)
            "reserved" -> withoutPrice(LnaddrQuote.Reserved)
            "invalid" -> withoutPrice(LnaddrQuote.Invalid(value.optionalPayload("reason") ?: ""))
            "rate_limited" -> withoutPrice(LnaddrQuote.RateLimited)
            else -> throw JSONException("state")
        }
    }

    private fun parseLnaddrMutation(json: String): LnaddrMutation {
        val value = boundedObject(json)
        if (value.keys().asSequence().toSet() != setOf("ok")) throw JSONException("mutation fields")
        return LnaddrMutation(value.get("ok") as? Boolean ?: throw JSONException("ok"))
    }

    private fun parseLnaddrRecovery(json: String): LnaddrRecovery {
        val value = boundedObject(json)
        if (value.keys().asSequence().toSet() != setOf("recovered")) throw JSONException("recovery fields")
        return LnaddrRecovery(value.requiredNonNegativeLong("recovered"))
    }

    private fun parseFederationDetails(json: String): FederationDetails {
        val value = boundedObject(json)
        val stats = if (value.isNull("stats")) null else value.getJSONObject("stats").let {
            FederationStats(it.requiredNonNegativeLong("totalValueSat"), it.requiredNonNegativeLong("blockCount"), if (it.isNull("feerateSatPerKvb")) null else it.requiredNonNegativeLong("feerateSatPerKvb"))
        }
        return FederationDetails(value.requiredString("name"), value.requiredString("id"), value.requiredCurrency(), stats)
    }
    private fun parseContacts(json: String): ContactsSnapshot {
        val values = boundedObject(json).getJSONArray("contacts")
        if (values.length() > MAX_CONTACTS) throw JSONException("contacts")
        return ContactsSnapshot((0 until values.length()).map { values.getJSONObject(it).let { value -> Contact(value.requiredString("name"), value.requiredPayload("lnurl")) } })
    }
    private fun parseAddresses(json: String): OnchainAddresses {
        val values = boundedObject(json).getJSONArray("addresses")
        if (values.length() > MAX_ADDRESSES) throw JSONException("addresses")
        return OnchainAddresses((0 until values.length()).map { values.getJSONObject(it).let { value -> OnchainAddress(value.requiredNonNegativeLong("tweakIndex"), value.requiredPayload("address")) } })
    }

    override fun parseBitcoinPayment(payload: String): NativeResult<BitcoinPayment> = sensitiveInput(payload) {
        val value = boundedObject(parseBitcoinBinding(payload))
        if (value.keys().asSequence().toSet() != setOf("address", "amountSat", "label", "message", "isUri")) throw JSONException("bitcoin fields")
        val amount = if (value.isNull("amountSat")) null else value.requiredPositiveLong("amountSat")
        val isUri = value.get("isUri") as? Boolean ?: throw JSONException("isUri")
        val label = value.optionalPayload("label")?.also { if (it.length > 256) throw JSONException("label") }
        val message = value.optionalPayload("message")?.also { if (it.length > 256) throw JSONException("message") }
        if (!isUri && (amount != null || label != null || message != null)) throw JSONException("plain address metadata")
        BitcoinPayment(value.requiredPayload("address"), amount, label, message, isUri)
    }

    private suspend fun <T> awaitNative(
        start: (NativeRequestCallback) -> Long,
        parse: (String) -> T,
    ): NativeResult<T> = suspendCancellableCoroutine { continuation ->
        val terminal = AtomicBoolean(false)
        val cancelSent = AtomicBoolean(false)
        val lock = Any()
        var requestId = 0L
        var pendingId = 0L
        var pendingDelivery: (() -> Unit)? = null
        fun cancel(id: Long) {
            runCatching {
                val cancelled = boundedObject(cancelRequestBinding(id)).get("cancelled")
                if (cancelled !is Boolean) throw JSONException("cancelled")
            }
        }
        fun accept(id: Long, delivery: () -> Unit) {
            val run = synchronized(lock) {
                if (terminal.get() || id <= 0) null
                else if (requestId == 0L) {
                    if (pendingDelivery == null) { pendingId = id; pendingDelivery = delivery }
                    null
                } else if (requestId == id && terminal.compareAndSet(false, true)) delivery else null
            }
            run?.invoke()
        }
        val callback = object : NativeRequestCallback {
            override fun onSuccess(requestId: Long, json: String) {
                accept(requestId) {
                    val result = try { NativeResult.Success(parse(json)) }
                        catch (_: JSONException) { invalidResponse() }
                        catch (_: RuntimeException) { invalidResponse() }
                    if (continuation.isActive) continuation.resume(result)
                }
            }
            override fun onError(requestId: Long, code: String, message: String, retryable: Boolean) {
                accept(requestId) {
                    val error = if (code.isBlank() || code.length > 64 || message.isBlank() || message.length > 512)
                        AndroidError("invalid_native_response", "Wallet services returned an invalid response.", false)
                    else AndroidError(code, message, retryable)
                    if (continuation.isActive) continuation.resume(NativeResult.Failure(error))
                }
            }
        }
        continuation.invokeOnCancellation {
            if (terminal.compareAndSet(false, true)) synchronized(lock) { requestId }.takeIf { it > 0 }?.let {
                if (cancelSent.compareAndSet(false, true)) cancel(it)
            }
        }
        try {
            val id = start(callback)
            val run = synchronized(lock) {
                requestId = id
                when {
                    id <= 0 -> if (terminal.compareAndSet(false, true)) ({ if (continuation.isActive) continuation.resume(invalidResponse()) }) else null
                    pendingDelivery != null && pendingId != id -> if (terminal.compareAndSet(false, true)) ({ if (continuation.isActive) continuation.resume(invalidResponse()) }) else null
                    pendingDelivery != null && terminal.compareAndSet(false, true) -> pendingDelivery
                    else -> null
                }
            }
            run?.invoke()
            if (!continuation.isActive) {
                if (cancelSent.compareAndSet(false, true)) cancel(id)
            }
        } catch (_: LinkageError) {
            if (terminal.compareAndSet(false, true) && continuation.isActive)
                continuation.resume(failure("native_api_unavailable", "Wallet services could not be started.", false))
        } catch (_: RuntimeException) {
            if (terminal.compareAndSet(false, true) && continuation.isActive)
                continuation.resume(failure("native_call_failed", "Wallet services could not complete the request.", true))
        }
    }

    private fun <T> positiveAmount(amount: Long, block: () -> T): NativeResult<T> =
        if (amount <= 0) failure("invalid_amount", "Enter an amount greater than zero.", false) else nativeCall(block)
    private fun <T> ecashPayload(value: String, block: () -> T): NativeResult<T> =
        if (value.isBlank() || value.length > 64 * 1024 || !value.startsWith("fedimint1")) failure("invalid_input", "Enter a valid ecash token.", false)
        else nativeCall(block)
    private fun <T> codecHandle(handle: Long, block: () -> T): NativeResult<T> =
        if (handle <= 0) failure("invalid_handle", "Ecash transfer session is invalid.", false) else nativeCall(block)
    private fun <T> sensitiveInput(value: String, block: () -> T): NativeResult<T> =
        if (value.isBlank() || value.length > MAX_PAYLOAD_CHARS) failure("invalid_input", "Enter a valid value.", false)
        else nativeCall(block)
    private fun <T> sensitiveValue(value: String, block: () -> T): T {
        if (value.isBlank() || value.length > MAX_PAYLOAD_CHARS) throw IllegalArgumentException("invalid")
        return block()
    }

    private fun JSONObject.toSelected(): SelectedWallet {
        val paymentsJson = getJSONArray("payments")
        if (paymentsJson.length() > MAX_PAYMENTS) throw JSONException("too many payments")
        val payments = (0 until paymentsJson.length()).map { index -> paymentsJson.getJSONObject(index).toPayment() }
        return SelectedWallet(
            clientHandle = requiredPositiveHandle("clientHandle"),
            federationId = requiredString("federationId"),
            name = requiredString("name"),
            balanceSat = requiredNonNegativeLong("balanceSat"),
            payments = payments,
        )
    }

    private fun parseSnapshot(json: String): WalletSnapshot {
        val value = boundedObject(json)
        val federationsJson = value.getJSONArray("federations")
        if (federationsJson.length() > MAX_FEDERATIONS) throw JSONException("too many federations")
        val federations = (0 until federationsJson.length()).map { index -> federationsJson.getJSONObject(index).let {
            FederationSummary(it.requiredString("id"), it.requiredString("name"), it.requiredNonNegativeInt("guardianCount"))
        } }
        return WalletSnapshot(value.requiredCurrency(), federations,
            if (value.isNull("selected")) null else value.getJSONObject("selected").toSelected())
    }

    private fun JSONObject.requiredCurrency(key: String = "currencyCode") = requiredString(key).also {
        if (!it.matches(Regex("[A-Z]{3}"))) throw JSONException("currency")
    }

    private fun JSONObject.toPayment(): Payment {
        val fiatAmount = optionalPayload("fiatAmount")
        val fiatCode = optionalPayload("fiatCurrencyCode")
        if ((fiatAmount == null) != (fiatCode == null)) throw JSONException("fiat")
        return Payment(
        operationId = requiredString("operationId"),
        direction = when (val incoming = get("incoming")) {
            true -> PaymentDirection.INCOMING
            false -> PaymentDirection.OUTGOING
            else -> throw JSONException("invalid direction")
        },
        type = when (requiredString("type")) {
            "lightning" -> PaymentType.LIGHTNING
            "onchain" -> PaymentType.ONCHAIN
            "ecash" -> PaymentType.ECASH
            else -> throw JSONException("invalid payment type")
        },
        amountSat = requiredNonNegativeLong("amountSat"),
        feeSat = if (isNull("feeSat")) null else requiredNonNegativeLong("feeSat"),
        timestampMillis = requiredNonNegativeLong("timestampMillis"),
        status = when (requiredString("status")) {
            "pending" -> PaymentStatus.PENDING
            "succeeded" -> PaymentStatus.SUCCEEDED
            "failed" -> PaymentStatus.FAILED
            else -> throw JSONException("invalid payment status")
        },
        ecash = optionalPayload("ecash"), txid = optionalPayload("txid"), address = optionalPayload("address"),
        preimage = optionalPayload("preimage"), fiat = fiatAmount?.let { FiatAmount(it, fiatCode!!) },
    ) }

    private fun parsePaymentPage(json: String): PaymentPage {
        val value = boundedObject(json)
        if (value.keys().asSequence().toSet() != setOf("payments", "nextCursor")) throw JSONException("payment page fields")
        val array = value.getJSONArray("payments")
        if (array.length() > 50) throw JSONException("too many payments")
        val payments = (0 until array.length()).map { index -> array.getJSONObject(index).let { item ->
            val allowed = setOf("operationId", "direction", "type", "amountSat", "feeSat", "timestampMillis", "status", "fiatAmount", "fiatCurrencyCode")
            if (item.keys().asSequence().toSet() != allowed) throw JSONException("payment fields")
            val fiatAmount = item.optionalPayload("fiatAmount")
            val fiatCode = item.optionalPayload("fiatCurrencyCode")
            if ((fiatAmount == null) != (fiatCode == null) || fiatCode?.matches(Regex("[A-Z]{3}")) == false) throw JSONException("fiat")
            Payment(
                operationId = item.requiredString("operationId"),
                direction = when (item.requiredString("direction")) { "incoming" -> PaymentDirection.INCOMING; "outgoing" -> PaymentDirection.OUTGOING; else -> throw JSONException("direction") },
                type = when (item.requiredString("type")) { "lightning" -> PaymentType.LIGHTNING; "onchain" -> PaymentType.ONCHAIN; "ecash" -> PaymentType.ECASH; else -> throw JSONException("type") },
                amountSat = item.requiredNonNegativeLong("amountSat"),
                feeSat = if (item.isNull("feeSat")) null else item.requiredNonNegativeLong("feeSat"),
                timestampMillis = item.requiredNonNegativeLong("timestampMillis"),
                status = when (item.requiredString("status")) { "pending" -> PaymentStatus.PENDING; "succeeded" -> PaymentStatus.SUCCEEDED; "failed" -> PaymentStatus.FAILED; else -> throw JSONException("status") },
                fiat = fiatAmount?.let { FiatAmount(it, fiatCode!!) },
            )
        } }
        if (payments.map(Payment::operationId).toSet().size != payments.size ||
            payments.zipWithNext().any { (left, right) -> left.timestampMillis < right.timestampMillis || (left.timestampMillis == right.timestampMillis && left.operationId > right.operationId) })
            throw JSONException("payment order")
        val next = if (value.isNull("nextCursor")) null else value.requiredString("nextCursor").also {
            if (!it.matches(Regex("pyx1\\.[0-9a-f]{16}\\.[0-9a-f]{64}"))) throw JSONException("cursor")
        }
        if (payments.isEmpty() && next != null) throw JSONException("empty page cursor")
        return PaymentPage(payments, next)
    }

    private fun parsePendingOperations(json: String): PendingNativeOperations {
        if (json.toByteArray(Charsets.UTF_8).size > MAX_SNAPSHOT_JSON_BYTES) throw JSONException("too large")
        val array = JSONArray(json)
        if (array.length() > 16) throw JSONException("too many pending operations")
        return PendingNativeOperations((0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            if (item.keys().asSequence().toSet() != setOf("correlationId", "kind", "status")) throw JSONException("pending fields")
            PendingNativeOperation(item.requiredCorrelation(), item.requiredDurableKind(), when (item.requiredString("status")) {
                "submitted" -> DurableOperationStatus.SUBMITTED
                else -> throw JSONException("pending status")
            })
        })
    }

    private fun parseOperationReconciliation(json: String): OperationReconciliationResult {
        val value = boundedObject(json)
        if (value.keys().asSequence().toSet() != setOf("correlationId", "kind", "status", "operationId", "candidateCount"))
            throw JSONException("reconciliation fields")
        return OperationReconciliationResult(
            value.requiredCorrelation(),
            value.requiredDurableKind(),
            when (value.requiredString("status")) {
                "inFlight" -> DurableOperationStatus.IN_FLIGHT
                "pending" -> DurableOperationStatus.PENDING
                "notSubmitted" -> DurableOperationStatus.NOT_SUBMITTED
                "succeeded" -> DurableOperationStatus.SUCCEEDED
                "failed" -> DurableOperationStatus.FAILED
                "ambiguous" -> DurableOperationStatus.AMBIGUOUS
                else -> throw JSONException("reconciliation status")
            },
            value.optionalPayload("operationId"),
            value.requiredNonNegativeInt("candidateCount"),
        )
    }

    private fun JSONObject.requiredCorrelation(): String = requiredString("correlationId").also {
        if (!validCorrelation(it)) throw JSONException("correlation")
    }

    private fun JSONObject.requiredDurableKind(): DurableOperationKind = when (requiredString("kind")) {
        "lightning" -> DurableOperationKind.LIGHTNING
        "onchain" -> DurableOperationKind.ONCHAIN
        "ecashCreate" -> DurableOperationKind.ECASH_CREATE
        "ecashClaim" -> DurableOperationKind.ECASH_CLAIM
        else -> throw JSONException("operation kind")
    }

    private fun DurableOperationKind.nativeName(): String = when (this) {
        DurableOperationKind.LIGHTNING -> "lightning"
        DurableOperationKind.ONCHAIN -> "onchain"
        DurableOperationKind.ECASH_CREATE -> "ecashCreate"
        DurableOperationKind.ECASH_CLAIM -> "ecashClaim"
    }

    private fun validCorrelation(value: String) = value.matches(Regex("[0-9a-f]{32}"))
    private fun <T> invalidCorrelation(): NativeResult<T> =
        failure("invalid_argument", "The payment safety identifier is invalid.", false)

    private fun JSONObject.optionalPayload(key: String): String? =
        if (!has(key) || isNull(key)) null else requiredPayload(key)

    private fun <T> nativeCall(block: () -> T): NativeResult<T> {
        loadFailure?.let {
            return failure("native_library_unavailable", "Wallet services are not available in this build.", false)
        }
        return try {
            NativeResult.Success(block())
        } catch (_: JSONException) {
            invalidResponse()
        } catch (_: LinkageError) {
            failure("native_api_unavailable", "Wallet services could not be started.", false)
        } catch (_: IllegalArgumentException) {
            failure("invalid_argument", "Wallet request is invalid.", false)
        } catch (_: RuntimeException) {
            failure("native_call_failed", "Wallet services could not complete the request.", true)
        }
    }

    private fun boundedObject(json: String): JSONObject {
        if (json.toByteArray(Charsets.UTF_8).size > MAX_SNAPSHOT_JSON_BYTES) throw JSONException("too large")
        return JSONObject(json)
    }

    private fun JSONArray.strictStrings(expected: Int): List<String> {
        if (length() != expected) throw JSONException("invalid words")
        return (0 until length()).map { index ->
            getString(index).also { if (it.isBlank() || it.length > MAX_WORD_CHARS) throw JSONException("invalid word") }
        }
    }

    private fun parseBootstrap(json: String): NativeResult<BootstrapSession> {
        if (json.toByteArray(Charsets.UTF_8).size > MAX_BOOTSTRAP_JSON_BYTES) {
            return invalidResponse()
        }
        return try {
            val value = JSONObject(json)
            val version = value.requiredString("nativeVersion")
            val databaseHandle = value.requiredPositiveHandle("databaseHandle")
            when (value.requiredString("status")) {
                "uninitialized" -> NativeResult.Success(
                    BootstrapSession.Uninitialized(version, databaseHandle),
                )
                "ready" -> NativeResult.Success(
                    BootstrapSession.Ready(
                        version,
                        databaseHandle,
                        value.requiredPositiveHandle("factoryHandle"),
                    ),
                )
                else -> invalidResponse()
            }
        } catch (_: JSONException) {
            invalidResponse()
        }
    }

    private fun JSONObject.requiredString(key: String): String = getString(key).also {
        if (it.isBlank() || it.length > MAX_STRING_CHARS) throw JSONException("invalid field")
    }
    private fun JSONObject.requiredPayload(key: String): String = getString(key).also {
        if (it.isBlank() || it.length > MAX_PAYLOAD_CHARS) throw JSONException("invalid payload")
    }

    private fun JSONObject.requiredPositiveHandle(key: String): Long {
        val raw = get(key)
        if (raw !is Byte && raw !is Short && raw !is Int && raw !is Long) {
            throw JSONException("invalid handle")
        }
        return (raw as Number).toLong().also {
            if (it <= 0L) throw JSONException("invalid handle")
        }
    }

    private fun JSONObject.requiredNonNegativeLong(key: String): Long {
        val raw = get(key)
        if (raw !is Byte && raw !is Short && raw !is Int && raw !is Long) throw JSONException("invalid number")
        return (raw as Number).toLong().also { if (it < 0L) throw JSONException("invalid number") }
    }
    private fun JSONObject.requiredPositiveLong(key: String): Long = requiredNonNegativeLong(key).also {
        if (it == 0L) throw JSONException("positive number required")
    }

    private fun JSONObject.requiredNonNegativeInt(key: String): Int {
        val value = requiredNonNegativeLong(key)
        if (value > Int.MAX_VALUE) throw JSONException("invalid number")
        return value.toInt()
    }

    private fun invalidResponse() = failure(
        "invalid_native_response",
        "Wallet services returned an invalid response.",
        false,
    )

    private fun failure(code: String, message: String, retryable: Boolean) =
        NativeResult.Failure(AndroidError(code, message, retryable))

    private companion object {
        const val MAX_BOOTSTRAP_JSON_BYTES = 16 * 1024
        const val MAX_SNAPSHOT_JSON_BYTES = 256 * 1024
        const val MAX_STRING_CHARS = 128
        const val MAX_WORD_CHARS = 32
        const val EXACT_SEED_WORDS = 12
        const val MAX_FEDERATIONS = 64
        const val MAX_PAYMENTS = 256
        const val MAX_PAYLOAD_CHARS = 16 * 1024
        const val MAX_CONTACTS = 512
        const val MAX_GUARDIANS = 128
        const val MAX_CURRENCIES = 256
        const val MAX_ADDRESSES = 4096
        const val MAX_LNADDRESSES = 64
        const val MAX_LNADDR_SERVERS = 32
        const val MAX_LNADDR_DOMAINS_PER_SERVER = 32
        const val MAX_LNADDR_USERNAME_CHARS = 64
        const val MAX_LNADDR_DOMAIN_CHARS = 253
        val LNADDRESS_FIELDS = setOf("claimedAtSecs", "destination", "domain", "federationId", "isPrimary", "serverOrigin", "username")
        val LNADDR_QUOTE_FIELDS = setOf("priceMsat", "state", "reason")
    }
}
