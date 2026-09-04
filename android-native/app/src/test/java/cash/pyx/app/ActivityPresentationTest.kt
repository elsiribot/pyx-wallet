package cash.pyx.app

import cash.pyx.app.nativeapi.*
import cash.pyx.app.ui.ActivityPresentation
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class ActivityPresentationTest {
    @Test fun `row key is the durable operation id across mutable payment fields`() {
        val pending = payment("stable-operation", 1)
        val terminal = pending.copy(timestampMillis = 2, status = PaymentStatus.SUCCEEDED, feeSat = 7)

        assertEquals("stable-operation", ActivityPresentation.itemKey(pending))
        assertEquals(ActivityPresentation.itemKey(pending), ActivityPresentation.itemKey(terminal))
    }

    @Test fun groupsNewestFirstWithStableOperationIdTieBreak() {
        val payments = listOf(payment("b", 1_000), payment("a", 1_000), payment("new", 86_401_000))
        val groups = ActivityPresentation.group(payments, ZoneOffset.UTC)
        assertEquals(2, groups.size)
        assertEquals("new", groups[0].payments.single().operationId)
        assertEquals(listOf("a", "b"), groups[1].payments.map { it.operationId })
    }

    @Test fun presentsDirectionAndHistoricalFiat() {
        val incoming = payment("in", 1, direction = PaymentDirection.INCOMING,
            fiat = FiatAmount("12.34", "EUR"))
        assertEquals("+42 sats", ActivityPresentation.amount(incoming, Locale.US))
        assertEquals("12.34 EUR", ActivityPresentation.historicalFiat(incoming, Locale.US))
        assertEquals("−42 sats", ActivityPresentation.amount(payment("out", 1), Locale.US))
    }

    @Test fun onlyNonSecretTechnicalFieldsAreCopyable() {
        val payment = payment("id", 1).copy(txid = "tx", address = "bc1", preimage = "secret", ecash = "token")
        val fields = ActivityPresentation.technicalFields(payment)
        assertEquals(listOf("Transaction ID", "Address"), fields.filter { it.copyable }.map { it.label })
        assertTrue(fields.filter { it.label in listOf("Preimage", "Ecash") }.all { it.sensitive && !it.copyable })
    }

    @Test fun timestampAndDecimalsAreLocaleAware() {
        assertEquals("Jan 1, 1970, 12:00:01 AM", ActivityPresentation.timestamp(payment("id", 1_000), ZoneOffset.UTC, Locale.US))
        val localized = payment("id", 1, fiat = FiatAmount("1234.50", "EUR"))
        assertEquals("1.234,50 EUR", ActivityPresentation.historicalFiat(localized, Locale.GERMANY))
    }

    private fun payment(
        id: String,
        timestamp: Long,
        direction: PaymentDirection = PaymentDirection.OUTGOING,
        fiat: FiatAmount? = null,
    ) = Payment(id, direction, PaymentType.LIGHTNING, 42, 1, timestamp, PaymentStatus.SUCCEEDED, fiat)
}
