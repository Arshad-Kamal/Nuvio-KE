package com.nuvio.tv.ui.screens.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.util.parseEpisodeReleaseLocalDate
import com.nuvio.tv.domain.model.isEpisodeWatched
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.MetaTrailer
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.ui.components.formatContinueWatchingProgressLabel
import com.nuvio.tv.ui.components.marquee.MarqueeEyebrow
import com.nuvio.tv.ui.components.marquee.MarqueeMetaRow
import com.nuvio.tv.ui.components.marquee.MarqueeProgress
import com.nuvio.tv.ui.components.marquee.formatRatingScore
import com.nuvio.tv.ui.components.marquee.marqueeStaggered
import com.nuvio.tv.ui.components.marquee.rememberMarqueeEnter
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.formatHeroRuntime
import com.nuvio.tv.ui.util.localizeEpisodeTitle
import java.util.Locale

// Detail scrolled state (Marquee spec §Screens "Detail as decided at the U2 slice", sandbox
// u2-detail-marquee stages 3-8): a context line at the top and, bottom-anchored above the
// focused band, a block that follows the focused item like Home's hero.

private const val SEPARATOR = " \u00B7 "
private const val BLOCK_CHILDREN = 5

/**
 * Band geometry on this screen (Home's frame rule: real window pixels through the current
 * density). Two bands: the episodes band (series) and the section band (tab strip + one
 * section). [firstFrameHeight] is the info column item's minimum height (the first band starts
 * below it). A focused band's list item is scrolled to its top ([episodesTopPx] /
 * [sectionsTopPx]), i.e. its height above the screen bottom; [scrollTravelPx] is the scroll from
 * the first frame to the first band's anchor, which paces the scrolled-state fade.
 */
@Immutable
internal class DetailBandGeometry(
    val firstFrameHeight: Dp,
    val episodesBandHeight: Dp,
    val sectionsBandHeight: Dp,
    val episodesTopPx: Int,
    val sectionsTopPx: Int,
    val scrollTravelPx: Float
)

/**
 * [hasEpisodes]: series with episodes; [hasChips]: the season chip row shows; [hasSections]:
 * at least one section tab. With neither band the first frame is [noBandFirstFrameHeight].
 */
@Composable
internal fun rememberDetailBandGeometry(
    hasEpisodes: Boolean,
    hasChips: Boolean,
    hasSections: Boolean,
    noBandFirstFrameHeight: Dp
): DetailBandGeometry {
    val detail = NuvioTheme.marquee.detail
    val density = LocalDensity.current
    val screenHeight = with(density) { LocalContext.current.resources.displayMetrics.heightPixels.toDp() }
    return remember(hasEpisodes, hasChips, hasSections, noBandFirstFrameHeight, screenHeight, density, detail) {
        val firstFrame = if (hasEpisodes || hasSections) detail.bandFirstFrameTop else noBandFirstFrameHeight
        val chips = if (hasChips) detail.chipHeight + detail.chipsToCards else 0.dp
        val episodesHeight = detail.bandBottomInset + detail.cardHeight + chips
        val sectionsHeight = detail.bandBottomInset + detail.sectionContentHeight +
            detail.sectionTabsToContent + detail.sectionTabsHeight
        val episodesTop = screenHeight - episodesHeight
        val sectionsTop = screenHeight - sectionsHeight
        val firstBandTop = if (hasEpisodes) episodesTop else sectionsTop
        with(density) {
            DetailBandGeometry(
                firstFrameHeight = firstFrame,
                episodesBandHeight = episodesHeight,
                sectionsBandHeight = sectionsHeight,
                episodesTopPx = episodesTop.roundToPx(),
                sectionsTopPx = sectionsTop.roundToPx(),
                scrollTravelPx = (firstFrame - firstBandTop).toPx().coerceAtLeast(1f)
            )
        }
    }
}

/** The focused item of a band; [inSections] tells the section band from the episodes band. */
internal sealed interface DetailBandFocus {
    val inSections: Boolean

    data class Season(val season: Int) : DetailBandFocus {
        override val inSections: Boolean get() = false
    }

