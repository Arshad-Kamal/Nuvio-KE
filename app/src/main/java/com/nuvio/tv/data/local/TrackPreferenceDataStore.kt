package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrackPreferenceDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "track_preference"
        private const val SUB_TYPE = "sub_type"
        private const val SUB_LANG = "sub_lang"
        private const val SUB_NAME = "sub_name"
        private const val SUB_TRACK_ID = "sub_track_id"
        private const val SUB_IS_FORCED = "sub_is_forced"
        private const val SUB_ADDON_ID = "sub_addon_id"
        private const val SUB_ADDON_URL = "sub_addon_url"
        private const val SUB_ADDON_NAME = "sub_addon_name"
        private const val AUDIO_LANG = "audio_lang"
        private const val AUDIO_NAME = "audio_name"
        private const val AUDIO_TRACK_ID = "audio_track_id"
        // Keyed per-videoId (not per-contentId) so that a delay calibrated for
        // one episode is not blindly reapplied to the next episode where it is
        // almost certainly wrong.
        private const val SUB_DELAY_MS = "sub_delay_ms"
        // Sidecar save-time for [BoundedPrefs] eviction. One per entry id; rides
        // the profile-settings sync blob so eviction stays coherent across devices.
        private const val TS = "ts"
    }

    private fun store() = factory.get(profileManager.activeProfileId.value, FEATURE)

    private fun key(field: String, contentId: String) =
        stringPreferencesKey("$field|$contentId")

    private fun intKey(field: String, id: String) =
        intPreferencesKey("$field|$id")

    private fun tsKey(id: String) = stringPreferencesKey("$TS|$id")

    /** True when any key other than the ts sidecar belongs to entry [id]. */
    private fun MutablePreferences.hasOtherEntryKeys(tsKey: Preferences.Key<String>, id: String): Boolean =
        asMap().keys.any {
            it.name != tsKey.name && '|' in it.name && it.name.substringAfter('|') == id
        }

    suspend fun save(contentId: String, pref: PersistedTrackPreference) {
        store().edit { prefs ->
            fun set(field: String, value: String?) {
                val k = key(field, contentId)
                if (value != null) prefs[k] = value else prefs.remove(k)
            }
            set(SUB_TYPE, pref.subtitleType)
            set(SUB_LANG, pref.subtitleLanguage)
            set(SUB_NAME, pref.subtitleName)
            set(SUB_TRACK_ID, pref.subtitleTrackId)
            set(SUB_IS_FORCED, pref.subtitleIsForced?.toString())
            set(SUB_ADDON_ID, pref.addonSubtitleId)
            set(SUB_ADDON_URL, pref.addonSubtitleUrl)
            set(SUB_ADDON_NAME, pref.addonSubtitleAddonName)
            set(AUDIO_LANG, pref.audioLanguage)
            set(AUDIO_NAME, pref.audioName)
            set(AUDIO_TRACK_ID, pref.audioTrackId)
            // All-null preferences (e.g. a delay nudge with no remembered tracks)
            // must leave no trace, mirroring saveSubtitleDelayMs: keep the sidecar
            // only when the entry still carries a field key.
            val ts = tsKey(contentId)
            if (prefs.hasOtherEntryKeys(ts, contentId)) {
                prefs[ts] = System.currentTimeMillis().toString()
            } else {
                prefs.remove(ts)
            }
            enforceEntryCap(prefs)
        }
    }

    suspend fun load(contentId: String): PersistedTrackPreference? {
        val prefs = store().data.first()
        val subType = prefs[key(SUB_TYPE, contentId)]
        val audioLang = prefs[key(AUDIO_LANG, contentId)]
        val audioName = prefs[key(AUDIO_NAME, contentId)]
        val audioTrackId = prefs[key(AUDIO_TRACK_ID, contentId)]
        if (
            subType == null &&
            audioLang == null &&
            audioName == null &&
            audioTrackId == null
        ) return null
        return PersistedTrackPreference(
            subtitleType = subType,
            subtitleLanguage = prefs[key(SUB_LANG, contentId)],
            subtitleName = prefs[key(SUB_NAME, contentId)],
            subtitleTrackId = prefs[key(SUB_TRACK_ID, contentId)],
            subtitleIsForced = prefs[key(SUB_IS_FORCED, contentId)]?.toBooleanStrictOrNull(),
            addonSubtitleId = prefs[key(SUB_ADDON_ID, contentId)],
            addonSubtitleUrl = prefs[key(SUB_ADDON_URL, contentId)],
            addonSubtitleAddonName = prefs[key(SUB_ADDON_NAME, contentId)],
            audioLanguage = audioLang,
            audioName = audioName,
            audioTrackId = audioTrackId
        )
    }

    /**
     * Subtitle delay is persisted separately from audio/subtitle track selection
     * because it has different locality: tracks sensibly apply to every episode
     * of a series (same preferred language), but a delay calibrated against one
     * release/encode rarely transfers to the next episode. Keying by videoId
     * scopes the delay to exactly the video it was synced against. See #1063.
     */
    suspend fun saveSubtitleDelayMs(videoId: String, delayMs: Int?) {
        store().edit { prefs ->
            val k = intKey(SUB_DELAY_MS, videoId)
            if (delayMs != null && delayMs != 0) {
                prefs[k] = delayMs
                prefs[tsKey(videoId)] = System.currentTimeMillis().toString()
            } else {
                prefs.remove(k)
                // Drop the sidecar too unless the id still has other fields,
                // so cleared delays don't leave orphan timestamps behind.
                val ts = tsKey(videoId)
                if (!prefs.hasOtherEntryKeys(ts, videoId)) prefs.remove(ts)
            }
            enforceEntryCap(prefs)
        }
    }

    suspend fun loadSubtitleDelayMs(videoId: String): Int? {
        return store().data.first()[intKey(SUB_DELAY_MS, videoId)]
    }

    /**
     * Entries are the key groups sharing an id ("field|id"); [BoundedPrefs]
     * evicts whole groups oldest-save-time-first past the cap. Removal uses the
     * actual Key objects — a group mixes string and int keys, and Preferences
     * key equality is type-sensitive.
     */
    @Suppress("UNCHECKED_CAST")
    private fun enforceEntryCap(prefs: MutablePreferences) {
        val entryKeys = LinkedHashMap<String, MutableList<Preferences.Key<*>>>()
        prefs.asMap().keys.forEach { key ->
            if ('|' !in key.name) return@forEach
            entryKeys.getOrPut(key.name.substringAfter('|')) { mutableListOf() }.add(key)
        }
        val timestamps = entryKeys.mapValues { (id, _) ->
            prefs[tsKey(id)]?.toLongOrNull() ?: 0L
        }
        BoundedPrefs.planEviction(timestamps).forEach { id ->
            entryKeys[id]?.forEach { key ->
                // remove's generic T can't bind to Key<*>; deletion is by key
                // identity, the cast only satisfies the unused return type.
                prefs.remove(key as Preferences.Key<Any>)
            }
        }
    }
}

