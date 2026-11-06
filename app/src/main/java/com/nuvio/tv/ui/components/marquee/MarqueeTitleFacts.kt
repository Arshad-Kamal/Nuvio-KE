package com.nuvio.tv.ui.components.marquee

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.formatAddonTypeLabel

// Title facts shared by Detail's info column and the Home / Search hero (sandbox
// data-additions-marquee stage 1): the type eyebrow and the credit line.

internal const val FACT_SEPARATOR = " \u00B7 "

/**
 * Type · season count (series) · status · "Part of" [collectionName] (Detail, T6), e.g. "Series ·
 * 2 seasons · Ended". [isSeries] is the caller's own series test (Detail: the one that shows the
 * season tabs); [seasonCount] null or 0, an unknown [status] and a blank collection drop out.
 */
@Composable
internal fun titleTypeEyebrow(
    apiType: String?,
    isSeries: Boolean,
    seasonCount: Int?,
    status: String?,
    collectionName: String? = null
): String {
    // Not a series: "movie" localized, anything else as the addon wrote it (a "tv" title without
    // episodes reads "Tv", as Detail always has).
    val type = when {
        isSeries -> stringResource(R.string.type_series)
        apiType.equals("movie", ignoreCase = true) -> stringResource(R.string.type_movie)
        else -> formatAddonTypeLabel(apiType.orEmpty())
    }
    val seasons = if (isSeries && seasonCount != null && seasonCount > 0) {
        pluralStringResource(R.plurals.detail_season_count, seasonCount, seasonCount)
    } else {
        null
    }
    val statusLabel = titleStatusLabel(status, isSeries)
    val collection = collectionName?.takeIf { it.isNotBlank() }
        ?.let { stringResource(R.string.detail_collection_eyebrow, it) }
    return listOfNotNull(type.takeIf { it.isNotBlank() }, seasons, statusLabel, collection).joinToString(FACT_SEPARATOR)
}

/** Known TMDB/Stremio statuses localized; anything else passes through as given. */
@Composable
private fun titleStatusLabel(status: String?, isSeries: Boolean): String? {
    val raw = status?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val res = when (raw.lowercase()) {
        "ended" -> if (isSeries) R.string.series_status_ended else R.string.movie_status_ended
        "continuing", "returning series" -> if (isSeries) R.string.series_status_continuing else R.string.movie_status_continuing
        "current" -> if (isSeries) R.string.series_status_current else R.string.movie_status_current
        "cancelled", "canceled" -> if (isSeries) R.string.series_status_cancelled else R.string.movie_status_cancelled
        "released" -> if (isSeries) R.string.series_status_released else R.string.movie_status_released
        "planned" -> if (isSeries) R.string.series_status_planned else R.string.movie_status_planned
        "rumored" -> if (isSeries) R.string.series_status_rumored else R.string.movie_status_rumored
        "in production" -> if (isSeries) R.string.series_status_in_production else R.string.movie_status_in_production
        "post production" -> if (isSeries) R.string.series_status_post_production else R.string.movie_status_post_production
        else -> null
    }
    return if (res != null) stringResource(res) else raw
}

/** True when [MarqueeCreditLine] has a name to show for these credits. */
internal fun hasCredit(directors: List<String>, writers: List<String>): Boolean =
    directors.any { it.isNotBlank() } || writers.any { it.isNotBlank() }

/**
 * The top award of an addon's awards text ("Won 2 Oscars. 164 wins & 165 nominations total."):
 * its first sentence; null when blank.
 */
internal fun topAward(awards: String?): String? =
    awards?.substringBefore(". ")?.trim()?.trimEnd('.')?.takeIf { it.isNotBlank() }

/**
 * "Created by" (series) / "Directed by" (movies) from [directors], else "Written by" [writers],
 * names in `onImage2` after an `onImage3` prefix, then " · " [award] in `onImage2` (Detail, T1),
 * `meta` style, one line. [reserveHeight] keeps
 * the line's height while empty (Detail: credits often land with the enriched meta, and the
 * actions below must not jump); without it an empty credit draws nothing.
 */
@Composable
internal fun MarqueeCreditLine(
    directors: List<String>,
    writers: List<String>,
    isSeries: Boolean,
    modifier: Modifier = Modifier,
    reserveHeight: Boolean = false,
    award: String? = null
) {
    val marquee = NuvioTheme.marquee
    val style = NuvioTheme.textStyles.meta
    val directorNames = directors.filter { it.isNotBlank() }
    val writerNames = writers.filter { it.isNotBlank() }
    val credit = when {
        directorNames.isNotEmpty() ->
            (if (isSeries) R.string.detail_credit_created_by else R.string.detail_credit_directed_by) to directorNames
        writerNames.isNotEmpty() -> R.string.detail_credit_written_by to writerNames
        else -> null
    }
    if (credit == null && award == null && !reserveHeight) return
    val lineHeight = with(LocalDensity.current) { style.lineHeight.toDp() }
    Box(modifier = modifier.height(lineHeight), contentAlignment = Alignment.CenterStart) {
        if (credit != null || award != null) {
            val prefix = credit?.let { stringResource(it.first) }
            val names = credit?.second
            val text = remember(prefix, names, award, marquee.onImage2) {
                buildAnnotatedString {
                    if (prefix != null && names != null) {
                        append(prefix)
                        append(' ')
                        withStyle(SpanStyle(color = marquee.onImage2)) { append(names.joinToString(", ")) }
                    }
                    if (award != null) {
                        if (prefix != null) append(FACT_SEPARATOR)
                        withStyle(SpanStyle(color = marquee.onImage2)) { append(award) }
                    }
                }
            }
            Text(
                text = text,
                style = style,
                color = marquee.onImage3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
