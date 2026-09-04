# Cancellable asynchronous payment JNI contract

Critical wallet and network work now has `NativeRequestCallback` variants that
return a request ID immediately and run on the managed Rust runtime:

```kotlin
external fun prepareLightningSendAsync(clientHandle: Long, invoice: String, callback: NativeRequestCallback): Long
external fun executeLightningSendAsync(clientHandle: Long, quoteHandle: Long, callback: NativeRequestCallback): Long
external fun prepareOnchainSendAsync(clientHandle: Long, address: String, amountSat: Long, callback: NativeRequestCallback): Long
external fun executeOnchainSendAsync(clientHandle: Long, quoteHandle: Long, callback: NativeRequestCallback): Long
external fun receiveLightningAsync(clientHandle: Long, amountSat: Long, callback: NativeRequestCallback): Long
external fun receiveOnchainAsync(clientHandle: Long, callback: NativeRequestCallback): Long
external fun createEcashAsync(clientHandle: Long, amountSat: Long, callback: NativeRequestCallback): Long
external fun claimEcashAsync(clientHandle: Long, payload: String, callback: NativeRequestCallback): Long
external fun prepareLnurlQuoteAsync(clientHandle: Long, sessionHandle: Long, amountSat: Long, callback: NativeRequestCallback): Long
external fun prepareLnurlAsync(request: String, callback: NativeRequestCallback): Long
```

Federation management and related database/network reads use the same request
lifecycle:

```kotlin
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
external fun bootstrapAsync(filesDir: String, callback: NativeRequestCallback): Long
external fun createWalletAsync(databaseHandle: Long, callback: NativeRequestCallback): Long
external fun restoreWalletAsync(databaseHandle: Long, wordsJson: String, callback: NativeRequestCallback): Long
external fun seedWordsAsync(factoryHandle: Long, callback: NativeRequestCallback): Long
external fun receiveLnurlAsync(clientHandle: Long, callback: NativeRequestCallback): Long
```

Success DTOs are identical to their synchronous counterparts. Inputs are copied
into bounded Rust-owned values before the JNI call returns. These paths contain
no nested `block_on`; synchronous exports remain temporarily for migration.

`cancelRequest` is exactly-once callback cancellation, not transaction
cancellation. If cancellation wins before preparation delivers a quote, any
newly created undelivered quote handle is explicitly closed. Suppressed JSON,
including invoices and ecash payloads, is zeroized when dropped.
LNURL limits resolution also runs on this boundary with a bounded Rust-owned
request string. If cancellation suppresses a successfully resolved response,
the newly created parsed LNURL session handle is closed before it can leak.

Once `executeLightningSendAsync` or `executeOnchainSendAsync` atomically begins a
one-shot quote, cancellation only suppresses its callback. It does not abort the
wallet operation and never restores the quote to a retryable state. The app must
mark the action as submitted, refresh payment history/status, and reconcile by
operation ID or wallet state before offering another payment. The same rule
applies to an ecash claim whose submission may already have reached the wallet.

Join/recover and leave are also durable lifecycle mutations once their worker
has begun. A cancelled callback must not cause an automatic retry. Rebuild from
`walletSnapshotAsync` to determine whether the federation was added, selected,
or removed. Contact/currency/address recheck mutations likewise reconcile from
their corresponding list/snapshot endpoint after ambiguous cancellation.

Bootstrap is serialized and idempotent across concurrent callers. Cancelling a
bootstrap/create/restore callback after initialization begins does not reset or
repeat database initialization. Restart with `bootstrapAsync`: cached native
state reports either `uninitialized` or the authoritative ready factory. Never
automatically repeat create/restore after ambiguous cancellation. Seed words and
LNURL receive payloads are zeroized when their terminal callback is suppressed.

`listFiatCurrencies`, seed prefix/checksum validation, and input classification
remain synchronous because they are bounded, in-memory, CPU-only operations
with no database, network, or wallet executor work.

Receive/create results cancelled after completion are discarded and zeroized;
the UI can safely request a new receive artifact or create flow according to its
screen state. LNURL quote cancellation consumes the one-shot LNURL session and
closes an undelivered Lightning quote, so the user must restart LNURL resolution.

## Process-owned session shutdown

`shutdownAndroidSession(callback)` is the single application-session teardown
boundary. It cancels outstanding requests, closes subscriptions and transient
codec/quote state, awaits client shutdown, then releases factory/database
handles and resets bootstrap state. It is idempotent. A later `bootstrapAsync`
can reopen the same database; old handles remain stale through registry
generations.

The application-scoped `WalletBootstrapRepository` owns this lifetime. Do not
call shutdown for Activity/Fragment/ViewModel recreation, configuration changes,
or ordinary background/foreground transitions. Call it only when intentionally
disposing the process-owned wallet repository.
