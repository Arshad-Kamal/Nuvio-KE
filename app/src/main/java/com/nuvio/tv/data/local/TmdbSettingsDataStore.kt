package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.TmdbSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TmdbSettingsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "tmdb_settings"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    // Retired enrichment keys (tmdb_modern_home_enabled, tmdb_enrich_continue_watching,
    // tmdb_use_artwork/basic_info/details/release_dates/credits/productions/networks/
    // episodes) are no longer read or written; they stay on disk and in the synced blob.
    private val enabledKey = booleanPreferencesKey("tmdb_enabled")
    private val languageKey = stringPreferencesKey("tmdb_language")
    private val useTrailersKey = booleanPreferencesKey("tmdb_use_trailers")
    private val useMoreLikeThisKey = booleanPreferencesKey("tmdb_use_more_like_this")
    private val useCollectionsKey = booleanPreferencesKey("tmdb_use_collections")

    /** The active profile's stored settings; emits only values read from disk (no placeholder). */
    val storedSettings: Flow<TmdbSettings> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            TmdbSettings(
                enabled = prefs[enabledKey] ?: false,
                language = prefs[languageKey] ?: "en",
                useTrailers = prefs[useTrailersKey] ?: false,
                useMoreLikeThis = prefs[useMoreLikeThisKey] ?: true,
                useCollections = prefs[useCollectionsKey] ?: true
            )
        }
    }

    val settings: StateFlow<TmdbSettings> = storedSettings.stateIn(scope, SharingStarted.Eagerly, TmdbSettings())

    suspend fun setEnabled(enabled: Boolean) {
        store().edit { it[enabledKey] = enabled }
    }

    suspend fun setLanguage(language: String) {
        store().edit { it[languageKey] = language.ifBlank { "en" } }
    }

    suspend fun setUseTrailers(enabled: Boolean) {
        store().edit { it[useTrailersKey] = enabled }
    }

    suspend fun setUseMoreLikeThis(enabled: Boolean) {
        store().edit { it[useMoreLikeThisKey] = enabled }
    }

    suspend fun setUseCollections(enabled: Boolean) {
        store().edit { it[useCollectionsKey] = enabled }
    }
}
