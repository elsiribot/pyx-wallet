package cash.pyx.app

import cash.pyx.app.nativeapi.JniNativeWalletApi
import cash.pyx.app.nativeapi.NativeRequestCallback
import cash.pyx.app.nativeapi.NativeResult
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StartupAsyncContractTest {
    @Test fun `early bootstrap callback is retained`() = runTest {
        val api = JniNativeWalletApi(libraryLoader = {}, bootstrapAsyncBinding = { _, callback ->
            callback.onSuccess(31, """{"status":"ready","nativeVersion":"1","databaseHandle":7,"factoryHandle":9}"""); 31
        })
        val result = api.bootstrapAsync("/files") as NativeResult.Success
        assertEquals(9, (result.value as cash.pyx.app.nativeapi.BootstrapSession.Ready).factoryHandle)
    }

    @Test fun `cancelled create cancels once and ignores late seed`() = runTest {
        lateinit var callback: NativeRequestCallback
        var cancellations = 0
        var delivered = false
        val api = JniNativeWalletApi(
            libraryLoader = {}, createAsyncBinding = { _, value -> callback = value; 32 },
            cancelRequestBinding = { cancellations++; "{\"cancelled\":true}" },
        )
        val job = launch { api.createWalletAsync(7); delivered = true }
        testScheduler.runCurrent(); job.cancelAndJoin()
        callback.onSuccess(32, """{"factoryHandle":9,"seedWords":["a","b","c","d","e","f","g","h","i","j","k","l"]}""")
        assertEquals(1, cancellations)
        assertFalse(delivered)
    }
}
