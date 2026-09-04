# BIP21 send contract

The existing JNI signature is unchanged. `destination` may be either a plain
Bitcoin address or a `bitcoin:` URI. `amountSat == 0` means “not supplied by the
user” and is valid only when the URI contains an amount.

```kotlin
external fun parseBitcoinPayment(payload: String): String

external fun prepareOnchainSend(
    clientHandle: Long,
    destination: String,
    amountSat: Long,
): String
```

The prepared quote now returns:

```json
{
  "quoteHandle": 8,
  "amountSat": 123456789,
  "feeSat": 250,
  "address": "bc1q...",
  "uriAmountSat": 123456789,
  "amountLocked": true,
  "label": "Alice Shop",
  "message": "Order 42"
}
```

`address` is canonical and contains no URI query. `uriAmountSat`, `label`, and
`message` are nullable. `amountLocked` is true exactly when the URI supplied the
amount, allowing Compose to prefill and lock the amount while showing metadata
on the review screen. Label and message are decoded strictly and bounded to 256
Unicode characters each.

URI BTC amounts are parsed directly into integer satoshis with at most eight
fractional digits; no floating-point value crosses or participates in parsing.
Zero, negative, exponent-form, over-precise, and greater-than-21-million-BTC
amounts are rejected. Duplicate `amount` parameters are rejected even if equal.
If Kotlin also supplies a positive amount, it must exactly equal the URI amount.
Malformed percent escapes, invalid UTF-8, duplicate metadata fields, fragments,
and unknown `req-*` parameters are rejected.

Unknown optional parameters are ignored per BIP21. Plain addresses retain the
old behavior and require a positive Kotlin `amountSat`. The existing native fee
preparation remains the final network/address validation step before a typed,
one-shot quote handle is returned.

`classifyInput` remains frozen as `{\"type\":\"bitcoin\"}` and never echoes any
destination or metadata. After classification, `parseBitcoinPayment` returns a
typed preview containing canonical `address`, nullable `amountSat`, nullable
`label`/`message`, and `isUri`. The prepared quote repeats these reviewed fields
so the amount and destination executed by the one-shot handle are explicit.
