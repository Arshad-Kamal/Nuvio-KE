package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.api.IntroDbApi
import com.nuvio.tv.data.remote.api.IntroDbSegment
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class SkipInterval(
    val startTime: Double, // seconds
    val endTime: Double,   // seconds
    val type: String,      // "intro", "recap", "outro"
    val provider: String   // "introdb"
)

@Singleton
class SkipIntroRepository @Inject constructor(
    private val introDbApi: IntroDbApi
) {
    private val cache = ConcurrentHashMap<String, List<SkipInterval>>()
    private val introDbConfigured = BuildConfig.INTRODB_API_URL.isNotEmpty()

    suspend fun getSkipIntervals(imdbId: String?, season: Int, episode: Int): List<SkipInterval> {
        if (imdbId == null) return emptyList()
        val cacheKey = "$imdbId:$season:$episode"
        cache[cacheKey]?.let { return it }
        // 2026-08-26 review: only authoritative results are cached. A transient failure
        // (network down, 5xx) must stay uncached or skip-intro stays poisoned all session.
        val result = if (introDbConfigured) fetchFromIntroDb(imdbId, season, episode) else emptyList()
        if (result != null) {
            cache[cacheKey] = result
        }
        return result ?: emptyList()
    }

    /** Null = fetch failed this time; caller must not cache. Empty list = confirmed no data. */
    private suspend fun fetchFromIntroDb(imdbId: String, season: Int, episode: Int): List<SkipInterval>? {
        return try {
            val response = introDbApi.getSegments(imdbId, season, episode)
            if (response.isSuccessful && response.body() != null) {
                val data = response.body()!!
                listOfNotNull(
                    data.intro.toSkipIntervalOrNull("intro"),
                    data.recap.toSkipIntervalOrNull("recap"),
                    data.outro.toSkipIntervalOrNull("outro")
                )
            } else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d("SkipIntro", "IntroDB: fetch failed for $imdbId S${season}E${episode}")
            null
        }
    }

    private fun IntroDbSegment?.toSkipIntervalOrNull(type: String): SkipInterval? {
        if (this == null) return null
        val start = startSec ?: startMs?.let { it / 1000.0 }
        val end = endSec ?: endMs?.let { it / 1000.0 }
        if (start == null || end == null || end <= start) return null
        return SkipInterval(startTime = start, endTime = end, type = type, provider = "introdb")
    }
}
