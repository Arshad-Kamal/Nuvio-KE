package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class StartupSyncState(
    val lastFullPullUserId: String?,
    val lastFullPullAtMs: Long,
    val lastFullPullIncludedProfileSettings: Boolean
)

@Singleton
class StartupSyncPreferences @Inject constructor(
    private val factory: ProfileDataStoreFactory
) {
    companion object {
        private const val FEATURE = "startup_sync_state"
    }

    private val lastFullPullUserIdKey = stringPreferencesKey("last_full_pull_user_id")
    private val lastFullPullAtMsKey = longPreferencesKey("last_full_pull_at_ms")
    private val lastFullPullIncludedProfileSettingsKey = booleanPreferencesKey("last_full_pull_included_profile_settings")
    private val lastSyncOkAtMsKey = longPreferencesKey("last_sync_ok_at_ms")

    private fun store(profileId: Int) = factory.get(profileId, FEATURE)

    suspend fun getState(profileId: Int): StartupSyncState {
        val prefs = store(profileId).data.first()
        return StartupSyncState(
            lastFullPullUserId = prefs[lastFullPullUserIdKey],
            lastFullPullAtMs = prefs[lastFullPullAtMsKey] ?: 0L,
            lastFullPullIncludedProfileSettings = prefs[lastFullPullIncludedProfileSettingsKey] ?: false
        )
    }

    suspend fun markFullPull(
        profileId: Int,
        userId: String,
        includeProfileSettings: Boolean
    ) {
        store(profileId).edit { prefs ->
            prefs[lastFullPullUserIdKey] = userId
            prefs[lastFullPullAtMsKey] = System.currentTimeMillis()
            prefs[lastFullPullIncludedProfileSettingsKey] = includeProfileSettings
        }
    }

    /** Wall-clock time of the last pull whose core steps all succeeded (sync line); null = never. */
    fun lastSyncOkAtMs(profileId: Int): Flow<Long?> =
        store(profileId).data.map { it[lastSyncOkAtMsKey] }.distinctUntilChanged()

    suspend fun markSyncOk(profileId: Int) {
        store(profileId).edit { prefs ->
            prefs[lastSyncOkAtMsKey] = System.currentTimeMillis()
        }
    }
}
