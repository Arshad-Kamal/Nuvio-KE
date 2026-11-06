package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.player.BingeGroups
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the last-used binge group per content ID (series).
 * When a user watches an episode with a stream that has a bingeGroup,
 * we store it so that subsequent episode plays (from Continue Watching,
 * Details, or Next Episode) can automatically prefer the same source group.
 *
 * Values carry their save time as {"g":...,"at":...} so [BoundedPrefs] can
 * evict the oldest entries past the cap. Pre-2026-08-27 values were the bare
 * group string; they still read fine and simply sort oldest for eviction.
 * Stored locally only - not synced to remote.
 */
@Singleton
class BingeGroupCacheDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "binge_group_cache"
        private const val KEY_PREFIX = "bg_"
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private fun prefKey(contentId: String) = stringPreferencesKey("$KEY_PREFIX$contentId")


    /**
     * Stores [bingeGroup] for [contentId]; null, blank or a group naming no release
     * ([BingeGroups.namesRelease], Spec 47 D2) removes the entry instead, so playing such a
     * row also drops the group remembered from an earlier one.
     */
    suspend fun replace(contentId: String, bingeGroup: String?) {
        store().edit { prefs ->
            val value = bingeGroup?.takeIf(BingeGroups::namesRelease)
            if (value == null) {
                prefs.remove(prefKey(contentId))
            } else {
                prefs[prefKey(contentId)] = encodeValue(value)
                enforceEntryCap(prefs)
            }
        }
    }

    /** The stored group; a nameless one saved before Spec 47 D2 reads as none. */
    suspend fun get(contentId: String): String? {
        return store().data.first()[prefKey(contentId)]?.let(::decodeGroup)
            ?.takeIf(BingeGroups::namesRelease)
    }


    private fun encodeValue(group: String): String = JSONObject().apply {
        put("g", group)
        put("at", System.currentTimeMillis())
    }.toString()

    private fun decodeGroup(raw: String): String? =
        if (raw.startsWith("{")) {
            // Our envelope always carries a non-blank "g"; the raw fallback only
            // fires for foreign brace-bearing values (untrusted addon content),
            // where returning them verbatim is correct.
            runCatching { JSONObject(raw).optString("g").ifBlank { null } }.getOrNull() ?: raw
        } else {
            raw
        }

    private fun savedAtMsOf(raw: Any?): Long =
        (raw as? String)?.takeIf { it.startsWith("{") }?.let {
            runCatching { JSONObject(it).optLong("at") }.getOrDefault(0L)
        } ?: 0L

    private fun enforceEntryCap(prefs: MutablePreferences) {
        val timestamps = prefs.asMap()
            .filterKeys { it.name.startsWith(KEY_PREFIX) }
            .entries.associate { (key, value) -> key.name to savedAtMsOf(value) }
        BoundedPrefs.planEviction(timestamps).forEach { keyName ->
            prefs.remove(stringPreferencesKey(keyName))
        }
    }
}
