package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingSource
import com.nuvio.tv.data.simkl.RelatedTitlesSource
import com.nuvio.tv.data.simkl.SimklAnimeSeasons
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Profile-scoped Continue Watching settings: dismissed Next-Up keys, the tracker source choices
 * (Spec 104 S1e: Continue Watching source, Library source), Simkl's anime seasons mode (Spec 106 S5, E3) and the
 * More like this source (Spec 106 S4a, E6).
 *
 * Stored under the legacy "trakt_settings" feature name, which the profile-settings sync blob
 * ("trakt_settings" in ProfileSettingsSyncService) carries, so the source choices sync with the
 * Nuvio account (Spec 104 D10; tokens live in TrackingAccountStore and never sync). Keys of
 * removed tracking features (watch_progress_source [TRAKT|SIMKL|NUVIO_SYNC|MDBLIST],
 * library_source_mode, continue_watching_days_cap, show_meta_comments, more_like_this_source,
 * simkl_anime_id_preference) and the unread duplicates show_unaired_next_up /
 * next_up_from_furthest_episode (LayoutPreferenceDataStore owns the live settings) are left on
 * disk untouched and never read: the source choices use new keys so a pre-cut TRAKT/SIMKL value
 * cannot come back as a choice (and Simkl's anime mode uses simkl_anime_seasons, not the pre-cut
 * simkl_anime_id_preference; More like this uses tracking_more_like_this_source, not more_like_this_source).
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class ContinueWatchingSettingsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        // Wire/on-disk names (all of them): pinned by ProfileSettingsCredentialPolicyTest.
        internal const val FEATURE = "trakt_settings"
        internal const val DISMISSED_NEXT_UP_KEYS_KEY = "dismissed_next_up_keys"
        internal const val PROGRESS_SOURCE_KEY = "tracking_progress_source"
        internal const val LIBRARY_SOURCE_KEY = "tracking_library_source"
        internal const val SIMKL_ANIME_SEASONS_KEY = "simkl_anime_seasons"
        internal const val RELATED_SOURCE_KEY = "tracking_more_like_this_source"
        // Dismissed Next-Up keys only ever grow; keep the persisted set bounded by
        // dropping the oldest entries past the cap. A dropped dismissal can let a
        // Next-Up card resurface - self-healing on the next dismiss.
        const val MAX_DISMISSED_NEXT_UP_KEYS = 500
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private val dismissedNextUpKeysKey = stringSetPreferencesKey(DISMISSED_NEXT_UP_KEYS_KEY)
    private val progressSourceKey = stringPreferencesKey(PROGRESS_SOURCE_KEY)
    private val librarySourceKey = stringPreferencesKey(LIBRARY_SOURCE_KEY)
    private val simklAnimeSeasonsKey = stringPreferencesKey(SIMKL_ANIME_SEASONS_KEY)
    private val relatedSourceKey = stringPreferencesKey(RELATED_SOURCE_KEY)

    /** The chosen Continue Watching source (not the effective one: see effectiveTrackingSource). */
    val progressSource: Flow<TrackingSource> = observeSource(progressSourceKey)

    /** The chosen Library source (not the effective one: see effectiveTrackingSource). */
    val librarySource: Flow<TrackingSource> = observeSource(librarySourceKey)

    suspend fun getProgressSource(profileId: Int): TrackingSource = readSource(profileId, progressSourceKey)

    suspend fun getLibrarySource(profileId: Int): TrackingSource = readSource(profileId, librarySourceKey)

    suspend fun setProgressSource(source: TrackingSource, profileId: Int) {
        writeSource(profileId, progressSourceKey, source)
    }

    suspend fun setLibrarySource(source: TrackingSource, profileId: Int) {
        writeSource(profileId, librarySourceKey, source)
    }

    /** [profileId]'s Simkl anime mode (read by Simkl's providers for that profile). */
    fun simklAnimeSeasons(profileId: Int): Flow<SimklAnimeSeasons> =
        factory.get(profileId, FEATURE).data.map { prefs -> SimklAnimeSeasons.fromStorage(prefs[simklAnimeSeasonsKey]) }
            .distinctUntilChanged()

    /** The active profile's Simkl anime mode (Settings › Trackers). */
    val activeSimklAnimeSeasons: Flow<SimklAnimeSeasons> = profileManager.activeProfileId.flatMapLatest(::simklAnimeSeasons)

    suspend fun setSimklAnimeSeasons(value: SimklAnimeSeasons, profileId: Int) {
        factory.get(profileId, FEATURE).edit { prefs -> prefs[simklAnimeSeasonsKey] = value.storageId }
    }

    /** The active profile's chosen More like this source (not the effective one: see RelatedTitlesSource.effective). */
    val relatedTitlesSource: Flow<RelatedTitlesSource> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs -> RelatedTitlesSource.fromStorage(prefs[relatedSourceKey]) }
    }.distinctUntilChanged()

    suspend fun setRelatedTitlesSource(value: RelatedTitlesSource, profileId: Int) {
        factory.get(profileId, FEATURE).edit { prefs -> prefs[relatedSourceKey] = value.storageId }
    }

    private fun observeSource(key: Preferences.Key<String>): Flow<TrackingSource> =
        profileManager.activeProfileId.flatMapLatest { pid ->
            factory.get(pid, FEATURE).data.map { prefs -> TrackingSource.fromStorage(prefs[key]) }
        }.distinctUntilChanged()

    private suspend fun readSource(profileId: Int, key: Preferences.Key<String>): TrackingSource =
        TrackingSource.fromStorage(factory.get(profileId, FEATURE).data.first()[key])

    private suspend fun writeSource(profileId: Int, key: Preferences.Key<String>, source: TrackingSource) {
        factory.get(profileId, FEATURE).edit { prefs -> prefs[key] = source.storageId }
    }

    val dismissedNextUpKeys: Flow<Set<String>> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            val raw = prefs[dismissedNextUpKeysKey] ?: emptySet()
            // Normalize legacy "contentId|season|episode" keys to bare contentIds.
            // G4-2 per-seed keys ("contentId:sN:eM") pass through whole — every
            // consumer compares full nextUpDismissKey(...) output against this set.
            raw.mapTo(mutableSetOf()) { it.substringBefore("|") }
        }
    }

    suspend fun addDismissedNextUpKey(key: String) {
        if (key.isBlank()) return
        // G4-2: keys are per-seed ("contentId:sN:eM", see nextUpDismissKey), so a
        // dismissal only suppresses the card built from that exact seed episode;
        // watching on produces a new seed and a new key. Legacy "|" suffixes are
        // still stripped here; ":"-shaped keys pass through whole.
        val normalized = key.trim().substringBefore("|")
        store().edit { prefs ->
            val current = prefs[dismissedNextUpKeysKey] ?: emptySet()
            var updated = current + normalized
            if (updated.size > MAX_DISMISSED_NEXT_UP_KEYS) {
                // Truncation relies on DataStore 1.1.1 round-tripping string sets in
                // insertion order: the tail holds the newest first-time dismissals
                // (a re-dismissed key keeps its original slot). If that ordering were
                // ever lost, the failure is one resurfaced Next-Up card - the
                // self-healing case below covers it.
                updated = updated.toList().takeLast(MAX_DISMISSED_NEXT_UP_KEYS).toSet()
            }
            prefs[dismissedNextUpKeysKey] = updated
        }
    }

    /**
     * Restores dismissed Next-Up cards (Library's Hidden group, Spec 27d): removes exactly [keys],
     * as [dismissedNextUpKeys] spells them (a legacy "contentId|s|e" entry matches its bare id).
     */
    suspend fun removeDismissedNextUpKeys(keys: Collection<String>) {
        if (keys.isEmpty()) return
        removeDismissedWhere(dismissedKeyMatcher(keys))
    }

    /** Drops the stored keys [matches] selects; writes only when one went. */
    private suspend fun removeDismissedWhere(matches: (String) -> Boolean) {
        store().edit { prefs ->
            val current = prefs[dismissedNextUpKeysKey] ?: emptySet()
            val filtered = current.filterNot(matches)
            if (filtered.size != current.size) {
                prefs[dismissedNextUpKeysKey] = filtered.toSet()
            }
        }
    }

    suspend fun removeDismissedNextUpKeysForContent(contentId: String) {
        if (contentId.isBlank()) return
        val trimmed = contentId.trim()
        val legacyPrefix = "$trimmed|"
        val seedPrefix = "$trimmed:"
        // Remove all three shapes: bare contentId (legacy + null-seed),
        // "contentId|s|e" (oldest legacy), and "contentId:sN:eM" (G4-2 per-seed).
        // Watching any episode of a series clears its dismissals so the card
        // can resurface (documented invariant — kept under per-seed semantics).
        removeDismissedWhere { it == trimmed || it.startsWith(legacyPrefix) || it.startsWith(seedPrefix) }
    }
}

/**
 * Which stored dismissal keys [keys] (as the dismissedNextUpKeys flow spells them) name: a stored
 * key matches when equal after the flow's legacy normalization ("contentId|s|e" -> its bare id).
 */
internal fun dismissedKeyMatcher(keys: Collection<String>): (String) -> Boolean {
    val normalized = keys.mapNotNullTo(mutableSetOf()) { key -> key.trim().substringBefore("|").takeIf { it.isNotEmpty() } }
    return { stored -> stored.substringBefore("|") in normalized }
}
