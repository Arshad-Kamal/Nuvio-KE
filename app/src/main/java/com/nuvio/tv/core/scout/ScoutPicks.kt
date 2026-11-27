package com.nuvio.tv.core.scout

import com.nuvio.tv.data.remote.api.TmdbRecommendationResult
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import kotlinx.serialization.Serializable
import kotlin.math.max

// Rank share and quality floor follow Nuvio Arc's ScoutPicksLogic (ysosrs main 5575b0be0, GPL-3.0), Spec 100 S1.
private const val RANK_DEPTH = 40.0
private const val MIN_SHARE = 0.05
private const val MIN_VOTES = 50
private const val MIN_RATING = 6.0
private const val RATING_NUDGE = 0.3

/** The row's size (Spec 100 S0: up to 20 picks). */
const val SCOUT_MAX_PICKS = 20

/**
 * One title for the Picked for you row. [reason] is the name of the watched title that suggested it most
 * ("Because you watched [reason]"). Image fields are TMDB paths; the row picks the size.
 */
@Serializable
data class ScoutPick(
    val tmdbId: Int,
    val tv: Boolean,
    val name: String,
    val reason: String,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val overview: String? = null,
    val year: String? = null,
    val rating: Double? = null
) {
    val key: String get() = scoutKey(tmdbId, tv)
}

/** "tv:<id>" or "movie:<id>": TMDB ids are only unique per type. */
fun scoutKey(tmdbId: Int, tv: Boolean): String = (if (tv) "tv:" else "movie:") + tmdbId

/** What the profile already watched or started: never picked. Titles catch the ids no lookup resolved. */
data class ScoutExclusions(val keys: Set<String>, val titles: Set<String>) {
    fun blocks(tmdbId: Int, tv: Boolean, name: String): Boolean =
        scoutKey(tmdbId, tv) in keys || scoutTitleKey(name, tv) in titles
}

/**
 * What [watched] and [progress] exclude without a request: TMDB ids the content ids carry (`tmdb:`, `movie:`,
 * `series:` prefixes or a bare number, parsed as TmdbService.ensureTmdbId does) or [cachedTmdbId] knows for an
 * IMDb id, plus every non-blank title.
 */
fun scoutLocalExclusions(
    watched: List<WatchedItem>,
    progress: List<WatchProgress>,
    cachedTmdbId: (String) -> Int?
): ScoutExclusions {
    val typed = watched.map { Triple(it.contentId, it.title, it.contentType) } +
        progress.map { Triple(it.contentId, it.name, it.contentType) }
    return ScoutExclusions(
        keys = typed.mapNotNullTo(HashSet()) { (id, _, type) -> localTmdbId(id, cachedTmdbId)?.let { scoutKey(it, type.isTv()) } },
        titles = typed.filter { (_, name, _) -> name.isNotBlank() }
            .mapTo(HashSet()) { (_, name, type) -> scoutTitleKey(name, type.isTv()) }
    )
}

private fun localTmdbId(contentId: String, cachedTmdbId: (String) -> Int?): Int? {
    val base = scoutBaseId(contentId)
    return when {
        base.startsWith("tt") -> cachedTmdbId(base)
        base.isNotEmpty() && base.all(Char::isDigit) -> base.toIntOrNull()
        else -> null
    }
}

/** Every key of every rated title ([ScoutRatingEntry.keys]): a pick whose [ScoutPick.key] is here is rated (D6). */
fun scoutRatedKeys(ratings: List<ScoutRatingEntry>): Set<String> = ratings.flatMapTo(HashSet()) { it.keys }

/** Lower-cased title with only letters and digits kept, per type, for [ScoutExclusions.titles]. */
fun scoutTitleKey(name: String, tv: Boolean): String =
    (if (tv) "tv:" else "movie:") + name.lowercase().filter { it.isLetterOrDigit() }

/**
 * Ranks every seed's TMDB recommendations ([results]: each seed with its list, best first, and whether that
 * list is of shows). Each place adds the seed's weight times a share that falls with the place; a pick keeps
 * the name of the positive seed that gave it the biggest share as its reason. Excluded, low-voted, low-rated
 * and net-negative titles go; neighbours with the same reason are split up where another pick can go between.
 */
fun scoutRank(
    results: List<Pair<ScoutSeed, List<TmdbRecommendationResult>>>,
    exclusions: ScoutExclusions,
    limit: Int = SCOUT_MAX_PICKS
): List<ScoutPick> {
    val scores = LinkedHashMap<String, Double>()
    val best = HashMap<String, Double>()
    val candidates = HashMap<String, Candidate>()
    results.forEach { (seed, list) ->
        list.forEachIndexed { rank, rec ->
            val tv = rec.mediaType?.trim()?.lowercase()?.let { it == "tv" } ?: seed.tv
            val name = rec.displayName() ?: return@forEachIndexed
            val key = scoutKey(rec.id, tv)
            val share = seed.weight * max(MIN_SHARE, 1.0 - rank / RANK_DEPTH)
            scores.merge(key, share, Double::plus)
            if (seed.weight > 0 && share > (best[key] ?: 0.0)) {
                best[key] = share
                candidates[key] = rec.toCandidate(tv, name, seed.name)
            }
        }
    }
    val ranked = scores.entries
        .mapNotNull { (key, score) -> candidates[key]?.takeIf { score > 0 }?.let { it to score } }
        .filter { (candidate, _) ->
            val pick = candidate.pick
            !exclusions.blocks(pick.tmdbId, pick.tv, pick.name) &&
                (candidate.votes ?: 0) >= MIN_VOTES && (pick.rating ?: MIN_RATING) >= MIN_RATING
        }
        .sortedByDescending { (candidate, score) -> score + RATING_NUDGE * (candidate.pick.rating ?: 0.0) / 10.0 }
        .map { (candidate, _) -> candidate.pick }
    return spreadReasons(ranked, limit)
}

/** Best first, but the next pick takes a different reason than the one before it when any is left. */
private fun spreadReasons(ranked: List<ScoutPick>, limit: Int): List<ScoutPick> {
    val left = ranked.toMutableList()
    val out = ArrayList<ScoutPick>(minOf(limit, ranked.size))
    while (out.size < limit && left.isNotEmpty()) {
        val previous = out.lastOrNull()?.reason
        val index = left.indexOfFirst { it.reason != previous }.takeIf { it >= 0 } ?: 0
        out += left.removeAt(index)
    }
    return out
}

/** A ranked candidate with the vote count the floor needs (not kept on [ScoutPick]). */
private data class Candidate(val pick: ScoutPick, val votes: Int?)

private fun TmdbRecommendationResult.displayName(): String? =
    listOf(title, name, originalTitle, originalName).firstOrNull { !it.isNullOrBlank() }

private fun TmdbRecommendationResult.toCandidate(tv: Boolean, name: String, reason: String) = Candidate(
    ScoutPick(
        tmdbId = id,
        tv = tv,
        name = name,
        reason = reason,
        posterPath = posterPath?.takeIf { it.isNotBlank() },
        backdropPath = backdropPath?.takeIf { it.isNotBlank() },
        overview = overview?.takeIf { it.isNotBlank() },
        year = (if (tv) firstAirDate else releaseDate)?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) },
        rating = voteAverage
    ),
    voteCount
)