    /** An episode card, or (in the section band) a ratings grid cell. */
    data class Episode(val video: Video, override val inSections: Boolean = false) : DetailBandFocus

    /** A section tab, a cast member or a studio: the block shows the title's own info. */
    data object Title : DetailBandFocus {
        override val inSections: Boolean get() = true
    }

    data class Related(val item: MetaPreview, val eyebrow: String) : DetailBandFocus {
        override val inSections: Boolean get() = true
    }

    data class Trailer(val trailer: MetaTrailer) : DetailBandFocus {
        override val inSections: Boolean get() = true
    }
}

@Immutable
internal data class DetailBlockContent(
    val key: Any,
    val inSections: Boolean,
    val eyebrow: String,
    val title: String,
    val metaParts: List<String>,
    val imdb: String?,
    val progressFraction: Float?,
    val progressLabel: String?,
    val description: String?,
    /** TMDB score, shown only without [imdb] (MarqueeMetaRow). */
    val tmdb: String? = null
)

/**
 * The block for [focus] (read here, so focus moves recompose only this leaf): eyebrow, 32/38
 * title, meta, progress, 2-line overview, fading + rising in on change, `home.heroAboveRow`
 * above the focused band. It fades out (`fast`) when no band has focus. [eyebrow] and
 * [titleMetaParts] are the page's own (title block); [showImdbRating] gates title and related
 * ratings, [episodeRatings] is already filtered.
 */
@Composable
internal fun DetailBandBlock(
    focus: () -> DetailBandFocus?,
    meta: Meta,
    eyebrow: String,
    titleMetaParts: List<String>,
    showImdbRating: Boolean,
    geometry: DetailBandGeometry,
    episodeProgressMap: Map<Pair<Int, Int>, WatchProgress>,
    watchedEpisodes: Set<Pair<Int, Int>>,
    episodeRatings: Map<Pair<Int, Int>, Double>,
    accent: () -> Color,
    modifier: Modifier = Modifier
) {
    val content = when (val current = focus()) {
        is DetailBandFocus.Episode -> episodeBlockContent(current.video, current.inSections, episodeProgressMap, episodeRatings)
        is DetailBandFocus.Season -> seasonBlockContent(meta, current.season, episodeProgressMap, watchedEpisodes)
        DetailBandFocus.Title -> titleBlockContent(meta, eyebrow, titleMetaParts, showImdbRating)
        is DetailBandFocus.Related -> relatedBlockContent(current.item, current.eyebrow, showImdbRating)
        is DetailBandFocus.Trailer -> trailerBlockContent(current.trailer)
        null -> null
    }
    // The last content stays on screen while the block fades out. A plain holder: only this
    // composition reads it, so a snapshot write here would just recompose the block twice.
    val last = remember { arrayOfNulls<DetailBlockContent>(1) }
    if (content != null) last[0] = content
    val shown = content ?: last[0]
    val bandHeight = if (shown?.inSections == true) geometry.sectionsBandHeight else geometry.episodesBandHeight
    AnimatedVisibility(
        visible = content != null,
        enter = EnterTransition.None,
        exit = fadeOut(tween(NuvioTheme.motion.durations.fast)),
        modifier = modifier.padding(bottom = bandHeight + NuvioTheme.marquee.home.heroAboveRow)
    ) {
        shown?.let { DetailBlock(it, accent) }
    }
}

@Composable
private fun DetailBlock(content: DetailBlockContent, accent: () -> Color) {
    val marquee = NuvioTheme.marquee
    val detail = marquee.detail
    val enter = rememberMarqueeEnter(BLOCK_CHILDREN, content.key)
    Column(
        modifier = Modifier.width(detail.blockWidth),
        verticalArrangement = Arrangement.spacedBy(detail.blockGap)
    ) {
        MarqueeEyebrow(text = content.eyebrow, accent = accent, modifier = Modifier.marqueeStaggered(enter, 0))
        Text(
            text = content.title,
            style = NuvioTheme.textStyles.blockTitle,
            color = NuvioPrimitives.white,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.marqueeStaggered(enter, 1)
        )
        MarqueeMetaRow(
            parts = content.metaParts,
            imdb = content.imdb,
            tmdb = content.tmdb,
            modifier = Modifier.marqueeStaggered(enter, 2)
        )
        if (content.progressFraction != null || content.progressLabel != null) {
            MarqueeProgress(
                fraction = content.progressFraction,
                label = content.progressLabel,
                accent = accent,
                width = marquee.home.heroProgressWidth,
                modifier = Modifier.marqueeStaggered(enter, 3)
            )
        }
        content.description?.let {
            Text(
                text = it,
                style = NuvioTheme.textStyles.bodyDetail,
                color = marquee.onImageBody,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = detail.blockDescriptionWidth)
                    .marqueeStaggered(enter, 4)
            )
        }
    }
}

