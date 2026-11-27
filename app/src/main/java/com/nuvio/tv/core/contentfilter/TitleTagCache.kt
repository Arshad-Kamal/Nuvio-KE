package com.nuvio.tv.core.contentfilter

import android.content.Context
import android.util.Log
import com.nuvio.tv.data.local.localStoreJson
import com.nuvio.tv.domain.contentfilter.TitleTags
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** A cache read: tags, a words-only verdict, or nothing yet. */
internal sealed interface CachedTags {
    data class Tags(val tags: TitleTags) : CachedTags
    data object WordsOnly : CachedTags
    data object Missing : CachedTags
}

/**
 * Spec 117 P4: TMDB tags per title, one compact JSON file in the app's cache dir, shared by all
 * profiles (tags describe a title, not a profile). Entries live 30 days (words-only verdicts 7), at most
 * [maxEntries] each (oldest dropped); writes are debounced snapshots (temp file + rename); a corrupt
 * or oversized file reads as empty. Reads before [ensureLoaded] see nothing.
 */
@Singleton
class TitleTagCache internal constructor(
    private val file: File,
    private val nowMs: () -> Long,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val writeDelayMs: Long = WRITE_DELAY_MS,
    private val maxEntries: Int = MAX_ENTRIES,
    private val maxFileBytes: Long = MAX_FILE_BYTES
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        File(context.cacheDir, FILE_NAME),
        System::currentTimeMillis,
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
        Dispatchers.IO
    )

    private class Stored(val tags: TitleTags, val at: Long)
    private class Alias(val tmdbId: Int, val at: Long)

    private val tags = ConcurrentHashMap<String, Stored>()      // "movie:123"
    private val aliases = ConcurrentHashMap<String, Alias>()    // "movie:tt0133093" -> TMDB id
    private val wordsOnly = ConcurrentHashMap<String, Long>()   // TitleRef.cacheKey -> stored at

    private val loadMutex = Mutex()
    private val writeMutex = Mutex()
    private val writePending = AtomicBoolean(false)

    @Volatile
    private var loaded = false

    val isLoaded: Boolean get() = loaded

    suspend fun ensureLoaded() {
        if (loaded) return
        loadMutex.withLock {
            if (loaded) return
            withContext(ioDispatcher) { readFile() }?.let(::restore)
            loaded = true
        }
    }

    /** A map read and a TTL check; safe on any thread, cheap enough for every draw pass. */
    internal fun read(ref: TitleRef): CachedTags {
        if (!loaded) return CachedTags.Missing
        val now = nowMs()
        wordsOnly[ref.cacheKey]?.let { at ->
            if (now - at <= WORDS_ONLY_TTL_MS) return CachedTags.WordsOnly
            wordsOnly.remove(ref.cacheKey, at)
        }
        val tmdbId = when (val target = ref.target()) {
            is TmdbTarget.ById -> target.tmdbId
            is TmdbTarget.ByImdb -> aliasFor(ref.kind, target.imdbId) ?: return CachedTags.Missing
            TmdbTarget.Unresolvable -> return CachedTags.WordsOnly
        }
        val key = tagKey(ref.kind, tmdbId)
        val stored = tags[key] ?: return CachedTags.Missing
        if (now - stored.at > TAGS_TTL_MS) {
            tags.remove(key, stored)
            return CachedTags.Missing
        }
        return CachedTags.Tags(stored.tags)
    }

    internal fun aliasFor(kind: TitleKind, imdbId: String): Int? {
        val key = "${kind.key}:$imdbId"
        val alias = aliases[key] ?: return null
        if (nowMs() - alias.at > TAGS_TTL_MS) {
            aliases.remove(key, alias)
            return null
        }
        return alias.tmdbId
    }

    internal fun putTags(kind: TitleKind, tmdbId: Int, fetched: FetchedTags) {
        tags[tagKey(kind, tmdbId)] = Stored(fetched.toTitleTags(), nowMs())
        trim(tags) { it.at }
        scheduleWrite()
    }

    internal fun putAlias(kind: TitleKind, imdbId: String, tmdbId: Int) {
        aliases["${kind.key}:$imdbId"] = Alias(tmdbId, nowMs())
        trim(aliases) { it.at }
        scheduleWrite()
    }

    internal fun putWordsOnly(ref: TitleRef) {
        wordsOnly[ref.cacheKey] = nowMs()
        trim(wordsOnly) { it }
        scheduleWrite()
    }

    /** Writes the snapshot now (tests; the debounced write calls it too). */
    internal suspend fun flush() {
        // A snapshot before the load would replace the file with only the newer entries.
        ensureLoaded()
        writeMutex.withLock {
            writePending.set(false)
            val snapshot = snapshot()
            withContext(ioDispatcher) { writeFile(localStoreJson.encodeToString(TitleTagSnapshot.serializer(), snapshot)) }
        }
    }

    private fun scheduleWrite() {
        if (!writePending.compareAndSet(false, true)) return
        scope.launch {
            delay(writeDelayMs)
            try {
                flush()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "tag cache write failed: ${e.javaClass.simpleName}")
            }
        }
    }

    // Over the cap by a margin, trim back to the cap, oldest first (one sort per ~2% growth, not per put).
    private fun <V : Any> trim(map: ConcurrentHashMap<String, V>, at: (V) -> Long) {
        if (map.size <= maxEntries + trimMargin) return
        val excess = map.size - maxEntries
        map.entries.sortedBy { at(it.value) }.take(excess).forEach { map.remove(it.key, it.value) }
    }

    private val trimMargin: Int get() = (maxEntries / 50).coerceAtLeast(1)

    private fun snapshot(): TitleTagSnapshot {
        val names = HashMap<Int, String>()
        val entries = tags.map { (key, stored) ->
            stored.tags.keywordIds.zip(stored.tags.keywordNames).forEach { (id, name) -> names[id] = name }
            TitleTagSnapshotEntry(
                key = key,
                genres = stored.tags.genreIds.toList(),
                keywords = stored.tags.keywordIds.toList(),
                language = stored.tags.originalLanguage,
                adult = stored.tags.adult,
                at = stored.at
            )
        }
        return TitleTagSnapshot(
            tags = entries,
            keywordNames = names,
            aliases = aliases.map { (key, alias) -> TitleTagSnapshotAlias(key, alias.tmdbId, alias.at) },
            wordsOnly = HashMap(wordsOnly)
        )
    }

    private fun restore(snapshot: TitleTagSnapshot) {
        val now = nowMs()
        snapshot.tags.forEach { entry ->
            if (now - entry.at > TAGS_TTL_MS) return@forEach
            val names = entry.keywords.map { snapshot.keywordNames[it].orEmpty() }
            val restored = TitleTags(
                genreIds = entry.genres.toSet(),
                keywordIds = entry.keywords.toSet(),
                keywordNames = names,
                originalLanguage = entry.language,
                adult = entry.adult
            )
            // A put that raced the load is newer: keep it.
            tags.putIfAbsent(entry.key, Stored(restored, entry.at))
        }
        snapshot.aliases.forEach { alias ->
            if (now - alias.at <= TAGS_TTL_MS) aliases.putIfAbsent(alias.key, Alias(alias.tmdbId, alias.at))
        }
        snapshot.wordsOnly.forEach { (key, at) ->
            if (now - at <= WORDS_ONLY_TTL_MS) wordsOnly.putIfAbsent(key, at)
        }
    }

    private fun readFile(): TitleTagSnapshot? {
        if (!file.exists()) return null
        return try {
            if (file.length() > maxFileBytes) {
                Log.w(TAG, "tag cache file too large (${file.length()} bytes); starting empty")
                return null
            }
            localStoreJson.decodeFromString(TitleTagSnapshot.serializer(), file.readText())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Corrupt or unreadable: an empty cache refills from TMDB.
            Log.w(TAG, "tag cache unreadable (${e.javaClass.simpleName}); starting empty")
            null
        }
    }

    private fun writeFile(content: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) Log.w(TAG, "tag cache rename failed")
        }
    }

    internal companion object {
        const val TAG = "TitleTagCache"
        const val FILE_NAME = "content_filter_title_tags.json"
        const val TAGS_TTL_MS = 30L * 24 * 60 * 60 * 1000
        const val WORDS_ONLY_TTL_MS = 7L * 24 * 60 * 60 * 1000
        const val MAX_ENTRIES = 20_000
        const val WRITE_DELAY_MS = 2_000L

        // ~2-3 MB at the cap; anything far larger was not written by this code.
        const val MAX_FILE_BYTES = 16L * 1024 * 1024

        fun tagKey(kind: TitleKind, tmdbId: Int): String = "${kind.key}:$tmdbId"
    }
}

