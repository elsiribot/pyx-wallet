package cash.pyx.app

import cash.pyx.app.ui.CameraPermissionStateMachine
import cash.pyx.app.ui.CameraPermissionUi
import cash.pyx.app.ui.ScanFrameDecision
import cash.pyx.app.ui.ScanFrameGate
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraPermissionStateMachineTest {
    @Test fun `initial state distinguishes first rationale permanent and granted`() {
        assertEquals(CameraPermissionUi.FIRST_REQUEST, CameraPermissionStateMachine.initial(false, false, false))
        assertEquals(CameraPermissionUi.RATIONALE, CameraPermissionStateMachine.initial(false, true, true))
        assertEquals(CameraPermissionUi.PERMANENTLY_DENIED, CameraPermissionStateMachine.initial(false, true, false))
        assertEquals(CameraPermissionUi.GRANTED, CameraPermissionStateMachine.initial(true, true, false))
    }

    @Test fun `request result distinguishes denied permanent and granted`() {
        assertEquals(CameraPermissionUi.DENIED, CameraPermissionStateMachine.afterRequest(false, true))
        assertEquals(CameraPermissionUi.PERMANENTLY_DENIED, CameraPermissionStateMachine.afterRequest(false, false))
        assertEquals(CameraPermissionUi.GRANTED, CameraPermissionStateMachine.afterRequest(true, false))
    }

    @Test fun `frame gate deduplicates supports continuation and pauses while handling`() {
        val gate = ScanFrameGate()
        val frames = mutableListOf<String>()
        assertEquals(true, gate.accept("part-1") { frames += it; ScanFrameDecision.CONTINUE })
        assertEquals(false, gate.accept("part-1") { frames += it; ScanFrameDecision.CONTINUE })
        assertEquals(true, gate.accept("part-2") { frames += it; ScanFrameDecision.HANDLING })
        assertEquals(false, gate.accept("part-3") { frames += it; ScanFrameDecision.CONTINUE })
        assertEquals(listOf("part-1", "part-2"), frames)
        assertEquals(true, gate.isHandling())
    }
}
