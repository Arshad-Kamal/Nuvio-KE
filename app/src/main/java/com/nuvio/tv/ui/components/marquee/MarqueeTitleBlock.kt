package com.nuvio.tv.ui.components.marquee

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.formatRuntimeMinutes
import com.nuvio.tv.ui.util.localizeEpisodeTitle
import com.nuvio.tv.ui.util.localizedContentType

private const val META_MAX_GENRES = 2
private val GENRE_SEPARATOR = Regex("[\u2022,]")
private const val EYEBROW_SEPARATOR = " \u00b7 "

/**
 * The Stream screen's title block (sandbox `u5-stream-screen-marquee` stages 1-2), shared with the
 * player's loading hand-off (`u6-player-panels-marquee` stages 12-14) so a source start keeps its
 * place: `stream.titleBlock*` wide and spaced, padded here to its anchor (`tvSafeHorizontal` in,
 * `titleBlockBottom` up); the caller aligns it bottom-start in a full-screen box. The eyebrow
 * in the ambient [accent] ("Movie", "Series · S1 E1", plus [eyebrowStatus] when given), the
 * logo or title, the episode name, the meta line (year, runtime, two genres, IMDb; [imdb] is
 * already formatted, null hides it; [tmdb] stands in under its own mark without one), then
 * [footer]: the plot on Stream, the hand-off status
 * while a source loads.
 */
@Composable
internal fun MarqueeTitleBlock(
    title: String,
    logo: String?,
    contentType: String?,
    season: Int?,
    episode: Int?,
    episodeName: String?,
    year: String?,
    accent: () -> Color,
    modifier: Modifier = Modifier,
    runtimeMinutes: Int? = null,
    genres: String? = null,
    imdb: String? = null,
    tmdb: String? = null,
    eyebrowStatus: String? = null,
    footer: @Composable ColumnScope.() -> Unit = {}
) {
    val marquee = NuvioTheme.marquee
    val home = marquee.home
    val context = LocalContext.current
    val episodeLabel = if (season != null && episode != null) {
        stringResource(R.string.stream_episode_label, season, episode)
    } else {
        null
    }
    val typeLabel = if (episodeLabel != null) stringResource(R.string.type_series) else localizedContentType(contentType)
    val eyebrow = titleBlockEyebrow(typeLabel, episodeLabel, eyebrowStatus)
    val metaParts = remember(year, runtimeMinutes, genres) { titleMetaParts(year, runtimeMinutes, genres) }
    val episodeTitle = remember(episodeName, context) {
        episodeName?.takeIf { it.isNotBlank() }?.localizeEpisodeTitle(context)
    }
    Column(
        modifier = modifier
            .padding(start = NuvioTheme.layout.tvSafeHorizontal, bottom = marquee.stream.titleBlockBottom)
            .width(marquee.stream.titleBlockWidth),
        verticalArrangement = Arrangement.spacedBy(marquee.stream.titleBlockGap)
    ) {
        if (eyebrow.isNotEmpty()) MarqueeEyebrow(text = eyebrow, accent = accent)
        MarqueeLogoTitle(
            title = title,
            logo = logo,
            logoHeight = home.heroLogoMaxHeight,
            logoMaxWidth = home.heroLogoMaxWidth,
            textStyle = NuvioTheme.textStyles.heroTitle
        )
        if (episodeTitle != null) {
            Text(
                text = episodeTitle,
                style = NuvioTheme.textStyles.episodeName,
                color = NuvioPrimitives.white,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        MarqueeMetaRow(parts = metaParts, imdb = imdb, tmdb = tmdb)
        footer()
    }
}

/** "Type · S1 E4 · Status": the blank or missing parts dropped (stage 12: "Series · S1 E4 · Starting"). */
internal fun titleBlockEyebrow(typeLabel: String, episodeLabel: String?, status: String?): String =
    listOfNotNull(typeLabel, episodeLabel, status)
        .filter { it.isNotBlank() }
        .joinToString(EYEBROW_SEPARATOR)

/**
 * The meta line's parts before IMDb, in the mockup's order: year, runtime ("1h 32m"), then up to
 * two genres, each its own part ([genres] arrives "A • B" or "A, B" from the route or the meta).
 */
internal fun titleMetaParts(year: String?, runtimeMinutes: Int?, genres: String?): List<String> = buildList {
    year?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
    runtimeMinutes?.let(::formatRuntimeMinutes)?.let(::add)
    genres?.split(GENRE_SEPARATOR)
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.take(META_MAX_GENRES)
        ?.let(::addAll)
}
