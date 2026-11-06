package com.nuvio.tv.data.local

import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.ContentType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How many times each movie was played to the end on this device (Library's hero eyebrow,
 * "Watched 2×", Spec 27e / approved sandbox `u7-library-marquee` Q7). Profile-scoped and local
 * only: [FEATURE] is not in ProfileSettingsSyncService's synced features (a test pins that), and
 * sign-out wipes it with every profile store (ProfileDataStoreFactory.clearProfileScopedData).
 *
 * Written only by the players' completion paths, never by a manual mark watched.
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class WatchCountDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        internal const val FEATURE = "watch_count"
        private const val KEY_PREFIX = "count_"
        private const val TAG = "WatchCountDataStore"
    }

    private fun prefKey(contentId: String) = intPreferencesKey("$KEY_PREFIX$contentId")

    /** Movie id -> completed plays, for the active profile. */
    val counts: Flow<Map<String, Int>> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            buildMap {
                prefs.asMap().forEach { (key, value) ->
                    if (key.name.startsWith(KEY_PREFIX) && value is Int) put(key.name.removePrefix(KEY_PREFIX), value)
                }
            }
        }
    }

    /**
     * One play of [contentId] reached the end. Counts movies only (a series' eyebrow names its
     * latest episode instead). A failed write is logged and dropped: a count never fails playback.
     */
    suspend fun recordCompletedPlay(contentId: String, contentType: String) {
        if (contentId.isBlank() || ContentType.fromString(contentType) != ContentType.MOVIE) return
        try {
            factory.get(profileManager.activeProfileId.value, FEATURE).edit { prefs ->
                val key = prefKey(contentId)
                prefs[key] = (prefs[key] ?: 0) + 1
            }
        } catch (e: IOException) {
            Log.w(TAG, "Watch count not saved", e)
        }
    }
}
