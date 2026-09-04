package cash.pyx.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LegacyPreferenceContractTest {
    @Test fun `only established non-secret native preferences are eligible for migration`() {
        assertEquals(
            mapOf(
                "security_policy" to setOf("biometric_protection_enabled"),
                "seed_backup_state" to setOf("pending"),
                "camera_permission" to setOf("asked"),
            ),
            LEGACY_PREFERENCE_KEYS,
        )
        val keys = LEGACY_PREFERENCE_KEYS.values.flatten()
        listOf("seed", "seed_words", "mnemonic", "entropy", "invite", "payment_payload", "currency").forEach {
            assertFalse(it in keys)
        }
    }
}
