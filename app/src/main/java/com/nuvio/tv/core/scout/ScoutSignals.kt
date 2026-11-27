package com.nuvio.tv.core.scout

import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import kotlin.math.pow

// Weights and thresholds follow Nuvio Arc's ScoutPicksLogic (ysosrs main 5575b0be0, GPL-3.0), Spec 100 S1.
internal const val SCOUT_FINISHED = 1.0
internal const val SCOUT_REWATCHED = 4.0
internal const val SCOUT_WATCHING = 0.5
internal const val SCOUT_STOPPED = -1.0
private const val DECAY = 0.97
private const val STOPPED_AFTER_MS = 14L * 24 * 60 * 60 * 1000
private const val STOPPED_FILM_SHARE = 0.2f
private const val STOPPED_EPISODES = 2
// Marking a film watched stamps its progress row a moment after the watched item, completed; a rewatch is a
// row played again, not completed, at least this long after it was finished.
private const val REWATCH_GAP_MS = 60L * 60 * 1000

/** Positive seeds asked about per build; with [SCOUT_MAX_NEGATIVE_SEEDS] this bounds the TMDB calls (Spec 100). */
const val SCOUT_MAX_POSITIVE_SEEDS = 6
const val SCOUT_MAX_NEGATIVE_SEEDS = 2

/** Below this many positive seeds, titles still being watched fill in. */
private const val MIN_POSITIVE_SEEDS = 3

/**
 * A title the profile watched or rated that steers the picks: [weight] > 0 asks TMDB for more like it (and
 * names the reason), < 0 pushes its recommendations down. [rated]: the weight is the profile's own rating;
 * [tmdbId]: the TMDB id that rating stored (D6), so the build asks no lookup for it.
 */
data class ScoutSeed(
    val contentId: String,
    val tv: Boolean,
    val name: String,
    val weight: Double,
    val rated: Boolean = false,
    val tmdbId: Int? = null
)

/**
 * The seeds for one build, from the profile's [watched] items (finished films and episodes) and its [progress]
 * (started, resumed): rewatched > finished > still watching; a show dropped after an episode or two, or a film
 * left before a fifth, is a mild minus. Newer counts more. A detail rating ([ratings], Spec 100 D3) replaces
 * what the history says for its title (Love > rewatched > Like > finished; Not for me the strongest minus),
 * matched by exact id only ([ScoutRatingEntry.keys], D6), and a rated title with no history seeds too, at full
 * weight. At most [SCOUT_MAX_POSITIVE_SEEDS] positive, heaviest first, then at most [SCOUT_MAX_NEGATIVE_SEEDS]
 * negative, Not for me first.
 */
