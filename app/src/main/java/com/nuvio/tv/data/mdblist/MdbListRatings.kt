package com.nuvio.tv.data.mdblist

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * The detail page's MDBList scores (Spec 105 S4, D1/D3/D7; mockup v3 B2, C1): one `GET /{imdb|tmdb}/{movie|show}/{id}/`
 * per title with the profile's one key, through [MdbListSession] (the daily pause refuses it unsent, D5). Scores are the
 * same for every user, so answers are kept app-wide in [MdbListRatingsCache] for [MdbListRatingsCache.TTL_MS]. A title
 * with no IMDb or TMDB id, a failure, or an answer with no score gives null and nothing is kept.
 */
class MdbListRatings(private val session: MdbListSession, private val cache: MdbListRatingsCache) {

    /** Cached scores (served while paused too), else one call. Throws what the session throws (signed out, paused, unreachable). */
    suspend fun scores(profileId: Int, itemId: String, itemType: String): MdbListScores? {
        val title = lookup(itemId, itemType) ?: return null
        cache.get(title.cacheKey)?.let { return it }
        val reply = session.call(profileId, MdbListRequest(MdbListHttpMethod.GET, title.path)).body
        val scores = parseMdbListScores(reply)?.takeIf { it.values.isNotEmpty() } ?: return null
        cache.put(title.cacheKey, scores)
        return scores
    }

    /** A title's cache key (`movie:tt...` / `show:tmdb:...`) and request path: by IMDb id, else TMDB id, else none. */
    private class Lookup(val cacheKey: String, val path: String)

    private fun lookup(itemId: String, itemType: String): Lookup? {
        if (isMdbListUnmatchable(itemId)) return null
        val target = MdbListLibraryTarget.of(itemId, itemType)
        val type = if (target.isMovie) "movie" else "show"
        val imdb = target.ids.imdb?.takeIf(MDBLIST_IMDB_ID::matches)
        if (imdb != null) return Lookup("$type:$imdb", "/imdb/$type/$imdb/")
        val tmdb = target.ids.tmdb ?: return null
        return Lookup("$type:tmdb:$tmdb", "/tmdb/$type/$tmdb/")
    }
}

/**
 * B2's eight sources in its default order; [max] bounds a plausible value (anything outside is dropped). Brand names,
 * not translated: [panelName] in B2's panel, [lineLabel] on C1's line.
 */
enum class MdbListRatingSource(val max: Double, val panelName: String, val lineLabel: String) {
    IMDB(10.0, "IMDb", "IMDb"),
    TOMATOES(100.0, "Rotten Tomatoes", "Tomatoes"),
    POPCORN(100.0, "Popcornmeter", "Popcorn"),
    METACRITIC(100.0, "Metacritic", "Metacritic"),
    LETTERBOXD(5.0, "Letterboxd", "Letterboxd"),
    TMDB(100.0, "TMDB", "TMDB"),
    TRAKT(100.0, "Trakt", "Trakt"),
    MAL(10.0, "MyAnimeList", "MAL")
}

/** One title's scores by source (only those MDBList has). */
@Serializable
data class MdbListScores(val values: Map<MdbListRatingSource, Double>)

/**
 * `ratings[]` {source, value, score} as upstream NuvioTV reads it (`MDBListRatingDtos.toRatings`): popcorn/audience is
 * the Popcornmeter, myanimelist/mal MyAnimeList; Letterboxd from `score` / 20 when present (its 0-100 form), else
 * `value`. The first row per source wins; an unknown source or an out-of-range value is left out. Null for a reply
 * that is not an object with a `ratings` array.
 */
