# Asynchronous JNI callback contract

The first asynchronous bridge covers wallet and connection snapshots while their synchronous
exports remain available during migration. Calls return a positive request ID immediately and never
perform wallet I/O on Android's calling thread.

```kotlin
package cash.pyx.app.nativeapi

internal interface NativeRequestCallback {
    fun onSuccess(requestId: Long, json: String)
    fun onError(
        requestId: Long,
        code: String,
        message: String,
        retryable: Boolean,
    )
}

internal object NativeBindings {
    external fun walletSnapshotAsync(
        factoryHandle: Long,
        callback: NativeRequestCallback,
    ): Long

    external fun connectionStatusAsync(
        clientHandle: Long,
        callback: NativeRequestCallback,
    ): Long

    external fun cancelRequest(requestId: Long): String
}
```

`cancelRequest` returns `{"cancelled":true}` when cancellation wins the terminal race, otherwise
`{"cancelled":false}`. Every accepted request attempts exactly one terminal callback. Cancellation
uses `onError(requestId, "cancelled", "The request was cancelled.", false)` and suppresses a
later success/error callback. Callback JSON uses the same bounded, redacted contracts as the
synchronous `walletSnapshot` and `connectionStatus` calls. Rust attaches worker threads to the JVM
before invoking callbacks and clears callback-thrown Java exceptions at the boundary.
