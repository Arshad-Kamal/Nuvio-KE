package com.nuvio.tv.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingAccountStore
import com.nuvio.tv.data.mdblist.MdbListRatingSource
import com.nuvio.tv.data.mdblist.MdbListRatings
import com.nuvio.tv.data.mdblist.MdbListRatingsPrefs
import com.nuvio.tv.data.mdblist.MdbListScores
import com.nuvio.tv.data.mdblist.mdbListConnected
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The detail page's MDBList ratings (Spec 105 S4, D1/D3/D7; mockup v5 C1): which sources the line shows, and one title's
 * scores (the app-wide cache, else one call with the profile's key).
 */
class DetailMdbListRatings @Inject constructor(
    private val ratings: MdbListRatings,
    private val prefs: MdbListRatingsPrefs,
    private val accounts: TrackingAccountStore,
    private val profileManager: ProfileManager
) {
    /** The line's sources in order; empty while ratings are off, no source is on, or the profile has no working key. */
    fun sources(): Flow<List<MdbListRatingSource>> = combine(accounts.mdbListConnected(), prefs.active) { connected, settings ->
        if (settings.onWith(connected)) settings.shown else emptyList()
    }.distinctUntilChanged()

    /** Throws what the session throws (paused, signed out, unreachable); null = no scores for this title. */
    suspend fun scores(itemId: String, itemType: String): MdbListScores? =
        ratings.scores(profileManager.activeProfileId.value, itemId, itemType)
}

/**
 * The page's line: [sources] (empty = off), [titleKey] the page's (id, type), [loaded] once MDBList answered for it, and
 * [scores] (null = not loaded, failed, or none for this title). A failure leaves [loaded] false, so a sources change
 * asks again; an answer with no scores does not.
 */
data class DetailMdbListRatingsUi(
    val sources: List<MdbListRatingSource> = emptyList(),
    val titleKey: Pair<String, String>? = null,
    val loaded: Boolean = false,
    val scores: MdbListScores? = null
) {
    /** C1: the line's height is kept while ratings are on, so its arrival never moves the buttons. */
    val reserved: Boolean get() = sources.isNotEmpty()

    /** What the line shows, in order: only sources with a score (a missing one is left out, never a dash). */
    val shown: List<Pair<MdbListRatingSource, Double>>
        get() = scores?.let { s -> sources.mapNotNull { source -> s.values[source]?.let { source to it } } }.orEmpty()

    /** New sources for [titleKey]: the same title keeps what it loaded; another starts without. */
    fun withSources(sources: List<MdbListRatingSource>, titleKey: Pair<String, String>?): DetailMdbListRatingsUi =
        if (titleKey == this.titleKey) copy(sources = sources) else DetailMdbListRatingsUi(sources, titleKey)

    /** MDBList answered for [titleKey]; dropped when the page moved on to another title meanwhile. */
    fun withScores(titleKey: Pair<String, String>, scores: MdbListScores?): DetailMdbListRatingsUi =
        if (titleKey == this.titleKey) copy(loaded = true, scores = scores) else this
}

/**
 * C1's score text: IMDb, Letterboxd and MyAnimeList as 0.0; TMDB out of 10 (MDBList sends it out of 100; a fractional
 * value under 10 is already out of 10, upstream's reading); Rotten Tomatoes, Popcornmeter and Trakt as percentages;
 * Metacritic a whole number.
 */
internal fun formatMdbListScore(source: MdbListRatingSource, value: Double): String = when (source) {
    MdbListRatingSource.IMDB, MdbListRatingSource.LETTERBOXD, MdbListRatingSource.MAL ->
        String.format(Locale.US, "%.1f", value)
    MdbListRatingSource.TMDB ->
        String.format(Locale.US, "%.1f", if (value < 10.0 && value % 1.0 != 0.0) value else value / 10.0)
    MdbListRatingSource.TOMATOES, MdbListRatingSource.POPCORN, MdbListRatingSource.TRAKT -> "${value.roundToInt()}%"
    MdbListRatingSource.METACRITIC -> value.roundToInt().toString()
}

/**
 * C1 (`.s105rt`): coloured label + score per source, [ITEM_GAP] apart, one line up to [LINE_MAX_WIDTH] (wider than the
 * info column, as C1 draws it: the default six fit); items that would overflow are left off from the end (never cut
 * mid-score). Composed while the line is reserved: with no [items] it keeps the line's height empty.
 */
@Composable
internal fun MdbListRatingsLine(items: List<Pair<MdbListRatingSource, Double>>, modifier: Modifier = Modifier) {
    val valueStyle = ratingsValueStyle()
    val labelStyle = ratingsLabelStyle()
    Box(modifier = modifier.fillMaxWidth().height(mdbListRatingsLineHeight()), contentAlignment = Alignment.CenterStart) {
        if (items.isEmpty()) return@Box
        Layout(
            modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true).widthIn(max = LINE_MAX_WIDTH),
            content = {
                items.forEach { (source, value) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LABEL_GAP)) {
                        Text(text = source.lineLabel, style = labelStyle, color = source.lineColor(), maxLines = 1)
                        Text(
                            text = formatMdbListScore(source, value),
                            style = valueStyle,
                            color = NuvioPrimitives.white,
                            maxLines = 1
                        )
                    }
                }
            }
        ) { measurables, constraints ->
            val gap = ITEM_GAP.roundToPx()
            val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
            var x = 0
            val fitted = placeables.takeWhile { placeable ->
                val start = if (x == 0) 0 else x + gap
                (start + placeable.width <= constraints.maxWidth).also { fits -> if (fits) x = start + placeable.width }
            }
            val height = fitted.maxOfOrNull { it.height } ?: 0
            layout(x, height) {
                var left = 0
                fitted.forEach { placeable ->
                    placeable.placeRelative(left, (height - placeable.height) / 2)
                    left += placeable.width + gap
                }
            }
        }
    }
}

