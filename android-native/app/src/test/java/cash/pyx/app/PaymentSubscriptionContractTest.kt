package cash.pyx.app

import cash.pyx.app.data.SubscriptionDtos
import cash.pyx.app.nativeapi.PaymentStatus
import cash.pyx.app.ui.PaymentNotificationPresentation
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentSubscriptionContractTest {
    private fun update(status: String, notification: String = "null") = """{"payments":[{"operationId":"stable","type":"lightning","direction":"incoming","amountSat":1000,"feeSat":null,"timestampMillis":1800000000000,"status":"$status","fiatAmount":"0.95","fiatCurrencyCode":"USD"}],"notification":$notification}"""

    @Test fun `pending folds to terminal stable identity and emits safe notice once`() {
        val pending = SubscriptionDtos.payments(update("pending"))
        val terminal = SubscriptionDtos.payments(update("succeeded", """{"direction":"incoming","success":true,"amountSat":1000,"type":"lightning"}"""))
        assertEquals("stable", pending.payments.single().operationId)
        assertEquals(PaymentStatus.SUCCEEDED, terminal.payments.single().status)
        val notice = PaymentNotificationPresentation.notice(terminal, null)!!
        assertEquals("stable:SUCCEEDED", notice.identity)
        assertTrue(notice.message.contains("1000 sats"))
        assertNull(PaymentNotificationPresentation.notice(terminal, notice.identity))
    }

    @Test fun `strict summary rejects secret or unknown fields`() {
        val leaked = update("pending").replace("\"status\":\"pending\"", "\"status\":\"pending\",\"preimage\":\"secret\"")
        assertTrue(runCatching { SubscriptionDtos.payments(leaked) }.exceptionOrNull() is JSONException)
    }

    @Test fun `summary list is capped and fiat fields must be paired`() {
        val entry = """{"operationId":"id","type":"ecash","direction":"outgoing","amountSat":1,"feeSat":null,"timestampMillis":1,"status":"pending","fiatAmount":null,"fiatCurrencyCode":null}"""
        val oversized = "{\"payments\":[${List(101) { entry.replace("id", "id$it") }.joinToString()}],\"notification\":null}"
        assertTrue(runCatching { SubscriptionDtos.payments(oversized) }.exceptionOrNull() is JSONException)
        val unpaired = update("pending").replace("\"fiatCurrencyCode\":\"USD\"", "\"fiatCurrencyCode\":null")
        assertTrue(runCatching { SubscriptionDtos.payments(unpaired) }.exceptionOrNull() is JSONException)
    }
}
