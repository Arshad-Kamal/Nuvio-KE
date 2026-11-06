package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class CachedStreamLink(
    val url: String,
    val streamName: String,
    val headers: Map<String, String>,
    val cachedAtMs: Long,
    val filename: String? = null,
    val videoHash: String? = null,
    val videoSize: Long? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val sources: List<String>? = null,
    val bingeGroup: String? = null,
    val contentLanguage: String? = null,
    val year: String? = null
)

@Singleton
class StreamLinkCacheDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "stream_link_cache"
        private const val KEY_PREFIX = "stream_link_"
        // Values are our own JSONObject output (no whitespace); used only for
        // eviction ordering, so a miss just sorts the entry oldest.
        private val CACHED_AT_MS_REGEX = Regex("\"cachedAtMs\":(-?\\d+)")
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    suspend fun save(
        contentKey: String,
        url: String,
        streamName: String,
        headers: Map<String, String>?,
        filename: String? = null,
        videoHash: String? = null,
        videoSize: Long? = null,
        infoHash: String? = null,
        fileIdx: Int? = null,
        sources: List<String>? = null,
        bingeGroup: String? = null,
        contentLanguage: String? = null,
        year: String? = null
    ) {
        val payload = JSONObject().apply {
            put("url", url)
            put("streamName", streamName)
            put("cachedAtMs", System.currentTimeMillis())
            put("headers", JSONObject(headers ?: emptyMap<String, String>()))
            put("filename", filename)
            put("videoHash", videoHash)
            videoSize?.let { put("videoSize", it) }
            infoHash?.let { put("infoHash", it) }
            fileIdx?.let { put("fileIdx", it) }
            sources?.let { put("sources", JSONArray(it)) }
            bingeGroup?.let { put("bingeGroup", it) }
            contentLanguage?.let { put("contentLanguage", it) }
            year?.let { put("year", it) }
        }.toString()

        store().edit { prefs ->
            prefs[cachePrefKey(contentKey)] = payload
            enforceEntryCap(prefs)
        }
    }

    /**
     * Drops the remembered link for [contentKey] — used when a link is provably dead
     * (checked before reuse) so the next entry falls through to a fresh resolve.
     */
    suspend fun evict(contentKey: String) {
        store().edit { prefs -> prefs.remove(cachePrefKey(contentKey)) }
    }

    /**
     * The entry for [contentKey] when it is at most [maxAgeMs] old. An older entry reads as
     * null but stays (the Stream screen's "Last played" marker reads it with any age; the
     * entry cap bounds the store); an unusable one is dropped.
     */
    suspend fun getValid(contentKey: String, maxAgeMs: Long): CachedStreamLink? {
        if (maxAgeMs <= 0L) return null

        val key = cachePrefKey(contentKey)
        val raw = store().data.first()[key] ?: return null

        var tooOld = false
        val parsed = runCatching {
            val json = JSONObject(raw)
            val cachedAtMs = json.optLong("cachedAtMs", 0L)
            if (cachedAtMs <= 0L) return@runCatching null
            if (System.currentTimeMillis() - cachedAtMs > maxAgeMs) {
                tooOld = true
                return@runCatching null
            }

            val headersJson = json.optJSONObject("headers")
            val headers = buildMap {
                headersJson?.keys()?.forEach { headerKey ->
                    put(headerKey, headersJson.optString(headerKey, ""))
                }
            }.filterValues { it.isNotEmpty() }

            val url = json.optString("url", "")
            val streamName = json.optString("streamName", "")
            val infoHash = json.optString("infoHash", "").ifBlank { null }
            // Accept entry if it has either a real URL (HTTP stream) or an
            // infoHash (torrent stream — URL is re-resolved on each playback).
            if (streamName.isBlank() || (url.isBlank() && infoHash == null)) return@runCatching null

            val sourcesJson = json.optJSONArray("sources")
            val sources = sourcesJson?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optString(i).takeIf { it.isNotEmpty() }
                }
            }?.takeIf { it.isNotEmpty() }

            CachedStreamLink(
                url = url,
                streamName = streamName,
                headers = headers,
                cachedAtMs = cachedAtMs,
                filename = json.optString("filename", "").ifBlank { null },
                videoHash = json.optString("videoHash", "").ifBlank { null },
                videoSize = json.optLong("videoSize", -1L).takeIf { it >= 0L },
                infoHash = infoHash,
                fileIdx = if (json.has("fileIdx")) json.optInt("fileIdx", -1).takeIf { it >= 0 } else null,
                sources = sources,
                bingeGroup = json.optString("bingeGroup", "").ifBlank { null },
                contentLanguage = json.optString("contentLanguage", "").ifBlank { null },
                year = json.optString("year", "").ifBlank { null }
            )
        }.getOrNull()

        if (parsed == null && !tooOld) {
            store().edit { mutablePrefs ->
                mutablePrefs.remove(key)
            }
        }

        return parsed
    }

    // Each entry is one key whose JSON payload carries cachedAtMs; [BoundedPrefs]
    // evicts the oldest past the cap so long sessions cannot grow the store forever.
    private fun enforceEntryCap(prefs: androidx.datastore.preferences.core.MutablePreferences) {
        val timestamps = prefs.asMap()
            .filterKeys { it.name.startsWith(KEY_PREFIX) }
            .entries.associate { (key, value) -> key.name to cachedAtMsOf(value as? String) }
        BoundedPrefs.planEviction(timestamps).forEach { keyName ->
            prefs.remove(stringPreferencesKey(keyName))
        }
    }

    private fun cachedAtMsOf(raw: String?): Long = raw?.let {
        CACHED_AT_MS_REGEX.find(it)?.groupValues?.getOrNull(1)?.toLongOrNull()
    } ?: 0L

    private fun cachePrefKey(contentKey: String): Preferences.Key<String> {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(contentKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return stringPreferencesKey("$KEY_PREFIX$digest")
    }
}
