package com.nuvio.tv.ui.screens.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.ui.components.NuvioScrollDefaults
import com.nuvio.tv.ui.components.marquee.formatRatingScore
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme

private const val VISIBLE_SEASON_ROWS = 3
private const val NO_RATING = "\u2014"

private class SeasonRatingsRow(val season: Int, val byEpisode: Map<Int, Video>)

/**
 * Ratings (series, when episode ratings show): a seasons x episodes grid of
 * `ratingCellWidth`x`ratingCellHeight` cells in the as-built colour scale, an "E1.." header and
 * "S1.." labels. Three season rows show at a time (seasons lazy; the grid scrolls both ways as
 * focus moves).
 * Cells are views, not actions: focus feeds the scrolled-state block via [onEpisodeFocused].
 * Up from the first season row goes to [upFocusRequester]; entering the grid lands on the
 * last focused cell, else the first. Loading / error / no seasons show a one-line message.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun DetailRatingsGrid(
    episodes: List<Video>,
    ratings: Map<Pair<Int, Int>, Double>,
    isLoading: Boolean,
    error: String?,
    upFocusRequester: FocusRequester,
    onEpisodeFocused: (Video) -> Unit
) {
    val marquee = NuvioTheme.marquee
    val detail = marquee.detail
    val rows = remember(episodes) {
        episodes
            .filter { (it.season ?: 0) > 0 && it.episode != null } // never the specials season (S0)
            .groupBy { it.season!! }
            .toSortedMap()
            .map { (season, videos) -> SeasonRatingsRow(season, videos.associateBy { it.episode!! }) }
    }
    val columns = remember(rows) { rows.flatMap { it.byEpisode.keys }.distinct().sorted() }
    val message = when {
        isLoading -> stringResource(R.string.ratings_loading)
        error != null -> error
        rows.isEmpty() -> stringResource(R.string.ratings_unavailable)
        else -> null
    }
    if (message != null) {
        Text(
            text = message,
            style = NuvioTheme.textStyles.meta,
            color = marquee.onImage2,
            modifier = Modifier.padding(horizontal = NuvioTheme.layout.tvSafeHorizontal)
        )
        return
    }

    val firstCellRequester = remember { FocusRequester() }
    // Entry lands on the first season's first cell (its lowest episode, not the shared column 0).
    val firstCellEpisode = rows.first().byEpisode.keys.min()
    val gap = detail.ratingGap
    // The focused cell's ring (gap + ring, scaled with the cell) draws outside it; the viewport
    // pads for it above and below.
    val ringRoom = marquee.primaryButton.ringGap + marquee.ringWidth +
        detail.ratingCellHeight * ((marquee.cardScale - 1f) / 2f)
    val viewportHeight = detail.ratingCellHeight * VISIBLE_SEASON_ROWS + gap * (VISIBLE_SEASON_ROWS - 1) + ringRoom * 2
    val seasonListState = rememberLazyListState()
    // Minimal scroll inside the ring room (the list's content padding): the inherited spec centres
    // the cell at a fixed viewport fraction, asking past the list's ends, and the part the list
    // cannot scroll went to the page through nested scroll (F-10: page jumped to the top).
    val density = LocalDensity.current
    val seasonScrollSpec = remember(density, ringRoom) {
        NuvioScrollDefaults.minimalScrollSpec(marginPx = with(density) { ringRoom.roundToPx() })
    }
    // Taller than the section box by the ring room; it draws into the band's bottom inset.
    Box(
        modifier = Modifier
            .wrapContentHeight(align = Alignment.Top, unbounded = true)
            .focusRestorer(
                // W5.1: the first season row is lazy; only resolve it while composed.
                if (seasonListState.layoutInfo.visibleItemsInfo.any { it.index == 0 }) firstCellRequester else FocusRequester.Default
            )
            .focusGroup()
            .horizontalScroll(rememberScrollState())
    ) {
        Column(modifier = Modifier.padding(horizontal = NuvioTheme.layout.tvSafeHorizontal)) {
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                Spacer(modifier = Modifier.width(detail.ratingLabelWidth))
                columns.forEach { episode ->
                    Text(
                        text = stringResource(R.string.ratings_episode_label, episode),
                        style = NuvioTheme.textStyles.chip,
                        color = marquee.onImage3,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.width(detail.ratingCellWidth)
                    )
                }
            }
            Spacer(modifier = Modifier.height((gap - ringRoom).coerceAtLeast(0.dp)))
            // Lazy on the season axis: a long-running series composes only the rows in view.
            CompositionLocalProvider(LocalBringIntoViewSpec provides seasonScrollSpec) {
                LazyColumn(
                    state = seasonListState,
                    modifier = Modifier.height(viewportHeight),
                    contentPadding = PaddingValues(vertical = ringRoom),
                    verticalArrangement = Arrangement.spacedBy(gap)
                ) {
                    itemsIndexed(rows, key = { _, row -> row.season }) { rowIndex, row ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(gap),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.ratings_season_label, row.season),
                                style = NuvioTheme.textStyles.cardLabel,
                                color = marquee.onImage2,
                                maxLines = 1,
                                modifier = Modifier.width(detail.ratingLabelWidth)
                            )
                            columns.forEach { episodeNumber ->
                                val video = row.byEpisode[episodeNumber]
                                if (video == null) {
                                    Spacer(modifier = Modifier.size(detail.ratingCellWidth, detail.ratingCellHeight))
                                } else {
                                    RatingCell(
                                        rating = ratings[row.season to episodeNumber],
                                        modifier = Modifier
                                            .then(if (rowIndex == 0 && episodeNumber == firstCellEpisode) Modifier.focusRequester(firstCellRequester) else Modifier)
                                            .then(if (rowIndex == 0) Modifier.focusProperties { up = upFocusRequester } else Modifier),
                                        onFocused = { onEpisodeFocused(video) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One cell: rating on its scale colour (no rating: `ratingEmptyFill` + a dash); focus = scale + black gap + white ring. */
