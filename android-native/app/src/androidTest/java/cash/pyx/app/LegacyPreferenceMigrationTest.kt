package cash.pyx.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.test.core.app.ApplicationProvider
import cash.pyx.app.data.LEGACY_PREFERENCE_KEYS
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyPreferenceMigrationTest {
    @Test fun migration_is_idempotent_and_excludes_unknown_sensitive_values() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = System.nanoTime().toString()
        val sourceNames = LEGACY_PREFERENCE_KEYS.keys.associateWith { "$it-$suffix" }
        val output = File(context.cacheDir, "legacy-preference-test-$suffix.preferences_pb")

        try {
            context.getSharedPreferences(sourceNames.getValue("security_policy"), Context.MODE_PRIVATE).edit()
                .putBoolean("biometric_protection_enabled", true)
                .putString("mnemonic", "must not migrate")
                .commit()
            context.getSharedPreferences(sourceNames.getValue("seed_backup_state"), Context.MODE_PRIVATE).edit()
                .putBoolean("pending", true)
                .putString("seed_words", "must not migrate")
                .commit()
            context.getSharedPreferences(sourceNames.getValue("camera_permission"), Context.MODE_PRIVATE).edit()
                .putBoolean("asked", true)
                .putString("payment_payload", "must not migrate")
                .commit()

            val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(
                migrations = LEGACY_PREFERENCE_KEYS.map { (name, keys) ->
                    SharedPreferencesMigration(context, sourceNames.getValue(name), keysToMigrate = keys)
                },
                produceFile = { output },
            )
            val first = store.data.first()
            assertEquals(true, first[booleanPreferencesKey("biometric_protection_enabled")])
            assertEquals(true, first[booleanPreferencesKey("pending")])
            assertEquals(true, first[booleanPreferencesKey("asked")])
            assertFalse(first.asMap().keys.any { it.name in setOf("mnemonic", "seed_words", "payment_payload") })

            // A migration marks itself complete. Later writes to a legacy file cannot override the
            // value already imported into the same DataStore.
            context.getSharedPreferences(sourceNames.getValue("security_policy"), Context.MODE_PRIVATE).edit()
                .putBoolean("biometric_protection_enabled", false)
                .commit()
            assertEquals(true, store.data.first()[booleanPreferencesKey("biometric_protection_enabled")])

            assertTrue(context.getSharedPreferences(sourceNames.getValue("security_policy"), Context.MODE_PRIVATE)
                .contains("mnemonic"))
            assertTrue(context.getSharedPreferences(sourceNames.getValue("seed_backup_state"), Context.MODE_PRIVATE)
                .contains("seed_words"))
        } finally {
            sourceNames.values.forEach { context.deleteSharedPreferences(it) }
            output.delete()
        }
    }
}
