package com.nuvio.tv.core.scout

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.tmdb.TmdbApiKeySource
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** A full day between build attempts, so one profile asks TMDB at most once a day (D4: <= 16 small requests). */
const val SCOUT_REFRESH_MS = 24L * 60 * 60 * 1000

/**
 * Bump when [ScoutPick] or the ranking changes meaning, so old caches rebuild. Not bumped for S4's ratings:
 * no version 1 list shipped before them, and a list built without ratings is still a valid list.
 */
internal const val SCOUT_CACHE_VERSION = 1

/** The stored Picked for you list of one profile. [version] has no default, so it is always written. */
@Serializable
data class ScoutCache(val builtAt: Long, val language: String, val picks: List<ScoutPick>, val version: Int)

/** The last build attempt, stored before its first request; a failed, empty or cancelled build still counts. */
@Serializable
data class ScoutAttempt(val at: Long, val language: String, val version: Int)

/**
 * A build is due unless one was attempted in the last day for this language and version; otherwise when the
 * list is missing, of another version or language, or a day old. A time in the future counts as old (clock set
 * back), which costs at most one extra build.
 */
fun scoutRebuildDue(cache: ScoutCache?, attempt: ScoutAttempt?, language: String, now: Long): Boolean {
    if (attempt != null && attempt.version == SCOUT_CACHE_VERSION && attempt.language == language &&
        now - attempt.at in 0 until SCOUT_REFRESH_MS
    ) return false
    return cache == null || cache.version != SCOUT_CACHE_VERSION || cache.language != language ||
        now - cache.builtAt !in 0 until SCOUT_REFRESH_MS
}

/**
 * Picked for you per profile (Spec 100 S2): the switch (D4: On by default), the cached picks and the detail
 * ratings (S4, D3), in the profile's own store (feature [FEATURE]; not synced, goes with the profile and on
 * sign-out). Titles watched or rated since the build are filtered out on read; the list itself rebuilds only
 * when [scoutRebuildDue], so a new rating re-weights the picks at the next daily build.
 */
