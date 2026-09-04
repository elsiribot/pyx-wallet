package cash.pyx.app.security

import org.junit.Assert.assertEquals
import org.junit.Test

class BiometricPolicyTest {
    @Test fun `configured policy never silently downgrades when biometric becomes unavailable`() {
        assertEquals(BiometricPolicy.Dispatch.BLOCK_UNAVAILABLE,
            BiometricPolicy.dispatchForTest(configuredEnabled = true, available = false))
    }

    @Test fun `production dispatch follows configured policy and capability`() {
        assertEquals(BiometricPolicy.Dispatch.PROCEED,
            BiometricPolicy.dispatchForTest(configuredEnabled = false, available = true))
        assertEquals(BiometricPolicy.Dispatch.PROCEED,
            BiometricPolicy.dispatchForTest(configuredEnabled = false, available = false))
        assertEquals(BiometricPolicy.Dispatch.AUTHENTICATE,
            BiometricPolicy.dispatchForTest(configuredEnabled = true, available = true))
        assertEquals(BiometricPolicy.Dispatch.BLOCK_UNAVAILABLE,
            BiometricPolicy.dispatchForTest(configuredEnabled = true, available = false))
    }
}
