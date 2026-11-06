package com.nuvio.tv.core.tmdb

import android.util.Log
import com.nuvio.tv.core.util.getOrStartShared
import com.nuvio.tv.data.remote.api.TmdbApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TmdbService"

/**
 * Service to handle TMDB ID conversions and lookups.
 * Provides caching to avoid redundant API calls.
 */
@Singleton
class TmdbService(
    private val tmdbApi: TmdbApi,
    private val ioDispatcher: CoroutineDispatcher,
    private val apiKeys: TmdbApiKeySource
) {
    @Inject
    constructor(tmdbApi: TmdbApi, apiKeys: TmdbApiKeySource) : this(tmdbApi, Dispatchers.IO, apiKeys)

    // Shared lookups run here, not in the first caller's coroutine, so a cancelled
    // caller never cancels the request other callers are awaiting.
    private val serviceScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    // Cache: IMDB ID -> TMDB ID (keyed by "$imdbId:$mediaType")
    private val imdbToTmdbCache = ConcurrentHashMap<String, Int>()
    
    // Cache: TMDB ID -> IMDB ID (keyed by "$tmdbId:$mediaType")
    private val tmdbToImdbCache = ConcurrentHashMap<String, String>()

    private val imdbToTmdbInFlight = ConcurrentHashMap<String, Deferred<Int?>>()
    private val tmdbToImdbInFlight = ConcurrentHashMap<String, Deferred<String?>>()
    
    /**
     * Convert an IMDB ID to a TMDB ID.
     * 
     * @param imdbId The IMDB ID (e.g., "tt0133093")
     * @param mediaType The media type ("movie" or "series"/"tv")
     * @return The TMDB ID, or null if not found
     */
    suspend fun imdbToTmdb(imdbId: String, mediaType: String): Int? {
        // Validate IMDB ID format
        if (!imdbId.startsWith("tt")) {
            Log.w(TAG, "Invalid IMDB ID format: $imdbId")
            return null
        }
        
        val normalizedType = normalizeMediaType(mediaType)
        val cacheKey = "$imdbId:$normalizedType"

        // Check cache first
        imdbToTmdbCache[cacheKey]?.let { cached ->
            Log.d(TAG, "Cache hit: IMDB $imdbId ($normalizedType) -> TMDB $cached")
            return cached
        }

        return imdbToTmdbInFlight.getOrStartShared(serviceScope, cacheKey) {
            lookupTmdbId(imdbId, mediaType, normalizedType, cacheKey)
        }.await()
    }

    private suspend fun lookupTmdbId(
        imdbId: String,
        mediaType: String,
        normalizedType: String,
        cacheKey: String
    ): Int? = try {
        Log.d(TAG, "Looking up TMDB ID for IMDB: $imdbId (type: $mediaType)")

        val response = tmdbApi.findByExternalId(
            externalId = imdbId,
            apiKey = apiKeys.key(),
            externalSource = "imdb_id"
        )

        if (!response.isSuccessful) {
            Log.e(TAG, "TMDB API error: ${response.code()} - ${response.message()}")
            null
        } else {
            val body = response.body()
            // Determine which results to use based on media type
            val result = when (normalizedType) {
                "movie" -> body?.movieResults?.firstOrNull()
                "tv", "series" -> body?.tvResults?.firstOrNull()
                else -> body?.movieResults?.firstOrNull() ?: body?.tvResults?.firstOrNull()
            }
            if (result != null) {
                Log.d(TAG, "Found TMDB ID: ${result.id} for IMDB: $imdbId")
                // Cache both directions
                imdbToTmdbCache[cacheKey] = result.id
                tmdbToImdbCache["${result.id}:$normalizedType"] = imdbId
                result.id
            } else {
                if (body != null) Log.w(TAG, "No TMDB result found for IMDB: $imdbId")
                null
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Error looking up TMDB ID for $imdbId: ${e.message}", e)
        null
    }
    
    /**
     * Convert a TMDB ID to an IMDB ID.
     * 
     * @param tmdbId The TMDB ID
     * @param mediaType The media type ("movie" or "series"/"tv")
     * @return The IMDB ID, or null if not found
     */
    suspend fun tmdbToImdb(tmdbId: Int, mediaType: String): String? {
        val normalizedType = normalizeMediaType(mediaType)
        val cacheKey = "$tmdbId:$normalizedType"

        // Check cache first
        tmdbToImdbCache[cacheKey]?.let { cached ->
            Log.d(TAG, "Cache hit: TMDB $tmdbId ($normalizedType) -> IMDB $cached")
            return cached
        }

        return tmdbToImdbInFlight.getOrStartShared(serviceScope, cacheKey) {
            lookupImdbId(tmdbId, mediaType, normalizedType, cacheKey)
        }.await()
    }

    private suspend fun lookupImdbId(
        tmdbId: Int,
        mediaType: String,
        normalizedType: String,
        cacheKey: String
    ): String? = try {
        Log.d(TAG, "Looking up IMDB ID for TMDB: $tmdbId (type: $mediaType)")

        val response = when (normalizedType) {
            "movie" -> tmdbApi.getMovieExternalIds(tmdbId, apiKeys.key())
            "tv", "series" -> tmdbApi.getTvExternalIds(tmdbId, apiKeys.key())
            else -> tmdbApi.getMovieExternalIds(tmdbId, apiKeys.key())
        }

        if (!response.isSuccessful) {
            Log.e(TAG, "TMDB API error: ${response.code()} - ${response.message()}")
            null
        } else {
            val body = response.body()
            val imdbId = body?.imdbId
            if (imdbId != null) {
                Log.d(TAG, "Found IMDB ID: $imdbId for TMDB: $tmdbId")
                // Cache both directions
                tmdbToImdbCache[cacheKey] = imdbId
                imdbToTmdbCache["$imdbId:$normalizedType"] = tmdbId
                imdbId
            } else {
                if (body != null) Log.w(TAG, "No IMDB ID found for TMDB: $tmdbId")
                null
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Error looking up IMDB ID for $tmdbId: ${e.message}", e)
        null
    }
    
    /**
     * Get a TMDB ID from a video ID string.
     * Handles both IMDB IDs (tt...) and TMDB IDs.
     * 
     * @param videoId The video ID (can be IMDB or TMDB format)
     * @param mediaType The media type
     * @return The TMDB ID as a string, or null if conversion failed
     */
    suspend fun ensureTmdbId(videoId: String, mediaType: String): String? {
        // Check if it's already a TMDB ID (numeric or prefixed)
        val cleanId = videoId
            .removePrefix("tmdb:")
            .removePrefix("movie:")
            .removePrefix("series:")

        // Stremio-style series ids can look like: tt1234567:season:episode
        // TMDB lookup needs the base external id only.
        val idPart = cleanId
            .substringBefore(':')
            .substringBefore('/')
            .trim()
        
        // If it's an IMDB ID, convert it
        if (idPart.startsWith("tt")) {
            val tmdbId = imdbToTmdb(idPart, normalizeMediaType(mediaType))
            return tmdbId?.toString()
        }
        
        // If it looks like a numeric ID, assume it's already a TMDB ID
        if (idPart.all { it.isDigit() }) {
            return idPart
        }
        
        // Unknown format
        Log.w(TAG, "Unknown video ID format: $videoId")
        return null
    }
    
    /**
     * Normalize media type to consistent format
     */
    private fun normalizeMediaType(mediaType: String): String {
        return when (mediaType.lowercase()) {
            "series", "tv", "show", "tvshow" -> "tv"
            "movie", "film" -> "movie"
            else -> mediaType.lowercase()
        }
    }
    
    /** Returns the cached TMDB ID for an IMDB ID without making any network call. */
    fun cachedTmdbId(imdbId: String): Int? =
        imdbToTmdbCache["$imdbId:movie"] ?: imdbToTmdbCache["$imdbId:tv"]

    suspend fun apiKey(): String = apiKeys.key()

    /**
     * Fetches backdrop and poster URLs from TMDB for the given IMDB ID.
     * Returns null if the IMDB ID doesn't start with "tt" or if TMDB has no data.
     * Results are NOT cached here — callers should persist what they need.
     */
    suspend fun fetchImdbImages(imdbId: String, mediaType: String): TmdbImages? =
        withContext(ioDispatcher) {
            if (!imdbId.startsWith("tt")) return@withContext null
            val tmdbId = imdbToTmdb(imdbId, mediaType) ?: return@withContext null
            try {
                val isMovie = normalizeMediaType(mediaType) == "movie"
                val response = if (isMovie)
                    tmdbApi.getMovieDetails(tmdbId, apiKeys.key())
                else
                    tmdbApi.getTvDetails(tmdbId, apiKeys.key())
                val body = response.body() ?: return@withContext null
                TmdbImages(
                    backdropUrl = tmdbImageUrl(body.backdropPath, TmdbImageSizes.backdrop),
                    posterUrl = tmdbImageUrl(body.posterPath, "w500")
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
}

data class TmdbImages(val backdropUrl: String?, val posterUrl: String?)