internal fun parseMdbListScores(reply: String): MdbListScores? {
    val root = try {
        mdbListJson.parseToJsonElement(reply) as? JsonObject
    } catch (_: SerializationException) {
        null
    } ?: return null
    val rows = root["ratings"] as? JsonArray ?: return null
    val values = linkedMapOf<MdbListRatingSource, Double>()
    rows.forEach { element ->
        val row = element as? JsonObject ?: return@forEach
        val source = when ((row["source"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.lowercase()) {
            "imdb" -> MdbListRatingSource.IMDB
            "tomatoes" -> MdbListRatingSource.TOMATOES
            "popcorn", "audience", "tomatoesaudience" -> MdbListRatingSource.POPCORN
            "metacritic" -> MdbListRatingSource.METACRITIC
            "letterboxd" -> MdbListRatingSource.LETTERBOXD
            "tmdb" -> MdbListRatingSource.TMDB
            "trakt" -> MdbListRatingSource.TRAKT
            "myanimelist", "mal" -> MdbListRatingSource.MAL
            else -> return@forEach
        }
        if (source in values) return@forEach
        val raw = row["value"].number()
        val value = if (source == MdbListRatingSource.LETTERBOXD) row["score"].number()?.div(20.0) ?: raw else raw
        if (value != null && value.isFinite() && value >= 0.0 && value <= source.max) values[source] = value
    }
    return MdbListScores(values)
}

private fun JsonElement?.number(): Double? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull

/**
 * App-wide disk cache of scores (`filesDir/mdblist_sync/ratings.json`; excluded from backups with its folder), loaded
 * once, kept in memory, saved at most every [SAVE_DELAY_MS]. Entries older than [TTL_MS] are not served and are
 * dropped on save; at most [MAX_ENTRIES] are kept (newest first). An unreadable file starts empty.
 */
class MdbListRatingsCache(
    private val file: File,
    private val io: CoroutineDispatcher,
    scope: CoroutineScope,
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) {
    @Serializable
    internal data class Entry(val scores: MdbListScores, val storedAt: Long)

    private val lock = Mutex()
    private var entries: MutableMap<String, Entry>? = null
    private val saves = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (ignored in saves) {
                delay(SAVE_DELAY_MS)
                // Memory is trimmed with the file, so both stay within TTL_MS and MAX_ENTRIES.
                val snapshot = lock.withLock { entries?.let(::fresh)?.also { entries = it.toMutableMap() } } ?: continue
                withContext(io) { write(snapshot) }
            }
        }
    }

    suspend fun get(key: String): MdbListScores? = lock.withLock {
        val entry = loaded()[key] ?: return@withLock null
        entry.scores.takeIf { entry.isFresh(nowEpochMs()) }
    }

    suspend fun put(key: String, scores: MdbListScores) {
        lock.withLock { loaded()[key] = Entry(scores, nowEpochMs()) }
        saves.trySend(Unit)
    }

    private suspend fun loaded(): MutableMap<String, Entry> =
        entries ?: (withContext(io) { read() } ?: mutableMapOf()).also { entries = it }

    private fun fresh(all: Map<String, Entry>): Map<String, Entry> {
        val now = nowEpochMs()
        return all.entries.filter { it.value.isFresh(now) }
            .sortedByDescending { it.value.storedAt }
            .take(MAX_ENTRIES)
            .associate { it.key to it.value }
    }

    private fun Entry.isFresh(now: Long): Boolean = now - storedAt in 0 until TTL_MS

    private fun read(): MutableMap<String, Entry>? {
        if (!file.isFile) return null
        return try {
            mdbListJson.decodeFromString(CACHE_SERIALIZER, file.readText()).toMutableMap()
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            // SerializationException included.
            null
        }
    }

    private fun write(snapshot: Map<String, Entry>) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(mdbListJson.encodeToString(CACHE_SERIALIZER, snapshot))
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } catch (_: IOException) {
            // A cache: the next save tries again; a missing file only costs calls.
        }
    }

    internal companion object {
        /** Upstream's 12 h (scores move slowly; a day's browsing stays within the free allowance). */
        const val TTL_MS = 12L * 60 * 60 * 1_000
        const val SAVE_DELAY_MS = 5_000L
        const val MAX_ENTRIES = 2_000
        private val CACHE_SERIALIZER = MapSerializer(String.serializer(), Entry.serializer())
    }
}