@Singleton
class ScoutPicksRepository @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val builder: ScoutPicksBuilder,
    private val tmdbService: TmdbService,
    private val apiKeys: TmdbApiKeySource
) {
    // One lock per profile: a build holds it across its requests, and another profile never waits on it.
    private val locks = ConcurrentHashMap<Int, Mutex>()

    fun enabled(profileId: Int): Flow<Boolean> =
        data(profileId).map { it[ENABLED_KEY] ?: true }.distinctUntilChanged()

    suspend fun setEnabled(profileId: Int, on: Boolean) {
        store(profileId).edit { it[ENABLED_KEY] = on }
    }

    /** The cached picks without any rated title, or empty while the switch is off or nothing is built. */
    fun cached(profileId: Int): Flow<List<ScoutPick>> = data(profileId)
        .map { prefs ->
            if (prefs[ENABLED_KEY] == false) return@map emptyList()
            val picks = decodeCache(prefs[CACHE_KEY])?.takeIf { it.version == SCOUT_CACHE_VERSION }?.picks.orEmpty()
            val ratings = decodeRatings(prefs[RATINGS_KEY])
            if (picks.isEmpty() || ratings.isEmpty()) return@map picks
            val rated = scoutRatedKeys(ratings)
            picks.filterNot { it.key in rated }
        }
        .distinctUntilChanged()

    /**
     * The detail rating on [profileId] of the title a page shows as [contentId] (and [imdbId]), or null: also one
     * saved from a page with another id of the same title (D6).
     */
    fun rating(profileId: Int, contentId: String, imdbId: String?, tv: Boolean): Flow<ScoutRating?> = data(profileId)
        .map { prefs -> decodeRatings(prefs[RATINGS_KEY]).ratingFor(titleKeys(contentId, imdbId, tv))?.rating }
        .distinctUntilChanged()

    /**
     * Sets the rating on [profileId] of the title [contentId] / [imdbId] / [tmdbId] (every id the page knows, D6)
     * to [rating], or clears it when null; any rating stored under one of its ids goes. A rating needs its
     * [tmdbId] (D7: without it nothing is saved); clearing does not. False when nothing was saved or the store
     * cannot be written. An unreadable stored list starts over rather than blocking every rating.
     */
    suspend fun setRating(
        profileId: Int,
        contentId: String,
        tv: Boolean,
        name: String,
        rating: ScoutRating?,
        imdbId: String? = null,
        tmdbId: Int? = null,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        if (contentId.isBlank() || (rating != null && tmdbId == null)) return false
        val imdb = imdbId?.trim()?.takeIf { it.startsWith("tt") }
        val entry = rating?.let { ScoutRatingEntry(contentId, tv, name, it, now, imdb, tmdbId) }
        val keys = titleKeys(contentId, imdb, tv) + listOfNotNull(tmdbId?.let { scoutKey(it, tv) })
        return write(store(profileId)) { prefs ->
            val next = scoutRatingsWith(decodeRatings(prefs[RATINGS_KEY]), keys, entry)
            if (next.isEmpty()) prefs.remove(RATINGS_KEY) else prefs[RATINGS_KEY] = json.encodeToString(ratingsSerializer, next)
        }
    }

    /** [picks] without anything [watched] or [progress] now holds; never a request. */
    fun visible(picks: List<ScoutPick>, watched: List<WatchedItem>, progress: List<WatchProgress>): List<ScoutPick> {
        if (picks.isEmpty()) return picks
        val exclusions = scoutLocalExclusions(watched, progress, tmdbService::cachedTmdbId)
        return picks.filterNot { exclusions.blocks(it.tmdbId, it.tv, it.name) }
    }

    /**
     * Builds and stores [profileId]'s picks when [scoutRebuildDue]. Nothing happens with the switch off, with no
     * TMDB key, or when the store cannot be read or the attempt cannot be recorded. An empty build keeps any
     * stored list; the next try comes a day later. [profileId] is the profile the caller read [watched] and
     * [progress] for, so a profile switch mid-build never stores one profile's picks in another's file.
     * Run it in a scope that outlives the screen: a cancelled build still used up the day's attempt.
     */
    suspend fun refreshIfDue(
        profileId: Int,
        watched: List<WatchedItem>,
        progress: List<WatchProgress>,
        language: String,
        now: Long = System.currentTimeMillis()
    ) {
        locks.getOrPut(profileId) { Mutex() }.withLock {
            val store = store(profileId)
            val prefs = try {
                store.data.first()
            } catch (error: IOException) {
                Log.w(TAG, "Scout store read failed: ${error.javaClass.simpleName}")
                return
            }
            if (prefs[ENABLED_KEY] == false) return
            val due = scoutRebuildDue(decodeCache(prefs[CACHE_KEY]), decodeAttempt(prefs[ATTEMPT_KEY]), language, now)
            val ratings = decodeRatings(prefs[RATINGS_KEY])
            // Nothing to ask more of yet (no history, or only minuses): no attempt, so the first title the
            // profile watches or likes builds at once.
            if (!due || apiKeys.key().isBlank() || scoutSeeds(watched, progress, now, ratings).none { it.weight > 0 }) return

            val attempt = ScoutAttempt(at = now, language = language, version = SCOUT_CACHE_VERSION)
            if (!write(store) { it[ATTEMPT_KEY] = json.encodeToString(ScoutAttempt.serializer(), attempt) }) return
            val picks = builder.build(watched, progress, ratings, language, now)
            if (picks.isEmpty()) return
            val cache = ScoutCache(builtAt = now, language = language, picks = picks, version = SCOUT_CACHE_VERSION)
            write(store) { it[CACHE_KEY] = json.encodeToString(ScoutCache.serializer(), cache) }
        }
    }

    private suspend fun write(store: DataStore<Preferences>, change: (MutablePreferences) -> Unit): Boolean =
        try {
            store.edit { change(it) }
            true
        } catch (error: IOException) {
            Log.w(TAG, "Scout store write failed: ${error.javaClass.simpleName}")
            false
        }

    private fun titleKeys(contentId: String, imdbId: String?, tv: Boolean): Set<String> =
        scoutContentKeys(contentId, tv) + scoutContentKeys(imdbId?.trim()?.takeIf { it.startsWith("tt") }.orEmpty(), tv)

    private fun store(profileId: Int): DataStore<Preferences> = factory.get(profileId, FEATURE)

    private fun data(profileId: Int): Flow<Preferences> = store(profileId).data.catch { error ->
        if (error !is IOException) throw error
        Log.w(TAG, "Scout store read failed: ${error.javaClass.simpleName}")
        emit(emptyPreferences())
    }

    private fun decodeCache(raw: String?): ScoutCache? = raw?.let {
        runCatching { json.decodeFromString(ScoutCache.serializer(), it) }.getOrNull()
    }

    private fun decodeAttempt(raw: String?): ScoutAttempt? = raw?.let {
        runCatching { json.decodeFromString(ScoutAttempt.serializer(), it) }.getOrNull()
    }

    private fun decodeRatings(raw: String?): List<ScoutRatingEntry> = raw?.let {
        runCatching { json.decodeFromString(ratingsSerializer, it) }.getOrNull()
    }.orEmpty()

    companion object {
        const val FEATURE = "scout"
        private const val TAG = "ScoutPicksRepository"
        private val ENABLED_KEY = booleanPreferencesKey("enabled")
        private val CACHE_KEY = stringPreferencesKey("picks")
        private val ATTEMPT_KEY = stringPreferencesKey("attempt")
        private val RATINGS_KEY = stringPreferencesKey("ratings")
        private val json = Json { ignoreUnknownKeys = true }
        private val ratingsSerializer = ListSerializer(ScoutRatingEntry.serializer())
    }
}
