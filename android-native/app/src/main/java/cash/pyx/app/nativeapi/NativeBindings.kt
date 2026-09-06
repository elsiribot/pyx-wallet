package cash.pyx.app.nativeapi

/** The deliberately small handwritten JNI entry point. Add bindings here as Rust APIs land. */
internal object NativeBindings {
    external fun nativeVersion(): String
    external fun seedWordSuggestions(prefix: String): String
    external fun validateSeedPhrase(wordsJson: String): String
    external fun createEcashEncoder(payload: String): Long
    external fun nextEcashFragment(encoderHandle: Long): String
    external fun createEcashDecoder(): Long
    external fun addEcashFragment(decoderHandle: Long, fragment: String): String
    external fun closeEcashCodec(handle: Long, kind: String): String
    external fun listFiatCurrencies(): String
    external fun classifyInput(payload: String): String
    external fun parseBitcoinPayment(payload: String): String
    external fun closeTransientHandle(handle: Long, kind: String): String
    external fun walletSnapshotAsync(factoryHandle: Long, callback: NativeRequestCallback): Long
    external fun connectionStatusAsync(clientHandle: Long, callback: NativeRequestCallback): Long
    external fun recoveryExpirySnapshotAsync(clientHandle: Long, callback: NativeRequestCallback): Long
    external fun cancelRequest(requestId: Long): String
    external fun prepareLightningSendAsync(clientHandle: Long, invoice: String, callback: NativeRequestCallback): Long
    external fun executeLightningSendAsync(clientHandle: Long, quoteHandle: Long, correlationId: String, callback: NativeRequestCallback): Long
    external fun prepareOnchainSendAsync(clientHandle: Long, address: String, amountSat: Long, callback: NativeRequestCallback): Long
    external fun executeOnchainSendAsync(clientHandle: Long, quoteHandle: Long, correlationId: String, callback: NativeRequestCallback): Long
    external fun receiveLightningAsync(clientHandle: Long, amountSat: Long, callback: NativeRequestCallback): Long
    external fun receiveOnchainAsync(clientHandle: Long, callback: NativeRequestCallback): Long
    external fun createEcashAsync(clientHandle: Long, amountSat: Long, correlationId: String, callback: NativeRequestCallback): Long
    external fun claimEcashAsync(clientHandle: Long, payload: String, correlationId: String, callback: NativeRequestCallback): Long
    external fun pendingOperationsAsync(clientHandle: Long, callback: NativeRequestCallback): Long
    external fun reconcileOperationAsync(clientHandle: Long, correlationId: String, kind: String, callback: NativeRequestCallback): Long
    external fun clearOperationAsync(clientHandle: Long, correlationId: String, callback: NativeRequestCallback): Long
    external fun prepareLnurlQuoteAsync(clientHandle: Long, sessionHandle: Long, amountSat: Long, callback: NativeRequestCallback): Long
    external fun prepareLnurlAsync(request: String, callback: NativeRequestCallback): Long
    external fun joinFederationAsync(factoryHandle: Long, invite: String, recover: Boolean, callback: NativeRequestCallback): Long
    external fun walletSnapshotForAsync(factoryHandle: Long, federationId: String, callback: NativeRequestCallback): Long
    external fun leaveFederationAsync(factoryHandle: Long, federationId: String, callback: NativeRequestCallback): Long
    external fun federationDetailsAsync(clientHandle: Long, callback: NativeRequestCallback): Long
    external fun listContactsAsync(factoryHandle: Long, callback: NativeRequestCallback): Long
    external fun saveContactAsync(factoryHandle: Long, lnurl: String, name: String, callback: NativeRequestCallback): Long
    external fun deleteContactAsync(factoryHandle: Long, lnurl: String, callback: NativeRequestCallback): Long
    external fun setCurrencyAsync(factoryHandle: Long, code: String, callback: NativeRequestCallback): Long
    external fun onchainAddressesAsync(clientHandle: Long, callback: NativeRequestCallback): Long
    external fun recheckOnchainAddressAsync(clientHandle: Long, tweakIndex: Long, callback: NativeRequestCallback): Long
    external fun paymentDetailsAsync(clientHandle: Long, operationId: String, callback: NativeRequestCallback): Long
    external fun paymentHistoryPageAsync(clientHandle: Long, cursor: String, pageSize: Int, callback: NativeRequestCallback): Long
    external fun bootstrapAsync(filesDir: String, callback: NativeRequestCallback): Long
    external fun createWalletAsync(databaseHandle: Long, callback: NativeRequestCallback): Long
    external fun restoreWalletAsync(databaseHandle: Long, wordsJson: String, callback: NativeRequestCallback): Long
    external fun seedWordsAsync(factoryHandle: Long, callback: NativeRequestCallback): Long
    external fun receiveLnurlAsync(clientHandle: Long, callback: NativeRequestCallback): Long
    external fun lnaddrSnapshotAsync(factoryHandle: Long, callback: NativeRequestCallback): Long
    external fun lnaddrDiscoverAsync(factoryHandle: Long, callback: NativeRequestCallback): Long
    external fun lnaddrQuoteAsync(factoryHandle: Long, origin: String, domain: String, username: String, callback: NativeRequestCallback): Long
    external fun lnaddrClaimAsync(clientHandle: Long, origin: String, domain: String, username: String, callback: NativeRequestCallback): Long
    external fun lnaddrSetPrimaryAsync(factoryHandle: Long, domain: String, username: String, callback: NativeRequestCallback): Long
    external fun lnaddrReleaseAsync(factoryHandle: Long, domain: String, username: String, callback: NativeRequestCallback): Long
    external fun lnaddrRepointAsync(clientHandle: Long, domain: String, username: String, callback: NativeRequestCallback): Long
    external fun lnaddrRecoverAsync(factoryHandle: Long, callback: NativeRequestCallback): Long
    external fun shutdownAndroidSession(callback: NativeRequestCallback): Long
    external fun subscribeBalance(clientHandle: Long, callback: NativeSubscriptionCallback): Long
    external fun subscribeConnection(clientHandle: Long, callback: NativeSubscriptionCallback): Long
    external fun subscribeRecovery(clientHandle: Long, callback: NativeSubscriptionCallback): Long
    external fun subscribePayments(clientHandle: Long, callback: NativeSubscriptionCallback): Long
    external fun closeSubscription(subscriptionHandle: Long): String
    external fun satsToFiat(clientHandle: Long, amountSat: Long): String?
    external fun fiatToSatsAsync(
        clientHandle: Long,
        amountDecimal: String,
        callback: NativeRequestCallback,
    ): Long
}

internal interface NativeRequestCallback {
    fun onSuccess(requestId: Long, json: String)
    fun onError(requestId: Long, code: String, message: String, retryable: Boolean)
}

interface NativeSubscriptionCallback {
    fun onEvent(subscriptionHandle: Long, json: String)
    fun onError(subscriptionHandle: Long, code: String, message: String, retryable: Boolean)
}
