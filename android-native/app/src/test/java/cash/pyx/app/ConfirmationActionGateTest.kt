package cash.pyx.app

import cash.pyx.app.ui.ConfirmationActionGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class ConfirmationActionGateTest {
    @Test fun `confirmed mutation is consumed exactly once`() {
        val gate = ConfirmationActionGate()
        var calls = 0

        assertTrue(gate.request { calls++ })
        assertFalse(gate.request { calls += 100 })
        assertTrue(gate.confirm())
        assertFalse(gate.confirm())
        assertEquals(1, calls)
        assertFalse(gate.hasPending())
    }

    @Test fun `cancelled mutation cannot execute`() {
        val gate = ConfirmationActionGate()
        var called = false

        assertTrue(gate.request { called = true })
        gate.cancel()
        assertFalse(gate.confirm())
        assertFalse(called)
    }
}