private fun FetchedTags.toTitleTags(): TitleTags {
    val distinct = keywords.distinctBy { it.first }
    return TitleTags(
        genreIds = genreIds.toSet(),
        keywordIds = distinct.mapTo(LinkedHashSet()) { it.first },
        keywordNames = distinct.map { it.second.lowercase() },
        originalLanguage = originalLanguage,
        adult = adult
    )
}

@Serializable
internal data class TitleTagSnapshot(
    @SerialName("v") val version: Int = 1,
    @SerialName("tags") val tags: List<TitleTagSnapshotEntry> = emptyList(),
    @SerialName("names") val keywordNames: Map<Int, String> = emptyMap(),
    @SerialName("aliases") val aliases: List<TitleTagSnapshotAlias> = emptyList(),
    @SerialName("wordsOnly") val wordsOnly: Map<String, Long> = emptyMap()
)

@Serializable
internal data class TitleTagSnapshotEntry(
    @SerialName("k") val key: String,
    @SerialName("g") val genres: List<Int> = emptyList(),
    @SerialName("kw") val keywords: List<Int> = emptyList(),
    @SerialName("l") val language: String? = null,
    @SerialName("a") val adult: Boolean = false,
    @SerialName("t") val at: Long
)

@Serializable
internal data class TitleTagSnapshotAlias(
    @SerialName("k") val key: String,
    @SerialName("id") val tmdbId: Int,
    @SerialName("t") val at: Long
)