fun scoutSeeds(
    watched: List<WatchedItem>,
    progress: List<WatchProgress>,
    now: Long,
    ratings: List<ScoutRatingEntry> = emptyList()
): List<ScoutSeed> {
    val rated = ratings.filter { it.contentId.isNotBlank() }
    fun ratingOf(contentId: String, type: String) = rated.ratingFor(scoutContentKeys(contentId, type.isTv()))
    // A seed names the reason ("Because you watched X"): its name is the newest non-blank one among the
    // title's rows, and a title with none cannot seed. Untitled rows still count as evidence.
    val progressById = progress.filter { it.contentId.isNotBlank() }.groupBy { it.contentId }
    val matched = HashSet<ScoutRatingEntry>()
    val history = watched.filter { it.contentId.isNotBlank() }
        .groupBy { it.contentId }
        .mapNotNull { (id, items) ->
            val newest = items.maxBy { it.watchedAt }
            val rating = ratingOf(id, newest.contentType)
            val name = items.filter { it.title.isNotBlank() }.maxByOrNull { it.watchedAt }?.title
                ?: progressById[id].orEmpty().filter { it.name.isNotBlank() }.maxByOrNull { it.lastWatched }?.name
                ?: rating?.name?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            if (rating != null) matched += rating
            HistoryTitle(newest, items, name, rating)
        }
        .sortedByDescending { it.newest.watchedAt }
        .mapIndexed { index, (newest, items, name, rating) ->
            val tv = newest.contentType.isTv()
            val episodes = items.filter { it.episode != null }.distinctBy { it.season to it.episode }.size
            val rows = progressById[newest.contentId].orEmpty()
            val lastPlayed = rows.maxOfOrNull { it.lastWatched }
            val again = !tv && rows.any { !it.isCompleted() && it.lastWatched > newest.watchedAt + REWATCH_GAP_MS }
            // A show still being played is not dropped, however few episodes are finished.
            val lastTouched = maxOf(newest.watchedAt, lastPlayed ?: 0L)
            val weight = when {
                rating != null -> rating.rating.weight
                again -> SCOUT_REWATCHED
                tv && episodes in 1..STOPPED_EPISODES && now - lastTouched > STOPPED_AFTER_MS -> SCOUT_STOPPED
                else -> SCOUT_FINISHED
            }
            // A stored TMDB id is the rating's, resolved for the rating's type, so the seed takes that type with it.
            ScoutSeed(
                newest.contentId, rating?.takeIf { it.tmdbId != null }?.tv ?: tv, name, weight * DECAY.pow(index),
                rated = rating != null, tmdbId = rating?.tmdbId
            )
        }
    val known = history.mapTo(HashSet()) { it.contentId }
    // Rated without a finished episode or film: the rating alone, newest first; untitled cannot seed.
    val ratedOnly = rated.filter { it !in matched && it.name.isNotBlank() }
        .sortedByDescending { it.ratedAt }
        .map { ScoutSeed(it.contentId, it.tv, it.name, it.rating.weight, rated = true, tmdbId = it.tmdbId) }
    // A rating replaces the progress signal, even one too untitled to seed itself.
    val startedOnly = progressById.filterKeys { it !in known }
        .mapNotNull { (id, rows) ->
            val newest = rows.maxBy { it.lastWatched }
            if (ratingOf(id, newest.contentType) != null) return@mapNotNull null
            val name = rows.filter { it.name.isNotBlank() }.maxByOrNull { it.lastWatched }?.name ?: return@mapNotNull null
            Triple(newest, rows, name)
        }
        .sortedByDescending { (row, _, _) -> row.lastWatched }
    val (quit, started) = startedOnly.partition { (row, rows, _) -> row.isQuit(rows, now) }
    val stopped = quit.map { (row, _, name) -> ScoutSeed(row.contentId, row.contentType.isTv(), name, SCOUT_STOPPED) }
    val watching = started
        .mapIndexed { index, (row, _, name) ->
            ScoutSeed(row.contentId, row.contentType.isTv(), name, SCOUT_WATCHING * DECAY.pow(index))
        }

    val signals = history + ratedOnly
    val positive = signals.filter { it.weight > 0 }.sortedByDescending { it.weight }.take(SCOUT_MAX_POSITIVE_SEEDS)
    val fill = if (positive.size < MIN_POSITIVE_SEEDS) watching.take(SCOUT_MAX_POSITIVE_SEEDS - positive.size) else emptyList()
    // Not for me first (strongest first), then the history's own minuses and the quits in their S1 order.
    val notForMe = signals.filter { it.weight < 0 && it.rated }.sortedBy { it.weight }
    val negative = (notForMe + history.filter { it.weight < 0 && !it.rated } + stopped).take(SCOUT_MAX_NEGATIVE_SEEDS)
    return positive + fill + negative
}

/** One watched title's rows before weighting. */
private data class HistoryTitle(
    val newest: WatchedItem,
    val items: List<WatchedItem>,
    val name: String,
    val rating: ScoutRatingEntry?
)

/** Left and not touched for two weeks: a show after at most two episodes, a film before a fifth of it. */
private fun WatchProgress.isQuit(rows: List<WatchProgress>, now: Long): Boolean {
    if (now - lastWatched <= STOPPED_AFTER_MS) return false
    if (contentType.isTv()) return rows.distinctBy { it.season to it.episode }.size <= STOPPED_EPISODES
    val known = duration > 0 || progressPercent != null
    return known && progressPercentage < STOPPED_FILM_SHARE
}

internal fun String.isTv(): Boolean = trim().lowercase() in TV_TYPES

private val TV_TYPES = setOf("series", "tv", "show", "tvshow", "anime")
