# LNURL payment fee-review contract

LNURL payment is a three-step, explicitly confirmed flow:

1. `prepareLnurl(request)` fetches limits and returns a parsed session handle.
2. `prepareLnurlQuote(clientHandle, sessionHandle, amountSat)` validates the
   amount, resolves the BOLT11 invoice, calculates fees, and returns a typed
   Lightning quote without paying.
3. After the user reviews amount, fee, gateway, and direct-routing status,
   `executeLightningSend(clientHandle, quoteHandle)` performs the payment once.

```kotlin
external fun prepareLnurlQuote(
    clientHandle: Long,
    sessionHandle: Long,
    amountSat: Long,
): String
```

The returned JSON is exactly the existing Lightning quote DTO:

```json
{
  "quoteHandle": 42,
  "amountSat": 1000,
  "feeSat": 10,
  "gatewayUrl": "https://gateway.example",
  "direct": false
}
```

The LNURL session is generation-safe and one-shot. The first quote attempt
atomically consumes it; range errors, invoice-resolution failures, invalid
client handles, fee failures, panics, and successful quote creation all close
the session. Concurrent or repeated attempts fail without resolving twice.
The produced Lightning quote is owner-bound to the client used for preparation
and inherits the existing concurrent one-winner execution semantics.

`sendLnurl` remains exported temporarily for binary compatibility but is
deprecated and always returns a bounded `invalid_argument` error. It never
resolves or pays. New Kotlin code must use `prepareLnurlQuote` followed by an
explicit confirmation and `executeLightningSend`.
