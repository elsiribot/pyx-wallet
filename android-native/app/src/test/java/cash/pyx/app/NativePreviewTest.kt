package cash.pyx.app

import org.junit.Assert.assertEquals
import org.junit.Test

class NativePreviewTest {
    @Test
    fun previewUsesSeparateDebugApplicationId() {
        assertEquals("cash.pyx.app.nativepreview", BuildConfig.APPLICATION_ID)
    }
}
