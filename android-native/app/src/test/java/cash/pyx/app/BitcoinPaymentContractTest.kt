package cash.pyx.app

import cash.pyx.app.nativeapi.JniNativeWalletApi
import cash.pyx.app.nativeapi.NativeResult
import cash.pyx.app.ui.BitcoinPaymentPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BitcoinPaymentContractTest {
    @Test fun `URI preview preserves canonical address metadata and locked integer sats`() {
        val api = JniNativeWalletApi(libraryLoader = {}, parseBitcoinBinding = {
            """{"address":"bc1qcanonical","amountSat":123456789,"label":"Alice Shop","message":"Order 42","isUri":true}"""
        })
        val payment = (api.parseBitcoinPayment("bitcoin:opaque") as NativeResult.Success).value
        val state = BitcoinPaymentPresentation.state(payment)
        assertEquals("bc1qcanonical", state.address)
        assertEquals("123456789", state.amount)
        assertTrue(state.amountLocked)
        assertEquals("Alice Shop", state.label)
    }

    @Test fun `plain address retains editable empty amount`() {
        val api = JniNativeWalletApi(libraryLoader = {}, parseBitcoinBinding = {
            """{"address":"bc1qplain","amountSat":null,"label":null,"message":null,"isUri":false}"""
        })
        val state = BitcoinPaymentPresentation.state((api.parseBitcoinPayment("bc1qplain") as NativeResult.Success).value)
        assertEquals("", state.amount)
        assertFalse(state.amountLocked)
    }

    @Test fun `invalid or conflicting preview shape returns sanitized failure`() {
        val api = JniNativeWalletApi(libraryLoader = {}, parseBitcoinBinding = {
            """{"address":"bc1qplain","amountSat":1,"label":null,"message":null,"isUri":false}"""
        })
        val failure = api.parseBitcoinPayment("secret input") as NativeResult.Failure
        assertEquals("Wallet services returned an invalid response.", failure.error.userMessage)
        assertFalse(failure.error.userMessage.contains("secret"))
    }
}
