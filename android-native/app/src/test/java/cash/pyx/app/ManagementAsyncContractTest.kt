package cash.pyx.app

import cash.pyx.app.nativeapi.JniNativeWalletApi
import cash.pyx.app.nativeapi.NativeRequestCallback
import cash.pyx.app.nativeapi.NativeResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ManagementAsyncContractTest {
    private val snapshot = """{"currencyCode":"USD","federations":[],"selected":null}"""

    @Test fun `early join and selection callbacks are retained`() = runTest {
        val api = JniNativeWalletApi(libraryLoader = {},
            joinAsyncBinding = { _, _, _, callback -> callback.onSuccess(20, snapshot); 20 },
            snapshotForAsyncBinding = { _, _, callback -> callback.onSuccess(21, snapshot); 21 })
        assertTrue(api.joinFederationAsync(1, "fedimint:invite", false) is NativeResult.Success)
        assertTrue(api.walletSnapshotForAsync(1, "federation") is NativeResult.Success)
    }

    @Test fun `cancel durable leave suppresses late success and cancels once`() = runTest {
        lateinit var callback: NativeRequestCallback
        var cancellations = 0
        var delivered = false
        val api = JniNativeWalletApi(libraryLoader = {}, leaveAsyncBinding = { _, _, value -> callback = value; 22 },
            cancelRequestBinding = { cancellations++; "{\"cancelled\":true}" })
        val job = launch { api.leaveFederationAsync(1, "federation"); delivered = true }
        testScheduler.runCurrent(); job.cancelAndJoin()
        callback.onSuccess(22, "{\"left\":true}")
        assertEquals(1, cancellations)
        assertTrue(!delivered)
    }

    @Test fun `early contact and payment detail DTOs remain strict`() = runTest {
        val api = JniNativeWalletApi(libraryLoader = {}, contactsAsyncBinding = { _, callback ->
            callback.onSuccess(23, "{\"contacts\":[]}"); 23
        }, paymentDetailsAsyncBinding = { _, _, callback ->
            callback.onError(24, "not_found", "Payment not found.", false); 24
        })
        assertEquals(0, (api.listContactsAsync(1) as NativeResult.Success).value.contacts.size)
        assertTrue(api.paymentDetailsAsync(1, "missing") is NativeResult.Failure)
    }
}
