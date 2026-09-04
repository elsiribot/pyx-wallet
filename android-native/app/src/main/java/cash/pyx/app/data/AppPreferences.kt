package cash.pyx.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.SharedPreferencesMigration

/** Ordinary preferences. Safety journals intentionally use synchronous commits. */
val Context.appPreferences: DataStore<Preferences> by preferencesDataStore(
    name = "app_preferences_v1",
    produceMigrations = ::legacyPreferenceMigrations,
)

/**
 * Explicit allowlist for preferences written by pre-DataStore native builds.
 *
 * Flutter never stored wallet state in SharedPreferences: currency, contacts, entropy and
 * federation state remain in the existing Rust RocksDB. Do not broaden these sets; restricting
 * migration prevents an unknown or sensitive legacy value from being copied into DataStore.
 */
internal val LEGACY_PREFERENCE_KEYS: Map<String, Set<String>> = mapOf(
    "security_policy" to setOf("biometric_protection_enabled"),
    "seed_backup_state" to setOf("pending"),
    "camera_permission" to setOf("asked"),
)

internal fun legacyPreferenceMigrations(context: Context) =
    LEGACY_PREFERENCE_KEYS.map { (name, keys) ->
        SharedPreferencesMigration(context, name, keysToMigrate = keys)
    }
