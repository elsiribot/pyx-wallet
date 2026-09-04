package cash.pyx.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cash.pyx.app.security.BiometricPolicy
import cash.pyx.app.data.appPreferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BiometricPolicyPersistenceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun clearBefore() = clear()
    @After fun clearAfter() = clear()

    @Test fun enabledPolicySurvivesPolicyRecreationAndCanBeDisabled() = runBlocking {
        BiometricPolicy.from(context).setEnabled(true)
        assertTrue(BiometricPolicy.from(context).currentEnabled())

        BiometricPolicy.from(context).setEnabled(false)
        assertFalse(BiometricPolicy.from(context).currentEnabled())
    }

    private fun clear() {
        runBlocking { context.appPreferences.edit { it.clear() } }
    }
}
