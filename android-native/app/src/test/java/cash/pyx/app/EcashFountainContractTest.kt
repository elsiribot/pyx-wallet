package cash.pyx.app

import cash.pyx.app.nativeapi.JniNativeWalletApi
import cash.pyx.app.nativeapi.NativeResult
import cash.pyx.app.ui.EcashFountainPresentation
import cash.pyx.app.ui.EcashFragmentAccumulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EcashFountainContractTest {
    @Test fun `static threshold cadence and reduced motion policy are deterministic`() {
        assertTrue(EcashFountainPresentation.useStatic(1_024, false))
        assertFalse(EcashFountainPresentation.useStatic(1_025, false))
        assertTrue(EcashFountainPresentation.useStatic(8_000, true))
        assertEquals(350L, EcashFountainPresentation.FRAME_MILLIS)
    }

    @Test fun `fragment accumulator accepts out of order and ignores duplicates`() {
        val accumulator = EcashFragmentAccumulator()
        assertTrue(accumulator.add("fedimint1part2")); assertTrue(accumulator.add("fedimint1part1"))
        assertFalse(accumulator.add("fedimint1part2")); assertEquals(2, accumulator.progress)
        accumulator.clear(); assertEquals(0, accumulator.progress)
    }

    @Test fun `codec facade parses fragments completion and close`() {
        val api = JniNativeWalletApi(
            libraryLoader = {}, createEcashEncoderBinding = { 5 }, nextEcashFragmentBinding = { "{\"fragment\":\"fedimint1part\"}" },
            createEcashDecoderBinding = { 6 }, addEcashFragmentBinding = { _, _ -> "{\"complete\":true,\"payload\":\"fedimint1token\"}" },
            closeEcashCodecBinding = { _, _ -> "{\"closed\":true}" },
        )
        assertEquals(5L, (api.createEcashEncoder("fedimint1token") as NativeResult.Success).value.handle)
        assertEquals("fedimint1part", (api.nextEcashFragment(5) as NativeResult.Success).value.fragment)
        assertEquals("fedimint1token", (api.addEcashFragment(6, "fedimint1part") as NativeResult.Success).value.payload)
        assertTrue((api.closeEcashCodec(5, "encoder") as NativeResult.Success).value.closed)
    }
}