/**
 * "Episode 3" (episodes band) or "Season 1 · Episode 3" (ratings grid) · title · runtime · date ·
 * IMDb · progress + time left · overview.
 */
@Composable
private fun episodeBlockContent(
    episode: Video,
    inSections: Boolean,
    episodeProgressMap: Map<Pair<Int, Int>, WatchProgress>,
    episodeRatings: Map<Pair<Int, Int>, Double>
): DetailBlockContent {
    val context = LocalContext.current
    val key = episode.season?.let { s -> episode.episode?.let { e -> s to e } }
    val progress = key?.let { episodeProgressMap[it] }?.takeIf { it.isInProgress() }
    val episodeWord = stringResource(R.string.episodes_episode)
    val season = episode.season?.takeIf { inSections }?.let { seasonLabel(it) }
    val resume = stringResource(R.string.cw_resume)
    val percent = stringResource(R.string.cw_percent_watched)
    val hoursMinLeft = stringResource(R.string.cw_hours_min_left)
    val minLeft = stringResource(R.string.cw_min_left)
    val rating = key?.let { episodeRatings[it] }
    return remember(episode, inSections, season, progress, rating, episodeWord, resume, percent, hoursMinLeft, minLeft, context) {
        val episodeLabel = episode.episode?.let { "$episodeWord $it" } ?: episodeWord
        DetailBlockContent(
            key = episode.id,
            inSections = inSections,
            eyebrow = if (season != null) season + SEPARATOR + episodeLabel else episodeLabel,
            title = episode.title.localizeEpisodeTitle(context),
            metaParts = listOfNotNull(
                formatHeroRuntime(episode.runtime?.toString()),
                episode.released?.let(::formatReleaseDate)?.takeIf { it.isNotBlank() }
            ),
            imdb = formatRatingScore(rating),
            progressFraction = progress?.progressPercentage,
            progressLabel = progress?.let { formatContinueWatchingProgressLabel(it, resume, percent, hoursMinLeft, minLeft) },
            description = episode.overview?.trim()?.takeIf { it.isNotBlank() }
        )
    }
}

/** "Season 2 · 6 episodes" · series title · first air year · "4 of 6 watched". */
@Composable
private fun seasonBlockContent(
    meta: Meta,
    season: Int,
    episodeProgressMap: Map<Pair<Int, Int>, WatchProgress>,
    watchedEpisodes: Set<Pair<Int, Int>>
): DetailBlockContent {
    val episodes = remember(meta.videos, season) {
        meta.videos.filter { it.season == season && it.episode != null }.distinctBy { it.id }
    }
    val watched = remember(episodes, episodeProgressMap, watchedEpisodes) {
        episodes.count { video ->
            video.episode?.let { isEpisodeWatched(episodeProgressMap, watchedEpisodes, season, it) } == true
        }
    }
    val label = seasonLabel(season)
    val count = pluralStringResource(R.plurals.detail_band_episode_count, episodes.size, episodes.size)
    val watchedLabel = stringResource(R.string.detail_band_watched_of, watched, episodes.size)
    return remember(meta.name, season, episodes, label, count, watchedLabel) {
        val year = episodes.mapNotNull { it.released?.let(::parseEpisodeReleaseLocalDate)?.year }.minOrNull()
        DetailBlockContent(
            key = "season:$season",
            inSections = false,
            eyebrow = if (episodes.isEmpty()) label else label + SEPARATOR + count,
            title = meta.name,
            metaParts = listOfNotNull(year?.toString(), watchedLabel.takeIf { episodes.isNotEmpty() }),
            imdb = null,
            progressFraction = null,
            progressLabel = null,
            description = null
        )
    }
}

