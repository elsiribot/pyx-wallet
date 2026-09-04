# Live payment subscription JNI contract

```kotlin
internal object NativeBindings {
    external fun subscribePayments(
        clientHandle: Long,
        callback: NativeSubscriptionCallback,
    ): Long

    external fun closeSubscription(subscriptionHandle: Long): String
}
```

The initial callback and every update contain a newest-first state snapshot and
an optional one-shot notification:

```json
{
  "payments": [
    {
      "operationId": "stable-id",
      "type": "lightning",
      "direction": "outgoing",
      "amountSat": 1000,
      "feeSat": 10,
      "timestampMillis": 1800000000000,
      "status": "succeeded",
      "fiatAmount": "0.95",
      "fiatCurrencyCode": "USD"
    }
  ],
  "notification": {
    "direction": "outgoing",
    "success": true,
    "amountSat": 1000,
    "type": "lightning"
  }
}
```

`type` is `lightning`, `onchain`, or `ecash`; `direction` is `incoming` or
`outgoing`; `status` is `pending`, `succeeded`, or `failed`. `feeSat`, frozen
`fiatAmount`, and `fiatCurrencyCode` are nullable. Fiat fields appear only as a
valid pair, and fiat crosses JNI as a decimal string.

The list is capped at 100 entries and the serialized callback is capped at 256
KiB. Operation IDs are exact, stable, and limited to 256 bytes. Pending and
terminal records are folded under the same operation ID. Already-ready updates
are conflated to the latest state snapshot; the optional notification belongs
to that delivered update. A refresh should use the delivered snapshot rather
than incrementally mutating an independent Kotlin history list.

Summary callbacks intentionally never contain ecash payloads, preimages,
addresses, or transaction IDs. Explicit payment-details UI continues using its
separate, user-selected endpoint. The subscription shares the generation-safe,
idempotent `closeSubscription` contract: close waits for an in-flight callback
and no callback begins after it returns.