/** The reserved line's height (DetailSkeleton draws its bar in it). */
@Composable
internal fun mdbListRatingsLineHeight(): Dp = with(LocalDensity.current) { ratingsValueStyle().lineHeight.toDp() }

// `.s105rt`: values 600 14/18, labels 700 12/16 +0.2; 16 px between items, 6 px label to value.
@Composable
private fun ratingsValueStyle(): TextStyle =
    NuvioTheme.textStyles.meta.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp)

@Composable
private fun ratingsLabelStyle(): TextStyle =
    NuvioTheme.textStyles.meta.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp)

/** D27 (sandbox `spec-105-mdblist` v6 F1-F4): the description's lines while the line is reserved (else 3), so a text
 *  title's column still ends above `detail.bandFirstFrameTop`. DetailSkeleton draws the same count. */
internal const val RATINGS_DESCRIPTION_MAX_LINES = 2

private val ITEM_GAP = 16.dp

/** C1's widest line, its source line's (x 48 to ~663 on 960): the default six sources (~564) fit. */
private val LINE_MAX_WIDTH = 616.dp
private val LABEL_GAP = 6.dp

/** `.s105rt` label colours (IMDb's is the app's own IMDb yellow). */
private fun MdbListRatingSource.lineColor(): Color = when (this) {
    MdbListRatingSource.IMDB -> NuvioPrimitives.imdb
    MdbListRatingSource.TOMATOES -> Color(0xFFFA6E4F)
    MdbListRatingSource.POPCORN -> Color(0xFFF7B544)
    MdbListRatingSource.METACRITIC -> Color(0xFF7BC96F)
    MdbListRatingSource.LETTERBOXD -> Color(0xFF40BCF4)
    MdbListRatingSource.TMDB -> Color(0xFF01B4E4)
    MdbListRatingSource.TRAKT -> Color(0xFFED4F6A)
    MdbListRatingSource.MAL -> Color(0xFF7E9BEA)
}
