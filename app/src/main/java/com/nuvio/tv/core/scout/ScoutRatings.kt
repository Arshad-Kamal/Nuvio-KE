package com.nuvio.tv.core.scout

import kotlinx.serialization.Serializable

/** The detail Rate choices (Spec 100 D3), weakest first as the control shows them. */
@Serializable
enum class ScoutRating(val weight: Double) {
    // Weights follow Nuvio Arc's ScoutPicksLogic (ysosrs main 5575b0be0, GPL-3.0).
    NOT_FOR_ME(-3.0),
    LIKE(3.0),
    LOVE(6.0)
}

/**
 * One title the profile rated on detail. Every id known when it was saved (D6): [contentId] (the detail page's
 * id), the meta's [imdbId] and the [tmdbId] resolved then, so history kept under any of them matches exactly
 * ([keys]) with no lookup at build time. [name] names the reason when the title has no history.
 */
@Serializable
data class ScoutRatingEntry(
    val contentId: String,
    val tv: Boolean,
    val name: String,
    val rating: ScoutRating,
    val ratedAt: Long,
    val imdbId: String? = null,
    val tmdbId: Int? = null
) {
    /** This title's keys: its page id's, plus the stored IMDb and TMDB ids'. */
    val keys: Set<String>
        get() = scoutContentKeys(contentId, tv) + scoutContentKeys(imdbId.orEmpty(), tv) +
            listOfNotNull(tmdbId?.let { scoutKey(it, tv) })
}

/**
 * What a content id says without a request: itself (per type: `tmdb:1` names a film and a show), an IMDb key
 * for a `tt` id and a TMDB key ([scoutKey]) for a `tmdb:` / `movie:` / `series:` / numeric one (parsed as
 * TmdbService.ensureTmdbId parses them, episode parts dropped). Two ids are one title when their keys meet.
 */
fun scoutContentKeys(contentId: String, tv: Boolean): Set<String> {
    val id = contentId.trim()
    if (id.isEmpty()) return emptySet()
    val base = scoutBaseId(id)
    return setOfNotNull(
        (if (tv) "tv|" else "movie|") + id,
        base.takeIf { it.startsWith("tt") }?.let { "imdb:$it" },
        base.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()?.let { scoutKey(it, tv) }
    )
}

/** [contentId] without a `tmdb:` / `movie:` / `series:` prefix or an episode / path part. */
internal fun scoutBaseId(contentId: String): String =
    contentId.removePrefix("tmdb:").removePrefix("movie:").removePrefix("series:")
        .substringBefore(':').substringBefore('/').trim()

/** The rating whose keys meet [keys], or null; one title never holds two ratings ([scoutRatingsWith]). */
fun List<ScoutRatingEntry>.ratingFor(keys: Set<String>): ScoutRatingEntry? =
    if (keys.isEmpty()) null else firstOrNull { entry -> entry.keys.any { it in keys } }

/**
 * [current] with the title of [keys] rated [entry], or unrated when [entry] is null: every stored rating meeting
 * [keys] or [entry]'s keys goes first, so a title holds one rating, the newest. [entry] keeps an IMDb or TMDB id
 * only a replaced rating knew (rated from a tt page, then again from a tmdb: page with no IMDb id).
 */
fun scoutRatingsWith(current: List<ScoutRatingEntry>, keys: Set<String>, entry: ScoutRatingEntry?): List<ScoutRatingEntry> {
    val all = keys + entry?.keys.orEmpty()
    val (replaced, kept) = current.partition { stored -> stored.keys.any { it in all } }
    val merged = entry?.let { new ->
        val newest = replaced.sortedByDescending { it.ratedAt }
        // A TMDB id only of the same type: an IMDb key meets across types, TMDB numbers do not.
        val sameType = newest.filter { it.tv == new.tv }
        new.copy(
            imdbId = new.imdbId ?: newest.firstNotNullOfOrNull { it.imdbId },
            tmdbId = new.tmdbId ?: sameType.firstNotNullOfOrNull { it.tmdbId }
        )
    }
    return kept + listOfNotNull(merged)
}
