package cash.pyx.app

import cash.pyx.app.nativeapi.*
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AsyncNativeApiTest {
    private val snapshotJson = """{"currencyCode":"EUR","federations":[],"selected":null}"""

    @Test fun callbackBeforeBindingReturnsIsDeliveredExactlyOnce() = runTest {
        val api = JniNativeWalletApi(libraryLoader = {}, walletSnapshotAsyncBinding = { _, callback ->
            callback.onSuccess(7, snapshotJson)
            callback.onError(7, "late", "Late callback", false)
            7
        })
        val result = api.walletSnapshotAsync(1) as NativeResult.Success
        assertEquals("EUR", result.value.currencyCode)
    }

    @Test fun errorIsTypedAndLateSuccessIsSuppressed() = runTest {
        val api = JniNativeWalletApi(libraryLoader = {}, walletSnapshotAsyncBinding = { _, callback ->
            callback.onError(8, "offline", "Wallet is offline.", true)
            callback.onSuccess(8, snapshotJson)
            8
        })
        val result = api.walletSnapshotAsync(1) as NativeResult.Failure
        assertEquals("offline", result.error.code)
        assertTrue(result.error.retryable)
    }

    @Test fun earlyCallbackWithMismatchedReturnedIdIsRejected() = runTest {
        val api = JniNativeWalletApi(libraryLoader = {}, walletSnapshotAsyncBinding = { _, callback ->
            callback.onSuccess(70, snapshotJson)
            71
        })
        val result = api.walletSnapshotAsync(1) as NativeResult.Failure
        assertEquals("invalid_native_response", result.error.code)
    }

    @Test fun coroutineCancellationPropagatesRequestCancellationAndSuppressesLateCallback() = runTest {
        var callback: NativeRequestCallback? = null
        var cancelled = 0L
        val api = JniNativeWalletApi(
            libraryLoader = {},
            walletSnapshotAsyncBinding = { _, value -> callback = value; 9 },
            cancelRequestBinding = { id -> cancelled = id; """{"cancelled":true}""" },
        )
        val job = launch { api.walletSnapshotAsync(1) }
        testScheduler.runCurrent()
        job.cancelAndJoin()
        assertEquals(9L, cancelled)
        callback!!.onSuccess(9, snapshotJson)
        assertTrue(job.isCancelled)
    }

    @Test fun connectionAsyncUsesStrictConnectionParser() = runTest {
        val json = """{"guardians":[{"name":"G","connected":true}],"onlineCount":1,"totalCount":1,"requiredCount":1,"state":"connected"}"""
        val api = JniNativeWalletApi(libraryLoader = {}, connectionStatusAsyncBinding = { _, callback ->
            callback.onSuccess(10, json); 10
        })
        val result = api.connectionStatusAsync(2) as NativeResult.Success
        assertEquals(ConnectionState.CONNECTED, result.value.state)
    }
}
