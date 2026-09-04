# Fiat conversion JNI contract

Fiat values cross JNI only as canonical decimal strings. Kotlin must never pass
or receive a `Double` for a fiat amount.

```kotlin
internal object NativeBindings {
    /** Null means that no fresh cached exchange rate is currently available. */
    external fun satsToFiat(clientHandle: Long, amountSat: Long): String?

    /** Uses NativeRequestCallback; cancelRequest(requestId) remains supported. */
    external fun fiatToSatsAsync(
        clientHandle: Long,
        amountDecimal: String,
        callback: NativeRequestCallback,
    ): Long
}
```

`satsToFiat` returns JSON such as:

```json
{"amountDecimal":"12.34","currencyCode":"USD","currencyName":"United States Dollar","currencySymbol":"$","decimalDigits":2}
```

The synchronous call reads only the fresh native cache and never performs
network or blocking work. A missing or stale rate returns Kotlin `null`.

`fiatToSatsAsync` fetches or reuses the cached rate off the calling thread and
delivers terminal JSON through the existing callback:

```json
{"amountSat":1234,"currencyCode":"USD"}
```

Input is canonical unsigned base-10 notation: an integer part with no redundant
leading zero, optionally followed by `.` and no more fractional digits than the
selected currency supports. Whitespace, signs, exponents, NaN/infinity, empty
fractions, zero, negative values, values below one sat after rounding, and
values above 21 million BTC are rejected. Conversion rounds to the nearest sat.
Errors never echo the supplied amount. `cancelRequest` uses the same terminal,
exactly-once cancellation contract as the snapshot APIs.
