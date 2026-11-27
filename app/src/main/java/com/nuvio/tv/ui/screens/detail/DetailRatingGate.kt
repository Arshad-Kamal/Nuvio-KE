package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.core.profile.ActiveRatingLimit
import com.nuvio.tv.domain.model.ContentRatingCap
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.NonUsRatingBoards
import com.nuvio.tv.domain.model.isRatingAllowed

/**
 * Spec 64 D13: a title Detail does not open under [limit]. [rating] is the addon's US rating above
 * the limit, or null when it has none (absent, `NR`, or a TMDB fallback meta); a title blocked on
 * its non-US ratings also shows null, the unrated copy (Spec 102 D4).
 */
data class DetailRatingBlock(val title: String, val rating: String?, val limit: ActiveRatingLimit)

/**
 * The block for [meta] under [limit], or null when Detail may open it (always with no limit).
 * Decided on the addon's own ratings, [Meta.ageRating] and [Meta.nonUsRatings] before TMDB facts
 * are merged in (D13). A [fromTmdbFallback] meta's rating is TMDB's any-country pick, not a
 * US-board value, so it counts as unrated (fail closed, D1).
 */
internal fun detailRatingBlock(meta: Meta, limit: ActiveRatingLimit?, fromTmdbFallback: Boolean): DetailRatingBlock? {
    if (limit == null) return null
    val rating = meta.ageRating.takeUnless { fromTmdbFallback }
    val nonUsRatings = if (fromTmdbFallback) emptyMap() else meta.nonUsRatings
    if (isRatingAllowed(rating, limit.cap, nonUsRatings)) return null
    val usRating = ContentRatingCap.usRatingCode(rating) ?: NonUsRatingBoards.usCodeOf(nonUsRatings)
    return DetailRatingBlock(title = meta.name, rating = usRating, limit = limit)
}