data class PersistedTrackPreference(
    val subtitleType: String?,
    val subtitleLanguage: String?,
    val subtitleName: String?,
    val subtitleTrackId: String?,
    val subtitleIsForced: Boolean? = null,
    val addonSubtitleId: String?,
    val addonSubtitleUrl: String?,
    val addonSubtitleAddonName: String?,
    val audioLanguage: String?,
    val audioName: String?,
    val audioTrackId: String?
)

internal fun PersistedTrackPreference.toTrackPreference(): com.nuvio.tv.core.player.PlayerRuntimeController.TrackPreference? {
    val audio = if (audioLanguage != null || audioName != null || audioTrackId != null) {
        com.nuvio.tv.core.player.PlayerRuntimeController.RememberedTrackSelection(
            language = audioLanguage,
            name = audioName,
            trackId = audioTrackId
        )
    } else null

    val subtitle = when (subtitleType) {
        "INTERNAL" -> com.nuvio.tv.core.player.PlayerRuntimeController.RememberedSubtitleSelection.Internal(
            track = com.nuvio.tv.core.player.PlayerRuntimeController.RememberedTrackSelection(
                language = subtitleLanguage,
                name = subtitleName,
                trackId = subtitleTrackId,
                isForcedHint = subtitleIsForced
            )
        )
        "ADDON" -> com.nuvio.tv.core.player.PlayerRuntimeController.RememberedSubtitleSelection.Addon(
            id = addonSubtitleId ?: "",
            url = addonSubtitleUrl ?: "",
            language = subtitleLanguage ?: "",
            addonName = addonSubtitleAddonName ?: ""
        )
        "DISABLED" -> com.nuvio.tv.core.player.PlayerRuntimeController.RememberedSubtitleSelection.Disabled
        else -> null
    }

    if (audio == null && subtitle == null) return null
    return com.nuvio.tv.core.player.PlayerRuntimeController.TrackPreference(
        audio = audio,
        subtitle = subtitle
    )
}
