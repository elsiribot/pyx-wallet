package cash.pyx.app

import cash.pyx.app.security.OneShotActionGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OneShotActionGateTest {
    @Test fun `success executes exactly once despite callback replay and double request`() {
        val gate = OneShotActionGate()
        var success: (() -> Unit)? = null
        var failure: (() -> Unit)? = null
        var executions = 0
        assertTrue(gate.request({ executions++ }) { ok, no -> success = ok; failure = no })
        assertFalse(gate.request({ executions++ }) { _, _ -> })
        success!!(); success!!(); failure!!()
        assertEquals(1, executions)
        assertFalse(gate.hasPending())
    }

    @Test fun `cancel and lifecycle clear never execute and allow fresh request`() {
        val gate = OneShotActionGate()
        var failure: (() -> Unit)? = null
        var staleSuccess: (() -> Unit)? = null
        var executions = 0
        gate.request({ executions++ }) { ok, no -> staleSuccess = ok; failure = no }
        failure!!(); staleSuccess!!()
        assertEquals(0, executions)
        gate.request({ executions++ }) { _, _ -> }
        gate.clear()
        assertEquals(0, executions)
        assertFalse(gate.hasPending())
    }
}
