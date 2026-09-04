package cash.pyx.app

import cash.pyx.app.nativeapi.FiatDecimal
import cash.pyx.app.nativeapi.JniNativeWalletApi
import cash.pyx.app.nativeapi.NativeRequestCallback
import cash.pyx.app.nativeapi.NativeResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FiatConversionTest {
    @Test fun `canonical fiat formatting rejects signs exponents zero and redundant zeros`() {
        assertTrue(FiatDecimal.isCanonical("12.34"))
        listOf("", "0", "00.1", "+1", "-1", "1.", "1e2", " 1").forEach { assertTrue(!FiatDecimal.isCanonical(it)) }
    }

    @Test fun `cached conversion supports unavailable and strictly parses display`() {
        val unavailable = JniNativeWalletApi(libraryLoader = {}, satsToFiatBinding = { _, _ -> null })
        assertNull((unavailable.satsToFiat(1, 2) as NativeResult.Success).value)
        val valid = JniNativeWalletApi(libraryLoader = {}, satsToFiatBinding = { _, _ ->
            """{"amountDecimal":"12.34","currencyCode":"USD","currencyName":"Dollar","currencySymbol":"$","decimalDigits":2}"""
        })
        assertEquals("12.34", (valid.satsToFiat(1, 2) as NativeResult.Success).value!!.amountDecimal)
        val invalid = JniNativeWalletApi(libraryLoader = {}, satsToFiatBinding = { _, _ ->
            """{"amountDecimal":"12.345","currencyCode":"USD","currencyName":"Dollar","currencySymbol":"$","decimalDigits":2}"""
        })
        assertTrue(invalid.satsToFiat(1, 2) is NativeResult.Failure)
    }

    @Test fun `async conversion retains early callback and rejects invalid response`() = runTest {
        val valid = JniNativeWalletApi(libraryLoader = {}, fiatToSatsAsyncBinding = { _, _, callback ->
            callback.onSuccess(4, """{"amountSat":1234,"currencyCode":"USD"}"""); 4
        })
        assertEquals(1234L, (valid.fiatToSatsAsync(1, "12.34") as NativeResult.Success).value.amountSat)
        val invalid = JniNativeWalletApi(libraryLoader = {}, fiatToSatsAsyncBinding = { _, _, callback ->
            callback.onSuccess(5, """{"amountSat":0,"currencyCode":"USD"}"""); 5
        })
        assertTrue(invalid.fiatToSatsAsync(1, "1") is NativeResult.Failure)
    }

    @Test fun `cancelling async conversion cancels native request exactly once`() = runTest {
        lateinit var callback: NativeRequestCallback
        var cancellations = 0
        val api = JniNativeWalletApi(
            libraryLoader = {},
            fiatToSatsAsyncBinding = { _, _, cb -> callback = cb; 8 },
            cancelRequestBinding = { cancellations++; "{\"cancelled\":true}" },
        )
        val job = launch { api.fiatToSatsAsync(1, "2.50") }
        testScheduler.runCurrent()
        job.cancelAndJoin()
        callback.onSuccess(8, """{"amountSat":25,"currencyCode":"USD"}""")
        assertEquals(1, cancellations)
    }
}
