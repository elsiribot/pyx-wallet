package cash.pyx.app

import cash.pyx.app.security.IrreversibleOperationJournal
import cash.pyx.app.security.IrreversibleOperationReconciliation
import cash.pyx.app.security.PendingIrreversibleOperation
import cash.pyx.app.security.secureOperationCorrelationId
import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IrreversibleOperationReconciliationTest {
    private val correlation = "00112233445566778899aabbccddeeff"

    @Test fun writesCorrelationKindFederationAndTimestampBeforeAllowingExecution() {
        val journal = MemoryJournal()
        val guard = IrreversibleOperationReconciliation(journal) { 1_234L }
        assertTrue(guard.begin(PendingIrreversibleOperation.Kind.LIGHTNING_SEND, correlation, "fed-a"))
        assertEquals(PendingIrreversibleOperation(PendingIrreversibleOperation.Kind.LIGHTNING_SEND, 1_234L, correlation, "fed-a"), journal.value)
    }

    @Test fun processRecreationCannotBeClearedByManualReviewOrNewSubmission() {
        val saved = PendingIrreversibleOperation(PendingIrreversibleOperation.Kind.ONCHAIN_SEND, 50L, correlation, "fed-a")
        val recreated = IrreversibleOperationReconciliation(MemoryJournal(saved)) { 100L }
        assertFalse(recreated.begin(PendingIrreversibleOperation.Kind.ECASH_CLAIM, "ffeeddccbbaa99887766554433221100", "fed-a"))
        assertEquals(saved, recreated.pending.value)
    }

    @Test fun conclusiveClearRequiresMatchingCorrelationAndDurableWrite() {
        val journal = MemoryJournal()
        val guard = IrreversibleOperationReconciliation(journal) { 7L }
        assertTrue(guard.begin(PendingIrreversibleOperation.Kind.ECASH_CREATE, correlation, "fed-a"))
        assertFalse(guard.resolved("ffeeddccbbaa99887766554433221100"))
        journal.allowClear = false
        assertFalse(guard.resolved(correlation))
        assertEquals(correlation, guard.pending.value?.correlationId)
        journal.allowClear = true
        assertTrue(guard.resolved(correlation))
        assertNull(guard.pending.value)
    }

    @Test fun failedDurableBeginNeverAllowsNativeExecution() {
        val journal = MemoryJournal().apply { allowBegin = false }
        val guard = IrreversibleOperationReconciliation(journal)
        assertFalse(guard.begin(PendingIrreversibleOperation.Kind.LIGHTNING_SEND, correlation, "fed-a"))
        assertNull(guard.pending.value)
    }

    @Test fun nativeOnlyRecordIsAdoptedButMismatchedCorrelationCannotReplaceIt() {
        val guard = IrreversibleOperationReconciliation(MemoryJournal()) { 8L }
        val native = PendingIrreversibleOperation(PendingIrreversibleOperation.Kind.ECASH_CLAIM, 8L, correlation, "fed-a")
        assertTrue(guard.adopt(native))
        assertFalse(guard.adopt(native.copy(correlationId = "ffeeddccbbaa99887766554433221100")))
        assertEquals(native, guard.pending.value)
    }

    @Test fun statusUpdateIsCorrelationBoundAndDurable() {
        val journal = MemoryJournal()
        val guard = IrreversibleOperationReconciliation(journal) { 9L }
        assertTrue(guard.begin(PendingIrreversibleOperation.Kind.ONCHAIN_SEND, correlation, "fed-a"))
        assertFalse(guard.updateStatus("ffeeddccbbaa99887766554433221100", PendingIrreversibleOperation.ReconciliationStatus.PENDING))
        assertTrue(guard.updateStatus(correlation, PendingIrreversibleOperation.ReconciliationStatus.AMBIGUOUS))
        assertEquals(PendingIrreversibleOperation.ReconciliationStatus.AMBIGUOUS, journal.value?.reconciliationStatus)
    }

    @Test fun correlationGeneratorProducesLowercase128BitHex() {
        val random = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(byteArrayOf(1, 2, 3, 4)) }
        val first = secureOperationCorrelationId(random)
        val second = secureOperationCorrelationId(random)
        assertTrue(first.matches(Regex("[0-9a-f]{32}")))
        assertTrue(second.matches(Regex("[0-9a-f]{32}")))
        assertTrue(first != second)
    }

    private class MemoryJournal(var value: PendingIrreversibleOperation? = null) : IrreversibleOperationJournal {
        var allowBegin = true
        var allowClear = true
        override fun read() = value
        override fun begin(operation: PendingIrreversibleOperation): Boolean {
            if (!allowBegin) return false
            value = operation
            return true
        }
        override fun clear(): Boolean {
            if (!allowClear) return false
            value = null
            return true
        }
    }
}
