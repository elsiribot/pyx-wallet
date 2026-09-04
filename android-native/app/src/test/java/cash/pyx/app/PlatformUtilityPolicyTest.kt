package cash.pyx.app

import cash.pyx.app.ui.PlatformUtilityPolicy
import org.junit.Assert.*
import org.junit.Test

class PlatformUtilityPolicyTest {
    @Test fun nonSecretPayloadsUseSharesheetButSecretPayloadsNeverDo() {
        assertTrue(PlatformUtilityPolicy.canShare(sensitive = false))
        assertFalse(PlatformUtilityPolicy.canShare(sensitive = true))
    }

    @Test fun absentPlatformFeaturesStayAbsentWithoutProductPolicy() {
        assertFalse(PlatformUtilityPolicy.POSTS_SYSTEM_PAYMENT_NOTIFICATIONS)
        assertFalse(PlatformUtilityPolicy.HAS_QUICK_SPEND_POLICY)
        assertNull(PlatformUtilityPolicy.privacyPolicyUrl)
    }
}
