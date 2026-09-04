package cash.pyx.app

import cash.pyx.app.nativeapi.*
import org.junit.Assert.*
import org.junit.Test

class InputClassificationContractTest {
    @Test fun parsesEveryFrozenInputType() {
        val expected = mapOf(
            "ecash" to InputType.ECASH, "invite" to InputType.INVITE, "lightning" to InputType.LIGHTNING,
            "bitcoin" to InputType.BITCOIN, "lnurl" to InputType.LNURL, "unknown" to InputType.UNKNOWN,
        )
        expected.forEach { (wire, type) ->
            val api = JniNativeWalletApi(libraryLoader = {}, classifyBinding = { """{"type":"$wire"}""" })
            assertEquals(type, (api.classifyInput("opaque payload") as NativeResult.Success).value)
        }
    }

    @Test fun rejectsUnversionedType() {
        val bad = JniNativeWalletApi(libraryLoader = {}, classifyBinding = { """{"type":"other"}""" })
        assertTrue(bad.classifyInput("payload") is NativeResult.Failure)
    }

    @Test fun transientCloseIsStrictAndTyped() {
        val api = JniNativeWalletApi(libraryLoader = {}, closeTransientBinding = { _, _ -> """{"closed":true}""" })
        assertTrue((api.closeTransientHandle(7, "quote") as NativeResult.Success).value.closed)
        assertTrue(api.closeTransientHandle(7, "other") is NativeResult.Failure)
    }
}
