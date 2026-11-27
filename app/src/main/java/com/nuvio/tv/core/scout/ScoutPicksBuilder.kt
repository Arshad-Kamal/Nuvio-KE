package com.nuvio.tv.core.scout

import com.nuvio.tv.core.tmdb.TmdbApiKeySource
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.util.getOrNullUnlessCancelled
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbRecommendationResult
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the Picked for you list (Spec 100) from one profile's history and detail ratings: one TMDB
 * recommendations page per seed (at most [SCOUT_MAX_POSITIVE_SEEDS] + [SCOUT_MAX_NEGATIVE_SEEDS]) plus an
 * IMDb-to-TMDB lookup for each seed TmdbService has not cached and no rating stored the TMDB id of. No key: no request, empty list. A seed whose
 * lookup or page fails is skipped.
 */
@Singleton
class ScoutPicksBuilder @Inject constructor(
    private val tmdbApi: TmdbApi,
    private val tmdbService: TmdbService,
    private val apiKeys: TmdbApiKeySource
) {
    suspend fun build(
        watched: List<WatchedItem>,
        progress: List<WatchProgress>,
        ratings: List<ScoutRatingEntry>,
        language: String,
        now: Long
    ): List<ScoutPick> {
        val seeds = scoutSeeds(watched, progress, now, ratings)
        // Only minuses (a first Not for me): nothing to ask more of, so no request.
        if (seeds.none { it.weight > 0 }) return emptyList()
        val key = apiKeys.key()
        if (key.isBlank()) return emptyList()

        val ratedKeys = scoutRatedKeys(ratings)
        val ids = coroutineScope {
            seeds.map { seed ->
                async {
                    // A rating's stored TMDB id (D6; every rating has one, D7), so only history is looked up here.
                    (seed.tmdbId ?: runCatching { tmdbService.ensureTmdbId(seed.contentId, seed.tv.mediaType()) }
                        .getOrNullUnlessCancelled()?.toIntOrNull())?.let { seed to it }
                }
            }.awaitAll().filterNotNull()
        }.let { found ->
            // A history id the rating did not store (tt1 history of a title rated from a tmdb: page with no IMDb
            // id): once both resolve to one TMDB id, the history seed goes and the rating stands.
            val rated = ratedKeys + found.filter { (seed, _) -> seed.rated }.map { (seed, id) -> scoutKey(id, seed.tv) }
            found.filterNot { (seed, id) -> !seed.rated && scoutKey(id, seed.tv) in rated }
                // Two history ids for one title (tt and tmdb:) ask for its page once and count it once.
                .distinctBy { (seed, id) -> scoutKey(id, seed.tv) }
        }
        val resolved = coroutineScope {
            ids.map { (seed, id) ->
                async {
                    runCatching { recommendations(id, seed.tv, key, language) }
                        .getOrNullUnlessCancelled()?.let { seed to it }
                }
            }.awaitAll().filterNotNull()
        }

        val local = scoutLocalExclusions(watched, progress, tmdbService::cachedTmdbId)
        val exclusions = local.copy(keys = local.keys + ratedKeys + ids.map { (seed, id) -> scoutKey(id, seed.tv) })
        return scoutRank(resolved, exclusions)
    }

    /** Null on a failed request (a missing body, a 401 without a key included). */
    private suspend fun recommendations(id: Int, tv: Boolean, key: String, language: String): List<TmdbRecommendationResult>? {
        val response = if (tv) tmdbApi.getTvRecommendations(id, key, language) else tmdbApi.getMovieRecommendations(id, key, language)
        return response.body()?.results?.filter { it.id > 0 }
    }

    private fun Boolean.mediaType(): String = if (this) "tv" else "movie"
}
