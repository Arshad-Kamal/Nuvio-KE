package com.nuvio.tv.data.trailer

import android.util.Log
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.util.extractReleaseYear
import com.nuvio.tv.core.util.getOrNullUnlessCancelled
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbVideoResult
import com.nuvio.tv.data.local.TrailerSettingsDataStore
import com.nuvio.tv.data.local.TrailerSource
import java.time.Clock
import java.net.URI
import java.time.Instant
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private const val TAG = "TrailerService"
private const val TMDB_TRAILER_FALLBACK_LANGUAGE = "en-US"
private val YOUTUBE_SOURCE_CACHE_TTL: Duration = Duration.ofHours(3)

@Singleton
class TrailerService(
    private val tmdbApi: TmdbApi,
    private val inAppYouTubeExtractor: InAppYouTubeExtractor,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val tmdbService: TmdbService,
    private val imdbTrailerResolver: ImdbTrailerResolver,
    private val trailerSettingsDataStore: TrailerSettingsDataStore,
    private val clock: Clock
) {
    @Inject
    constructor(
        tmdbApi: TmdbApi,
        inAppYouTubeExtractor: InAppYouTubeExtractor,
        tmdbSettingsDataStore: TmdbSettingsDataStore,
        tmdbService: TmdbService,
        imdbTrailerResolver: ImdbTrailerResolver,
        trailerSettingsDataStore: TrailerSettingsDataStore
    ) : this(
        tmdbApi = tmdbApi,
        inAppYouTubeExtractor = inAppYouTubeExtractor,
        tmdbSettingsDataStore = tmdbSettingsDataStore,
        tmdbService = tmdbService,
        imdbTrailerResolver = imdbTrailerResolver,
        trailerSettingsDataStore = trailerSettingsDataStore,
        clock = Clock.systemUTC()
    )

    // Cache: "title|year|tmdbId|imdbId|type|source" -> wrapped source (NEGATIVE_CACHE sentinel for
    // misses). Wrapped so every hit routes through the same expiry check as the youtube
    // cache: signed playback URLs expire within hours and must not serve for process life.
    private val cache = ConcurrentHashMap<String, CachedTrailerPlaybackSource>()
    private val NEGATIVE_CACHE = TrailerPlaybackSource(videoUrl = "")
    // Time-bound cache: youtubeVideoId -> resolved playback source (success-only)
    private val youtubeSourceCache = ConcurrentHashMap<String, CachedTrailerPlaybackSource>()
    // In-flight resolves by generation + cache key; see resolveShared.
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<TrailerPlaybackSource?>>()
    // Bumped by clearCache (a TMDB key or language change): a resolve started before it neither
    // keeps its result in [cache] (cacheIfCurrent) nor serves it to a request made after it.
    private val cacheGeneration = AtomicInteger()

    /**
     * The catalog-item entry (Home, folder, details): resolves the item's TMDB id (null when
     * that fails; cancellation propagates), then searches by title, release year, id and type.
     * An item whose own id is an IMDb id hands it to the IMDb path, which then needs no TMDB
     * lookup (Spec 52 S4: no key, no tmdb->imdb conversion).
     */
    suspend fun getTrailerPlaybackSourceForItem(
        itemId: String,
        title: String,
        releaseInfo: String?,
        apiType: String
    ): TrailerPlaybackSource? {
        val tmdbId = try {
            tmdbService.ensureTmdbId(itemId, apiType)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return getTrailerPlaybackSource(
            title = title,
            year = extractReleaseYear(releaseInfo),
            tmdbId = tmdbId,
            type = apiType,
            imdbId = imdbIdOf(itemId)
        )
    }

    /**
     * Search for a trailer by title, year, tmdbId, and type; [imdbId] (the item's own) wins over
     * converting [tmdbId] on the IMDb path.
     * Returns the trailer playback source (video URL + optional separate audio URL) or null.
     */
    suspend fun getTrailerPlaybackSource(
        title: String,
        year: String? = null,
        tmdbId: String? = null,
        type: String? = null,
        imdbId: String? = null
    ): TrailerPlaybackSource? = withContext(Dispatchers.IO) {
        // Before the settings it is paired with: a clearCache after this read (a language or key change)
        // marks everything below as stale, whichever settings it read.
        val generation = cacheGeneration.get()
        // Read both settings once up front. `useTrailers` (the TMDB settings
        // Trailers toggle) gates the TMDB->YouTube path only (#1647).
        // The IMDb source is a separate, user-selected opt-in ("Use IMDb trailers")
        // and must run independently of that gate, otherwise an unrelated TMDB
        // setting silently disables IMDb.
        val tmdbSettings = runCatching { tmdbSettingsDataStore.settings.first() }.getOrNullUnlessCancelled()
        val useTrailers = tmdbSettings?.useTrailers == true

        // Read the trailer-source setting BEFORE building the cache key and fold it in, so
        // toggling YouTube<->IMDb takes effect immediately (each source has its own cache
        // namespace) instead of serving this session's stale other-source entries.
        val trailerSettings = runCatching { trailerSettingsDataStore.settings.first() }.getOrNullUnlessCancelled()
        val trailerSource = trailerSettings?.source ?: TrailerSource.YOUTUBE

        // Nothing to surface when trailers are disabled AND the user hasn't opted
        // into IMDb: the only remaining source here is the (now-gated) TMDB->YouTube
        // path, so return no trailer. Preserves #1647 for the YouTube case.
        if (!useTrailers && trailerSource != TrailerSource.IMDB) {
            Log.d(TAG, "Trailers disabled in TMDB settings; skipping lookup")
            return@withContext null
        }

        val cacheKey = "$title|$year|$tmdbId|$imdbId|$type|$trailerSource"

        cache[cacheKey]?.let { cached ->
            if (!cached.isExpired()) {
                val source = cached.playbackSource
                val hit = source !== NEGATIVE_CACHE
                Log.d(TAG, "Cache hit for $cacheKey: $hit")
                return@withContext if (hit) source else null
            }
            Log.d(TAG, "Cache entry expired for $cacheKey; re-resolving")
            cache.remove(cacheKey, cached)
        }

        resolveShared("$generation|$cacheKey") {
            resolveUncached(
                cacheKey, generation, title, year, tmdbId, imdbId, type, trailerSource, useTrailers,
                tmdbSettings?.language
            )
        }
    }

    /**
     * Caches [source] under [key] unless [clearCache] ran since [generation] was read. The first check
     * keeps a stale result from overwriting a newer generation's entry; the put-then-check covers a
     * clear landing in between: it either bumps the generation before the second check (removed here)
     * or clears after the put (removed there), so a stale entry never outlives a clear.
     */
    private fun cacheIfCurrent(key: String, generation: Int, source: TrailerPlaybackSource) {
        if (cacheGeneration.get() != generation) return
        val entry = cachedEntry(source)
        cache[key] = entry
        if (cacheGeneration.get() != generation) cache.remove(key, entry)
    }

    /**
     * Joins a concurrent resolve of the same [key] instead of starting a second one (the IMDb
     * path builds a WebView per resolve). The work runs in the first caller's coroutine, so
     * cancelling that caller still cancels it; a joiner that sees it cancelled takes over.
     * CompletableDeferred + putIfAbsent + remove(key, value): the TmdbService in-flight pattern.
     */
    private suspend fun resolveShared(
        key: String,
        resolve: suspend () -> TrailerPlaybackSource?
    ): TrailerPlaybackSource? {
        while (true) {
            val owned = CompletableDeferred<TrailerPlaybackSource?>()
            val existing = inFlight.putIfAbsent(key, owned)
            if (existing == null) {
                try {
                    return resolve().also { owned.complete(it) }
                } catch (e: Throwable) {
                    owned.completeExceptionally(e)
                    throw e
                } finally {
                    inFlight.remove(key, owned)
                }
            }
            try {
                return existing.await()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive() // this caller was cancelled: propagate
                inFlight.remove(key, existing) // the owner was cancelled: retry as the owner
            }
        }
    }

    private suspend fun resolveUncached(
        cacheKey: String,
        generation: Int,
        title: String,
        year: String?,
        tmdbId: String?,
        imdbId: String?,
        type: String?,
        trailerSource: TrailerSource,
        useTrailers: Boolean,
        tmdbSettingsLanguage: String?
    ): TrailerPlaybackSource? {
        return try {
            Log.d(TAG, "Searching trailer: title=$title, year=$year, tmdbId=$tmdbId, type=$type")

            // IMDb-first path (opt-in via Settings > Layout). On success, cache + return;
            // on null (no trailer >=720p -- SD-only or scene-only title) fall through to the
            // existing TMDB->YouTube path below. This is the null-means-fallthrough contract the
            // hero/detail callers already expect (they treat a returned source as cacheable).
            if (trailerSource == TrailerSource.IMDB) {
                val resolvedImdbId = imdbId
                    ?: tmdbId?.toIntOrNull()?.let { runCatching { tmdbService.tmdbToImdb(it, type ?: "movie") }.getOrNullUnlessCancelled() }
                if (resolvedImdbId != null) {
                    val imdbSource = imdbTrailerResolver.resolve(resolvedImdbId)
                    if (imdbSource != null) {
                        cacheIfCurrent(cacheKey, generation, imdbSource)
                        return imdbSource
                    }
                    Log.d(TAG, "IMDb resolve returned null for $resolvedImdbId; falling through to YouTube")
                }
            }

            // TMDB->YouTube path. Gated on `useTrailers` (#1647): when trailers are
            // disabled in TMDB settings, skip it entirely. An IMDb-only user with
            // the gate off gets IMDb-or-nothing from this function, and the caller's
            // meta-provided YouTube fallback still applies. Not negative-cached: the
            // "no trailer" result here is gate-conditional, so caching it under the
            // gate-independent key would go stale if the user re-enables trailers.
            if (!useTrailers) {
                return null
            }

            val tmdbLanguage = normalizeTmdbTrailerLanguage(tmdbSettingsLanguage)
            val tmdbSource = getTrailerPlaybackSourceFromTmdbId(
                tmdbId = tmdbId,
                type = type,
                tmdbLanguage = tmdbLanguage
            )
            if (tmdbSource != null) {
                cacheIfCurrent(cacheKey, generation, tmdbSource)
                return tmdbSource
            }
            Log.w(TAG, "TMDB path exhausted; no YouTube trailer resolved")
            // Only cache negative result if tmdbId was available — if null, enrichment
            // may not have completed yet and a retry with tmdbId could succeed.
            if (tmdbId != null) {
                cacheIfCurrent(cacheKey, generation, NEGATIVE_CACHE)
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching trailer for $title: ${e.message}", e)
            null
        }
    }

    /**
     * TMDB-first resolution using /movie/{id}/videos or /tv/{id}/videos.
     * [tmdbLanguage] is the already-normalized TMDB settings language.
     */
    private suspend fun getTrailerPlaybackSourceFromTmdbId(
        tmdbId: String?,
        type: String?,
        tmdbLanguage: String
    ): TrailerPlaybackSource? = withContext(Dispatchers.IO) {
        val numericTmdbId = tmdbId?.toIntOrNull() ?: return@withContext null
        val mediaType = normalizeTmdbMediaType(type)
        Log.d(
            TAG,
            "TMDB trailer lookup start: tmdbId=$numericTmdbId type=${mediaType ?: "unknown"} language=$tmdbLanguage"
        )

        val tmdbResults = when (mediaType) {
            "movie" -> fetchTmdbMovieVideos(numericTmdbId, tmdbLanguage)
            "tv" -> fetchTmdbTvVideos(numericTmdbId, tmdbLanguage)
            else -> fetchTmdbMovieVideos(numericTmdbId, tmdbLanguage) + fetchTmdbTvVideos(numericTmdbId, tmdbLanguage)
        }

        val candidates = rankTmdbVideoCandidates(tmdbResults, preferredLanguageCode = tmdbLanguage)
        Log.d(TAG, "TMDB candidate count: ${candidates.size}")

        for (candidate in candidates) {
            val key = candidate.key?.trim().orEmpty()
            if (key.isBlank()) continue
            Log.d(
                TAG,
                "TMDB selected candidate: type=${candidate.type.orEmpty()} " +
                    "official=${candidate.official == true} key=${obfuscateYoutubeKey(key)}"
            )

            val youtubeUrl = "https://www.youtube.com/watch?v=$key"
            val source = getTrailerPlaybackSourceFromYouTubeUrl(youtubeUrl)
            if (source != null) {
                return@withContext source
            }

            Log.d(
                TAG,
                "TMDB candidate extraction failed, trying next: key=${obfuscateYoutubeKey(key)}"
            )
        }

        null
    }

    /** Resolve a YouTube trailer URL to a playback source via in-app extraction. */
    suspend fun getTrailerPlaybackSourceFromYouTubeUrl(youtubeUrl: String): TrailerPlaybackSource? = withContext(Dispatchers.IO) {
        try {
            val youtubeKey = youtubeVideoId(youtubeUrl)
            if (youtubeKey == null) {
                Log.w(TAG, "Not a YouTube video URL: ${summarizeUrl(youtubeUrl)}")
                return@withContext null
            }
            getValidCachedYoutubeSource(youtubeKey)?.let { cached ->
                Log.d(TAG, "YouTube cache hit for key=${obfuscateYoutubeKey(youtubeKey)}")
                return@withContext cached
            }

            Log.d(TAG, "Attempting in-app YouTube extraction for ${summarizeUrl(youtubeUrl)}")
            val localSource = inAppYouTubeExtractor.extractPlaybackSource(youtubeKey)
            if (localSource != null) {
                youtubeSourceCache[youtubeKey] = cachedEntry(localSource)
                Log.d(
                    TAG,
                    "Using in-app YouTube source for ${summarizeUrl(youtubeUrl)} " +
                        "(audioPresent=${!localSource.audioUrl.isNullOrBlank()})"
                )
                return@withContext localSource
            }

            Log.w(TAG, "In-app extraction failed for ${summarizeUrl(youtubeUrl)}")
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error getting trailer from YouTube: ${e.message}", e)
            null
        }
    }

    private suspend fun fetchTmdbMovieVideos(tmdbId: Int, preferredLanguage: String): List<TmdbVideoResult> {
        val localized = fetchTmdbMovieVideosOnce(tmdbId, preferredLanguage)
        if (localized.isNotEmpty() || preferredLanguage.equals(TMDB_TRAILER_FALLBACK_LANGUAGE, ignoreCase = true)) {
            return localized
        }
        Log.d(TAG, "TMDB movie videos localized miss for $tmdbId ($preferredLanguage), retrying $TMDB_TRAILER_FALLBACK_LANGUAGE")
        return fetchTmdbMovieVideosOnce(tmdbId, TMDB_TRAILER_FALLBACK_LANGUAGE)
    }

    private suspend fun fetchTmdbTvVideos(tmdbId: Int, preferredLanguage: String): List<TmdbVideoResult> {
        val localized = fetchTmdbTvVideosOnce(tmdbId, preferredLanguage)
        if (localized.isNotEmpty() || preferredLanguage.equals(TMDB_TRAILER_FALLBACK_LANGUAGE, ignoreCase = true)) {
            return localized
        }
        Log.d(TAG, "TMDB tv videos localized miss for $tmdbId ($preferredLanguage), retrying $TMDB_TRAILER_FALLBACK_LANGUAGE")
        return fetchTmdbTvVideosOnce(tmdbId, TMDB_TRAILER_FALLBACK_LANGUAGE)
    }

    private suspend fun fetchTmdbMovieVideosOnce(tmdbId: Int, language: String): List<TmdbVideoResult> {
        return try {
            val response = tmdbApi.getMovieVideos(
                movieId = tmdbId,
                apiKey = tmdbService.apiKey(),
                language = language
            )
            if (!response.isSuccessful) {
                Log.w(TAG, "TMDB movie videos request failed ($tmdbId/$language): ${response.code()}")
                emptyList()
            } else {
                response.body()?.results.orEmpty()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "TMDB movie videos error ($tmdbId/$language): ${e.message}")
            emptyList()
        }
    }

    private suspend fun fetchTmdbTvVideosOnce(tmdbId: Int, language: String): List<TmdbVideoResult> {
        return try {
            val response = tmdbApi.getTvVideos(
                tvId = tmdbId,
                apiKey = tmdbService.apiKey(),
                language = language
            )
            if (!response.isSuccessful) {
                Log.w(TAG, "TMDB tv videos request failed ($tmdbId/$language): ${response.code()}")
                emptyList()
            } else {
                response.body()?.results.orEmpty()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "TMDB tv videos error ($tmdbId/$language): ${e.message}")
            emptyList()
        }
    }

    private fun summarizeUrl(url: String): String {
        return runCatching {
            val uri = URI(url)
            val host = uri.host ?: "unknown-host"
            val path = uri.path ?: "/"
            "$host$path"
        }.getOrDefault(url.take(80))
    }

    private fun obfuscateYoutubeKey(key: String): String {
        if (key.length <= 4) return "****"
        return "***${key.takeLast(4)}"
    }

    private fun getValidCachedYoutubeSource(youtubeKey: String): TrailerPlaybackSource? {
        val cached = youtubeSourceCache[youtubeKey] ?: return null
        if (!cached.isExpired()) {
            return cached.playbackSource
        }
        youtubeSourceCache.remove(youtubeKey, cached)
        return null
    }

    private fun cachedEntry(source: TrailerPlaybackSource): CachedTrailerPlaybackSource =
        CachedTrailerPlaybackSource(
            playbackSource = source,
            cachedAt = Instant.now(clock),
            expiresAt = extractUrlExpireInstant(source)
        )

    /** URL expire timestamp when present (/expire/EPOCH/), else the shared cache TTL. */
    private fun CachedTrailerPlaybackSource.isExpired(): Boolean {
        val now = Instant.now(clock)
        return expiresAt?.let { now.isAfter(it) }
            ?: (Duration.between(cachedAt, now) > YOUTUBE_SOURCE_CACHE_TTL)
    }

    /** Generation first, then the clear: [cacheIfCurrent] relies on that order. */
    fun clearCache() {
        cacheGeneration.incrementAndGet()
        cache.clear()
        youtubeSourceCache.clear()
    }

    /**
     * Extracts the YouTube URL expiration timestamp from the `/expire/EPOCH/` path segment.
     */
    private fun extractUrlExpireInstant(source: TrailerPlaybackSource): Instant? {
        val url = source.videoUrl
        val expireRegex = Regex("/expire/(\\d+)/")
        val match = expireRegex.find(url) ?: return null
        val epoch = match.groupValues[1].toLongOrNull() ?: return null
        return Instant.ofEpochSecond(epoch)
    }

    private data class CachedTrailerPlaybackSource(
        val playbackSource: TrailerPlaybackSource,
        val cachedAt: Instant,
        val expiresAt: Instant? = null
    )
}

/**
 * The item's own IMDb id (`tt` + digits), from a catalog id such as `tt0903747` or a Stremio
 * episode id `tt0903747:2:3`; null for TMDB or other ids.
 */
internal fun imdbIdOf(itemId: String): String? =
    itemId.substringBefore(':').substringBefore('/').trim()
        .takeIf { it.length > 2 && it.startsWith("tt") && it.drop(2).all(Char::isDigit) }

internal fun normalizeTmdbTrailerLanguage(language: String?): String {
    val normalized = language
        ?.trim()
        ?.replace('_', '-')
        ?.takeIf { it.isNotBlank() }
        ?: return TMDB_TRAILER_FALLBACK_LANGUAGE

    val formatted = if (normalized.contains('-')) {
        val parts = normalized.split("-", limit = 2)
        val locale = parts[0].lowercase()
        val region = parts.getOrNull(1)?.uppercase()?.takeIf { it.isNotBlank() }
        if (region != null) "$locale-$region" else locale
    } else {
        normalized.lowercase()
    }

    if (formatted == "en") return TMDB_TRAILER_FALLBACK_LANGUAGE

    // Map codes unsupported by TMDB to their closest equivalent
    return when (formatted) {
        "es-419" -> "es-MX"
        else -> formatted
    }
}

internal fun normalizeTmdbMediaType(type: String?): String? {
    return when (type?.lowercase()) {
        "movie", "film" -> "movie"
        "tv", "series", "show", "tvshow" -> "tv"
        else -> null
    }
}

/**
 * Ranks candidates for the user's chosen TMDB trailer language first (e.g. "en-US",
 * "fr-FR"), falling back to English as a safety net when nothing matches that
 * language, and only then to whatever else is available.
 */
internal fun rankTmdbVideoCandidates(
    results: List<TmdbVideoResult>,
    preferredLanguageCode: String = TMDB_TRAILER_FALLBACK_LANGUAGE
): List<TmdbVideoResult> {
    val preferredLanguage = preferredLanguageCode.substringBefore('-').lowercase()

    fun languageRank(iso6391: String?): Int {
        val lang = iso6391?.trim()?.lowercase()
        return when {
            lang == preferredLanguage -> 0
            lang == "en" -> 1
            else -> 2
        }
    }

    return results
        .asSequence()
        .filter { (it.site ?: "").equals("YouTube", ignoreCase = true) }
        .filter { !it.key.isNullOrBlank() }
        .filter {
            val normalizedType = it.type?.trim()?.lowercase()
            normalizedType == "trailer" || normalizedType == "teaser"
        }
        .distinctBy { it.key }
        .sortedWith(
            compareBy<TmdbVideoResult> { videoTypePriority(it.type) }
                .thenBy { languageRank(it.iso6391) }
                .thenBy { if (it.official == true) 0 else 1 }
                .thenByDescending { it.size ?: 0 }
                .thenByDescending { parsePublishedAtEpoch(it.publishedAt) }
        )
        .toList()
}

private fun videoTypePriority(type: String?): Int {
    return when (type?.trim()?.lowercase()) {
        "trailer" -> 0
        "teaser" -> 1
        else -> 2
    }
}

private fun parsePublishedAtEpoch(value: String?): Long {
    if (value.isNullOrBlank()) return Long.MIN_VALUE
    return runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(Long.MIN_VALUE)
}
