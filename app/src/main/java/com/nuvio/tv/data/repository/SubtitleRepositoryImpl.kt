package com.nuvio.tv.data.repository

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.network.safeApiCall
import com.nuvio.tv.data.mapper.toAddonSubtitles
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.SubtitleRepository
import com.nuvio.tv.core.health.AddonHealthStore
import com.nuvio.tv.core.health.HealthOutcome
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import com.nuvio.tv.core.util.encodeUrlArg
import com.nuvio.tv.core.util.safeHost

private const val TAG = "SubtitleRepository"

class SubtitleRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val api: AddonApi,
    private val addonRepository: AddonRepository,
    private val healthStore: AddonHealthStore
) : SubtitleRepository {
    companion object {
        private const val PER_ADDON_TIMEOUT_MS = 20_000L
    }

    override suspend fun getSubtitles(
        type: String,
        id: String,
        videoId: String?,
        imdbId: String?,
        season: Int?,
        episode: Int?,
        videoHash: String?,
        videoSize: Long?,
        filename: String?,
        onProgress: ((completed: Int, total: Int, addonName: String?) -> Unit)?,
        onSubtitlesEmitted: ((List<Subtitle>) -> Unit)?
    ): List<Subtitle> = withContext(Dispatchers.IO) {
        val requestType = canonicalSubtitleType(type)
        val startedAtMs = System.currentTimeMillis()
        Log.d(TAG, "Fetching subtitles for type=$requestType, id=$id, videoId=$videoId, imdbId=$imdbId")
        
        // Get installed addons (waiting out the cold-start manifest load)
        val addons = awaitInstalledAddons(addonRepository.getInstalledAddons())

        val subtitleRequests = addons.mapNotNull {
            it.subtitleRequestFor(requestType, id, videoId, imdbId, season, episode)
        }
        
        Log.d(TAG, "Found ${subtitleRequests.size} subtitle addons: ${subtitleRequests.map { "${it.addon.name}(${it.type}/${it.id})" }}")

        if (subtitleRequests.isEmpty()) {
            return@withContext emptyList()
        }

        val total = subtitleRequests.size
        val completedCount = AtomicInteger(0)
        onProgress?.invoke(0, total, null)

        val accumulatedSubtitles = java.util.Collections.synchronizedList(mutableListOf<Subtitle>())

        // Fetch subtitles from all addons in parallel and stream results immediately
        val result = supervisorScope {
            subtitleRequests.map { request ->
                val addon = request.addon
                async {
                    val addonStartMs = System.currentTimeMillis()
                    val subtitles = try {
                        withTimeoutOrNull(PER_ADDON_TIMEOUT_MS) {
                            fetchSubtitlesFromAddon(addon, request.type, request.id, videoHash, videoSize, filename)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Exception fetching subtitles from ${addon.name}", e)
                        emptyList()
                    }
                    onProgress?.invoke(completedCount.incrementAndGet(), total, addon.displayName)
                    if (!subtitles.isNullOrEmpty()) {
                        accumulatedSubtitles.addAll(subtitles)
                        if (onSubtitlesEmitted != null) {
                            withContext(Dispatchers.Main.immediate) {
                                // Snapshot here, not at add time: Main runs deliveries one
                                // at a time, so a later delivery never carries fewer
                                // entries than an earlier one (a snapshot taken on IO could
                                // arrive after a larger one and overwrite it).
                                val snapshot = synchronized(accumulatedSubtitles) { accumulatedSubtitles.toList() }
                                onSubtitlesEmitted.invoke(snapshot)
                            }
                        }
                        Log.d(
                            TAG,
                            "Subtitle fetch done for addon=${addon.name} count=${subtitles.size} in ${System.currentTimeMillis() - addonStartMs}ms"
                        )
                        subtitles
                    } else {
                        if (subtitles == null) {
                            Log.w(
                                TAG,
                                "Subtitle fetch timed out for addon=${addon.name} after ${PER_ADDON_TIMEOUT_MS}ms"
                            )
                        }
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }
        Log.d(
            TAG,
            "Subtitle fetch completed total=${result.size} fromAddons=${subtitleRequests.size} in ${System.currentTimeMillis() - startedAtMs}ms"
        )
        result
    }

    /**
     * Routes this addon to the type and id its subtitles resource accepts (exact
     * type first, then the anime.* fallbacks), or null when none does.
     * [requestType] is already canonical, and so is every candidate derived from it.
     */
    private fun Addon.subtitleRequestFor(
        requestType: String,
        id: String,
        videoId: String?,
        imdbId: String?,
        season: Int?,
        episode: Int?
    ): SubtitleRequest? =
        addonRequestTypeCandidates(requestType).firstNotNullOfOrNull { candidateType ->
            resources.firstNotNullOfOrNull resource@{ resource ->
                if (!isSubtitleResource(resource.name) || !supportsType(resource, candidateType)) {
                    return@resource null
                }
                // Resource idPrefixes first, then the addon's top-level idPrefixes
                val prefixes = resource.idPrefixes?.takeIf { it.isNotEmpty() }
                    ?: idPrefixes.takeIf { it.isNotEmpty() }
                resolveSubtitleRequestId(
                    prefixes = prefixes,
                    id = id,
                    videoId = videoId,
                    imdbId = imdbId,
                    season = season,
                    episode = episode
                )?.let { requestId -> SubtitleRequest(this, candidateType, requestId) }
            }
        }

    private fun canonicalSubtitleType(type: String): String {
        return if (type.equals("tv", ignoreCase = true)) "series" else type.lowercase()
    }
    
    /** [type] must already be canonical (see [canonicalSubtitleType]). */
    private fun supportsType(resource: com.nuvio.tv.domain.model.AddonResource, type: String): Boolean {
        // Normalizing "tv" and "series" to be equivalent; no declared types = any type
        if (resource.types.isEmpty()) return true
        return resource.types.any { resType -> canonicalSubtitleType(resType) == type }
    }

    private fun isSubtitleResource(name: String): Boolean {
        return name.equals("subtitles", ignoreCase = true) ||
            name.equals("subtitle", ignoreCase = true)
    }
    
    private suspend fun fetchSubtitlesFromAddon(
        addon: Addon,
        type: String,
        requestId: String,
        videoHash: String?,
        videoSize: Long?,
        filename: String?
    ): List<Subtitle> {
        val normalizedType = canonicalSubtitleType(type)
        val encodedType = encodeUrlArg(normalizedType)
        val encodedActualId = encodeUrlArg(requestId)

        // Build the subtitle URL with optional extra parameters
        val (basePath, baseQuery) = splitAddonBaseUrl(addon.baseUrl)
        val extraParams = buildExtraParams(videoHash, videoSize, filename)
        val subtitleUrl = if (extraParams.isNotEmpty()) {
            "$basePath/subtitles/$encodedType/$encodedActualId/$extraParams.json$baseQuery"
        } else {
            "$basePath/subtitles/$encodedType/$encodedActualId.json$baseQuery"
        }
        
        Log.d(TAG, "Fetching subtitles from ${addon.name} type=$normalizedType id=$requestId host=${subtitleUrl.safeHost()}")
        
        val healthT0 = android.os.SystemClock.elapsedRealtime()
        return try {
            when (val result = safeApiCall(context) { api.getSubtitles(subtitleUrl) }) {
                is NetworkResult.Success -> {
                    healthStore.recordAddonOutcome(addon.baseUrl, HealthOutcome.SUCCESS, android.os.SystemClock.elapsedRealtime() - healthT0)
                    val subtitles = result.data.subtitles.toAddonSubtitles(addon.displayName, addon.logo)

                    Log.d(TAG, "Got ${subtitles.size} subtitles from ${addon.name}")
                    subtitles
                }
                is NetworkResult.Error -> {
                    healthStore.recordAddonOutcome(addon.baseUrl, HealthOutcome.FAILURE, android.os.SystemClock.elapsedRealtime() - healthT0)
                    Log.e(TAG, "Failed to fetch subtitles from ${addon.name}: code=${result.code} message=${result.message}")
                    emptyList()
                }
                NetworkResult.Loading -> emptyList()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Review 2026-08-29-110531: cancellation must not write a FAILURE
            // health sample (one scope cancel would mark every subtitle addon
            // failed) — rethrow, matching the sibling catch at the fan-out.
            throw e
        } catch (e: Exception) {
            healthStore.recordAddonOutcome(addon.baseUrl, HealthOutcome.FAILURE, android.os.SystemClock.elapsedRealtime() - healthT0)
            Log.e(TAG, "Exception fetching subtitles from ${addon.name}", e)
            emptyList()
        }
    }
    
    private fun buildExtraParams(
        videoHash: String?,
        videoSize: Long?,
        filename: String?
    ): String {
        val params = mutableListOf<String>()
        
        videoHash?.let { params.add("videoHash=${encodeUrlArg(it)}") }
        videoSize?.let { params.add("videoSize=$it") }
        filename?.let {
            params.add("filename=${encodeUrlArg(it)}")
        }
        
        return if (params.isNotEmpty()) {
            params.joinToString("&")
        } else {
            ""
        }
    }

}

/** One subtitle addon routed to the type and id it is requested with. */
private data class SubtitleRequest(
    val addon: Addon,
    val type: String,
    val id: String
)

/**
 * Picks the id to request from one subtitle addon, or null when no candidate
 * matches its [prefixes] (null = the addon accepts any id).
 *
 * The default request id is the playing [videoId] (falling back to the content
 * [id]), sent whenever the content id or the video id matches. Content keyed
 * `tmdb:`/`mal:`/`kitsu:` never matches a `tt`-only addon, so the [imdbId] is
 * tried last; for an episode (season and episode both known) it carries them in
 * Stremio video-id form, `tt123:1:2`.
 */
internal fun resolveSubtitleRequestId(
    prefixes: List<String>?,
    id: String,
    videoId: String?,
    imdbId: String?,
    season: Int?,
    episode: Int?
): String? {
    fun accepts(candidate: String): Boolean =
        prefixes == null || prefixes.any { prefix -> candidate.startsWith(prefix) }

    val playingId = videoId?.takeIf { it.isNotBlank() }
    if (accepts(id)) return playingId ?: id
    if (playingId != null && accepts(playingId)) return playingId
    val imdb = imdbId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val imdbRequestId = if (season != null && episode != null) "$imdb:$season:$episode" else imdb
    return imdbRequestId.takeIf { accepts(it) }
}