@Composable
private fun RatingCell(rating: Double?, modifier: Modifier, onFocused: () -> Unit) {
    val marquee = NuvioTheme.marquee
    val detail = marquee.detail
    var focused by remember { mutableStateOf(false) }
    val shape = remember(detail.ratingCellRadius) { RoundedCornerShape(detail.ratingCellRadius) }
    val text = formatRatingScore(rating)
    val fill = if (text != null && rating != null) ratingColor(rating) else detail.ratingEmptyFill
    val textColor = if (text != null && rating != null) ratingTextColor(rating) else marquee.onImage3
    Box(
        modifier = modifier
            .size(detail.ratingCellWidth, detail.ratingCellHeight)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .focusable()
            .graphicsLayer {
                val scale = if (focused) marquee.cardScale else 1f
                scaleX = scale
                scaleY = scale
            }
            .drawBehind {
                if (!focused) return@drawBehind
                val radius = detail.ratingCellRadius.toPx()
                val gapWidth = marquee.primaryButton.ringGap.toPx()
                val ringWidth = marquee.ringWidth.toPx()
                fun ring(color: Color, outset: Float, width: Float) {
                    val o = outset + width / 2
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(-o, -o),
                        size = Size(size.width + o * 2, size.height + o * 2),
                        cornerRadius = CornerRadius(radius + o),
                        style = Stroke(width)
                    )
                }
                ring(NuvioPrimitives.black, 0f, gapWidth)
                ring(marquee.ring, gapWidth, ringWidth)
            }
            .background(fill, shape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text ?: NO_RATING,
            style = NuvioTheme.textStyles.caption,
            fontWeight = FontWeight.Bold,
            color = textColor,
            maxLines = 1
        )
    }
}

/** As-built episode rating scale: 9+ / 8+ / 7.5+ / 7+ / 6+ / below. */
private fun ratingColor(value: Double): Color = when {
    value >= 9.0 -> Color(0xFF186A3B)
    value >= 8.0 -> Color(0xFF28B463)
    value >= 7.5 -> Color(0xFFF4D03F)
    value >= 7.0 -> Color(0xFFF39C12)
    value >= 6.0 -> Color(0xFFE74C3C)
    else -> Color(0xFF633974)
}

private fun ratingTextColor(value: Double): Color = when {
    value >= 7.0 && value < 8.0 -> Color(0xFF1D1D1F)
    else -> Color.White
}