/** The title's own info (section tabs, cast, studios): page eyebrow · title · meta line. */
@Composable
private fun titleBlockContent(
    meta: Meta,
    eyebrow: String,
    metaParts: List<String>,
    showImdbRating: Boolean
): DetailBlockContent = remember(meta.id, meta.name, meta.imdbRating, meta.tmdbRating, eyebrow, metaParts, showImdbRating) {
    DetailBlockContent(
        key = "title",
        inSections = true,
        eyebrow = eyebrow,
        title = meta.name,
        metaParts = metaParts,
        imdb = formatRatingScore(meta.imdbRating?.takeIf { showImdbRating }),
        tmdb = formatRatingScore(meta.tmdbRating?.takeIf { showImdbRating }),
        progressFraction = null,
        progressLabel = null,
        description = null
    )
}

/** A related or collection title: section eyebrow · its title · year · ≤2 genres · runtime · IMDb · overview. */
@Composable
private fun relatedBlockContent(item: MetaPreview, eyebrow: String, showImdbRating: Boolean): DetailBlockContent {
    val context = LocalContext.current
    return remember(item, eyebrow, showImdbRating, context) {
        DetailBlockContent(
            key = "related:${item.id}",
            inSections = true,
            eyebrow = eyebrow,
            title = item.name,
            metaParts = listOfNotNull(
                item.releaseInfo?.trim()?.takeIf { it.isNotBlank() },
                metaGenres(item.genres, context),
                formatHeroRuntime(item.runtime)
            ),
            imdb = formatRatingScore(item.imdbRating?.takeIf { showImdbRating }),
            tmdb = formatRatingScore(item.tmdbRating?.takeIf { showImdbRating }),
            progressFraction = null,
            progressLabel = null,
            description = item.description?.trim()?.takeIf { it.isNotBlank() }
        )
    }
}

/** "Trailers" · trailer name · type · language. */
@Composable
private fun trailerBlockContent(trailer: MetaTrailer): DetailBlockContent {
    val eyebrow = stringResource(R.string.detail_section_trailers)
    val fallbackTitle = stringResource(R.string.detail_tab_trailer)
    return remember(trailer, eyebrow, fallbackTitle) {
        DetailBlockContent(
            key = "trailer:${trailer.ytId}",
            inSections = true,
            eyebrow = eyebrow,
            title = trailerTitle(trailer, fallbackTitle),
            metaParts = listOfNotNull(
                trailer.type?.takeIf { it.isNotBlank() },
                trailer.lang?.takeIf { it.isNotBlank() }?.uppercase(Locale.ROOT)
            ),
            imdb = null,
            progressFraction = null,
            progressLabel = null,
            description = null
        )
    }
}

/** A trailer's display name: its name, else its type, else [fallback]. */
internal fun trailerTitle(trailer: MetaTrailer, fallback: String): String =
    trailer.name?.takeIf { it.isNotBlank() } ?: trailer.type?.takeIf { it.isNotBlank() } ?: fallback

/**
 * Scrolled-state context line: the title in white, then " · Season 2" (when [season] is set and
 * [showSeason] says so: the episodes band context) in `onImage2`, `tab` style. [alpha] is read
 * at draw time (it follows the page scroll).
 */
@Composable
internal fun DetailContextLine(
    title: String,
    season: Int?,
    showSeason: () -> Boolean,
    alpha: () -> Float,
    modifier: Modifier = Modifier
) {
    val marquee = NuvioTheme.marquee
    val seasonText = season?.takeIf { showSeason() }?.let { seasonLabel(it) }
    val text = remember(title, seasonText, marquee.onImage2) {
        buildAnnotatedString {
            append(title)
            if (seasonText != null) {
                withStyle(SpanStyle(color = marquee.onImage2)) { append(SEPARATOR + seasonText) }
            }
        }
    }
    Text(
        text = text,
        style = NuvioTheme.textStyles.tab,
        color = NuvioPrimitives.white,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .widthIn(max = marquee.detail.infoWidth)
            .graphicsLayer { this.alpha = alpha() }
    )
}
