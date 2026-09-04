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
class PaymentAsyncContractTest {
    @Test fun `early receive callback is retained and strictly parsed`() = runTest {
        val api = JniNativeWalletApi(libraryLoader = {}, receiveLightningAsyncBinding = { _, _, callback ->
            callback.onSuccess(7, """{"type":"lightning","payload":"invoice","amountSat":10,"feeSat":1,"gatewayUrl":"https://gateway","expiresAtEpochSeconds":1800000000}"""); 7
        })
        val result = api.receiveLightningAsync(1, 10) as NativeResult.Success
        assertEquals("invoice", result.value.payload)
        assertEquals(1_800_000_000L, result.value.expiresAtEpochSeconds)
    }

    @Test fun `receive rejects a missing authoritative invoice expiry`() = runTest {
        val api = JniNativeWalletApi(libraryLoader = {}, receiveLightningAsyncBinding = { _, _, callback ->
            callback.onSuccess(8, """{"type":"lightning","payload":"invoice","amountSat":10,"feeSat":1,"gatewayUrl":"https://gateway"}"""); 8
        })
        assertTrue(api.receiveLightningAsync(1, 10) is NativeResult.Failure)
    }

    @Test fun `cancel during irreversible execute sends one cancellation and ignores late success`() = runTest {
        lateinit var callback: NativeRequestCallback
        var cancellations = 0
        var completed = false
        val api = JniNativeWalletApi(
            libraryLoader = {}, executeLightningAsyncBinding = { _, _, _, value -> callback = value; 9 },
            cancelRequestBinding = { cancellations++; "{\"cancelled\":true}" },
        )
        val correlation = "00112233445566778899aabbccddeeff"
        val job = launch { api.executeLightningSendAsync(1, 4, correlation); completed = true }
        testScheduler.runCurrent(); job.cancelAndJoin()
        callback.onSuccess(9, """{"operationId":"late","feeSat":1,"correlationId":"$correlation"}""")
        assertEquals(1, cancellations)
        assertTrue(!completed)
    }

    @Test fun `early onchain quote callback preserves typed metadata`() = runTest {
        val api = JniNativeWalletApi(libraryLoader = {}, prepareOnchainAsyncBinding = { _, _, _, callback ->
            callback.onSuccess(11, """{"quoteHandle":8,"amountSat":200,"feeSat":3,"address":"bc1","uriAmountSat":200,"amountLocked":true,"label":"Shop","message":null}"""); 11
        })
        val quote = (api.prepareOnchainSendAsync(1, "bitcoin:opaque", 200) as NativeResult.Success).value
        assertTrue(quote.amountLocked)
        assertEquals("Shop", quote.label)
    }

    @Test fun `cancel during ecash creation cancels callback and suppresses late token`() = runTest {
        lateinit var callback: NativeRequestCallback
        var cancellations = 0
        var delivered = false
        val api = JniNativeWalletApi(libraryLoader = {}, createEcashAsyncBinding = { _, _, _, value -> callback = value; 12 },
            cancelRequestBinding = { cancellations++; "{\"cancelled\":true}" })
        val correlation = "00112233445566778899aabbccddeeff"
        val job = launch { api.createEcashAsync(1, 50, correlation); delivered = true }
        testScheduler.runCurrent(); job.cancelAndJoin()
        callback.onSuccess(12, """{"payload":"fedimint1secret","amountSat":50,"correlationId":"$correlation","operationId":null}""")
        assertEquals(1, cancellations)
        assertTrue(!delivered)
    }

    @Test fun `reconciliation passes typed kind and strictly parses a conclusive result`() = runTest {
        var encodedKind = ""
        val correlation = "00112233445566778899aabbccddeeff"
        val api = JniNativeWalletApi(libraryLoader = {}, reconcileOperationAsyncBinding = { _, _, kind, callback ->
            encodedKind = kind
            callback.onSuccess(13, """{"correlationId":"$correlation","kind":"onchain","status":"notSubmitted","operationId":null,"candidateCount":0}""")
            13
        })
        val result = api.reconcileOperationAsync(1, correlation, cash.pyx.app.nativeapi.DurableOperationKind.ONCHAIN)
        assertTrue(result is NativeResult.Success)
        assertEquals("onchain", encodedKind)
        assertEquals(cash.pyx.app.nativeapi.DurableOperationStatus.NOT_SUBMITTED, (result as NativeResult.Success).value.status)
    }

    @Test fun `pending operation parser preserves multiple native records for conservative handling`() = runTest {
        val first = "00112233445566778899aabbccddeeff"
        val second = "ffeeddccbbaa99887766554433221100"
        val api = JniNativeWalletApi(libraryLoader = {}, pendingOperationsAsyncBinding = { _, callback ->
            callback.onSuccess(14, """[{"correlationId":"$first","kind":"lightning","status":"submitted"},{"correlationId":"$second","kind":"ecashClaim","status":"submitted"}]""")
            14
        })
        val result = api.pendingOperationsAsync(1) as NativeResult.Success
        assertEquals(2, result.value.operations.size)
    }
}
