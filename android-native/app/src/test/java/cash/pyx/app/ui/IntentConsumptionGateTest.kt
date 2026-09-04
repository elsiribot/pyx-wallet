package cash.pyx.app.ui

import cash.pyx.app.security.ConsumedDeepLink
import cash.pyx.app.security.DeepLinkConsumptionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IntentConsumptionGateTest {
    @Test fun coldIntentIsConsumedAndWarmDuplicateIsIgnored() {
        val gate = IntentConsumptionGate()

        assertEquals("bitcoin:first", gate.consume("bitcoin:first"))
        assertNull(gate.consume("bitcoin:first"))
        assertEquals("lnurl:second", gate.consume("lnurl:second"))
        assertNull(gate.consume("lnurl:second"))
    }

    @Test fun processRecreationDoesNotConsumeTheLaunchIntentTwice() {
        val store = MemoryStore()
        val firstProcess = IntentConsumptionGate(store) { 1_000L }
        assertEquals("bitcoin:review", firstProcess.consume("bitcoin:review"))

        val recreatedProcess = IntentConsumptionGate(store) { 2_000L }
        assertNull(recreatedProcess.consume("bitcoin:review"))
    }

    @Test fun sameLinkCanBeDeliberatelyOpenedAfterDuplicateWindow() {
        val store = MemoryStore()
        assertEquals("bitcoin:review", IntentConsumptionGate(store) { 1_000L }.consume("bitcoin:review"))
        assertEquals("bitcoin:review", IntentConsumptionGate(store) { 602_000L }.consume("bitcoin:review"))
    }

    @Test fun emptyIntentIsNeverConsumed() {
        val gate = IntentConsumptionGate()
        assertNull(gate.consume(null))
        assertNull(gate.consume(""))
        assertNull(gate.consume("   "))
    }

    @Test fun oversizedExportedIntentIsRejectedBeforeFingerprintPersistence() {
        val store = MemoryStore()
        val gate = IntentConsumptionGate(store)

        assertNull(gate.consume("bitcoin:" + "x".repeat(16 * 1024)))
        assertNull(store.read())
    }

    @Test fun maximumSizedIntentIsAcceptedButOneCharacterMoreIsRejected() {
        val accepted = "b".repeat(16 * 1024)
        val rejected = "b".repeat(16 * 1024 + 1)
        val gate = IntentConsumptionGate()

        assertEquals(accepted, gate.consume(accepted))
        assertNull(gate.consume(rejected))
    }

    @Test fun failedCommitDoesNotBurnAnIntentAndAllowsRetry() {
        val store = MemoryStore().apply { rejectNextWrite = true }
        val gate = IntentConsumptionGate(store)

        assertNull(gate.consume("bitcoin:retry-after-storage-failure"))
        assertEquals(
            "bitcoin:retry-after-storage-failure",
            gate.consume("bitcoin:retry-after-storage-failure"),
        )
    }

    @Test fun futureOrExpiredFingerprintDoesNotSuppressAValidDelivery() {
        val future = MemoryStore().apply {
            write(ConsumedDeepLink(fingerprint("bitcoin:clock-change"), 2_000L))
        }
        assertEquals(
            "bitcoin:clock-change",
            IntentConsumptionGate(future) { 1_000L }.consume("bitcoin:clock-change"),
        )

        val expired = MemoryStore().apply {
            write(ConsumedDeepLink(fingerprint("bitcoin:expired"), 1_000L))
        }
        assertEquals(
            "bitcoin:expired",
            IntentConsumptionGate(expired) { 601_001L }.consume("bitcoin:expired"),
        )
    }

    private fun fingerprint(value: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private class MemoryStore : DeepLinkConsumptionStore {
        private var value: ConsumedDeepLink? = null
        var rejectNextWrite = false
        override fun read() = value
        override fun write(value: ConsumedDeepLink): Boolean {
            if (rejectNextWrite) { rejectNextWrite = false; return false }
            this.value = value
            return true
        }
    }
}
