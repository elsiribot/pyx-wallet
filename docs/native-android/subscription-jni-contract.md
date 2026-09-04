# Native callback subscription contract

Polling remains available until these streams are proven in the Kotlin repository. New subscriptions
return a positive generation-safe handle immediately.

```kotlin
package cash.pyx.app.nativeapi

internal interface NativeSubscriptionCallback {
    fun onEvent(subscriptionHandle: Long, json: String)
    fun onError(
        subscriptionHandle: Long,
        code: String,
        message: String,
        retryable: Boolean,
    )
}

internal object NativeBindings {
    external fun subscribeBalance(
        clientHandle: Long,
        callback: NativeSubscriptionCallback,
    ): Long

    external fun subscribeConnection(
        clientHandle: Long,
        callback: NativeSubscriptionCallback,
    ): Long

    external fun closeSubscription(subscriptionHandle: Long): String
}
```

Balance events are `{"balanceSat":Long}`. Connection events are
`{"guardians":[{"name":String,"connected":Boolean}],"onlineCount":Int,"totalCount":Int,
"state":"connected"|"degraded"|"offline"}`. Fedimint's underlying watch-style streams coalesce
intermediate changes to their latest state when a consumer is slow; balance state is therefore never
represented as a lossy delta. Guardian collections and names are bounded and no sensitive payment
data is included.

`closeSubscription` returns `{"closed":true}` and is idempotent for the same handle generation. It
waits for an in-flight callback to return; no callback can start after close returns. Wrong handle
types and stale generations fail safely.
