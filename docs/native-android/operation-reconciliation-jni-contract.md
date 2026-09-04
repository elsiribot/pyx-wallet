# Irreversible operation reconciliation JNI contract

All methods are asynchronous and use `NativeRequestCallback`. Correlation IDs
are random 128-bit lowercase hexadecimal strings. They are not payment IDs.

Kotlin generates each ID with `SecureRandom`, commits that same non-secret ID
to its local operation journal, and passes it into the execute call. Native code
validates exactly 32 lowercase/uppercase hex characters, rejects reuse, persists
its record before invoking Fedimint, and echoes it in the response:

```json
{"correlationId":"00112233445566778899aabbccddeeff","operationId":"…"}
```

The changed execute signatures are:

```kotlin
external fun executeLightningSendAsync(client: Long, quote: Long, correlationId: String, callback: NativeRequestCallback): Long
external fun executeOnchainSendAsync(client: Long, quote: Long, correlationId: String, callback: NativeRequestCallback): Long
external fun createEcashAsync(client: Long, amountSat: Long, correlationId: String, callback: NativeRequestCallback): Long
external fun claimEcashAsync(client: Long, payload: String, correlationId: String, callback: NativeRequestCallback): Long
```

`operationId` is nullable only where the installed Fedimint module does not
return it from the submission call. Existing fields such as `feeSat`, `payload`
and `amountSat` are unchanged.

```kotlin
external fun pendingOperationsAsync(client: Long, callback: NativeRequestCallback): Long
external fun reconcileOperationAsync(client: Long, correlationId: String, kind: String, callback: NativeRequestCallback): Long
external fun clearOperationAsync(client: Long, correlationId: String, callback: NativeRequestCallback): Long
```

`pendingOperationsAsync` returns an array of:

```json
{"correlationId":"…","kind":"lightning|onchain|ecashCreate|ecashClaim","status":"submitted"}
```

`reconcileOperationAsync` returns:

```json
{"correlationId":"…","kind":"onchain","status":"inFlight|notSubmitted|pending|succeeded|failed|ambiguous","operationId":"… or null","candidateCount":1}
```

The kind is one of `lightning`, `onchain`, `ecashCreate`, or `ecashClaim` and
allows native code to return a typed `notSubmitted` result when Android's local
journal was committed but the process died before the native database insert.

`notSubmitted`, `succeeded`, and `failed` are conclusive. `inFlight`, `pending`,
and `ambiguous` must keep retry disabled. The UI may poll reconciliation after a
history refresh. It may call `clearOperationAsync` only after displaying a
conclusive result; clearing an unresolved record is intentionally not a retry
mechanism.

The DB journal stores only version/kind/time, amount/fee, SHA-256 destination
fingerprint, pre-submission operation IDs, random correlation ID, and the
Fedimint operation ID when returned synchronously. It never stores an invoice,
address, ecash payload, token, preimage, or other payment secret. LN v1/v2,
mint v1/v2, and wallet v1 embed the random marker in Fedimint custom metadata.
Wallet v2 has no custom metadata parameter, so it is reconciled against the
pre-submit baseline using destination fingerprint, value, and fee. Zero
candidates is `notSubmitted`; more than one is always `ambiguous`.

All native submissions share a process-wide mutex. This makes baseline capture,
journal commit, and submission ordering deterministic even when multiple UI
entry points race.
