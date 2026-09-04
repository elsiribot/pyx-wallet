package cash.pyx.app.security

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import cash.pyx.app.data.appPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

interface SeedBackupState {
    suspend fun isPending(): Boolean
    /** Completes only after DataStore has atomically persisted the new value. */
    suspend fun setPending(value: Boolean)
}

class DataStoreSeedBackupState(context: Context) : SeedBackupState {
    private val store = context.applicationContext.appPreferences
    override suspend fun isPending(): Boolean = store.data.map { it[PENDING] ?: false }.first()
    override suspend fun setPending(value: Boolean) { store.edit { it[PENDING] = value } }

    private companion object { val PENDING = booleanPreferencesKey("pending") }
}
