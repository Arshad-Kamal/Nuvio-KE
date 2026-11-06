package com.nuvio.tv.ui.screens.home

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.nuvio.tv.LocalContentFocusRequester
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.ContinueWatchingCardStyle
import com.nuvio.tv.ui.components.marquee.MarqueeMessage
import com.nuvio.tv.ui.components.marquee.marqueeSkeletonBar
import com.nuvio.tv.ui.components.marquee.marqueeSkeletonTextBar
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme

// Skeleton bar geometry from the approved mockup (sandbox u1-home-marquee v3, stage 7), dp.
private val BAR_RADIUS = 4.dp
private val TITLE_BAR_RADIUS = 8.dp
private val HERO_EYEBROW_BAR = DpSize(120.dp, 10.dp)
private val HERO_TITLE_BAR = DpSize(330.dp, 44.dp)
private val HERO_TITLE_BOX = 52.dp // the title bar sits centred in its 44 + 2 x 4 margin box
private val HERO_META_BAR = DpSize(240.dp, 12.dp)
private val HERO_DESC_BARS = listOf(DpSize(410.dp, 12.dp), DpSize(330.dp, 12.dp))
private val HERO_DESC_TOP = 6.dp // extra margin above the description lines
private val ROW_TITLE_BAR = DpSize(150.dp, 14.dp)
private val NEXT_ROW_TITLE_BAR = DpSize(130.dp, 14.dp)

// Compact (Search/Discover) geometry from the approved mockup (sandbox u4-search-discover-marquee
// v2, stages 4 and 13): hero bars top to bottom (eyebrow, title, meta, one description line)
// [COMPACT_HERO_GAP] apart, a shorter row title bar, no next row.
private val COMPACT_HERO_BARS = listOf(
    DpSize(90.dp, 11.dp),
    DpSize(280.dp, 34.dp),
    DpSize(240.dp, 13.dp),
    DpSize(400.dp, 13.dp)
)
private const val COMPACT_HERO_TITLE_INDEX = 1
private val COMPACT_HERO_GAP = 12.dp
private val COMPACT_ROW_TITLE_BAR = DpSize(130.dp, 14.dp)

/**
 * The skeleton band's card shape: the first row's, as far as it is known while loading.
 * Continue Watching leads when it is on and has (or may still get) items; otherwise the
 * catalog card shape decides.
 */
internal fun homeSkeletonLandscape(uiState: HomeUiState, continueWatchingPending: Boolean): Boolean {
    val continueWatchingFirst = uiState.continueWatchingEnabled &&
        (continueWatchingPending || uiState.continueWatchingItems.isNotEmpty())
    return if (continueWatchingFirst) {
        uiState.continueWatchingCardStyle != ContinueWatchingCardStyle.POSTER
    } else {
        uiState.modernLandscapePostersEnabled
    }
}

/**
 * Home loading state (Marquee spec §Screens "Home states", mockup stage 7): a static skeleton in
 * the final geometry so the first content frame does not jump. Black, `skeletonFill` bars, no
 * shimmer; one draw pass. [landscape] picks the band's card shape (the first row's, when known).
 * Positions mirror [MarqueeHomeContent]: card bottom at the band inset, row title above it,
 * hero bottom [com.nuvio.tv.ui.theme.NuvioMarqueeHomeTokens.heroAboveRow] above the title, the
 * next row's title peeking [com.nuvio.tv.ui.theme.NuvioMarqueeHomeTokens.rowGap] below.
 * [compact]: the Search/Discover variant (results hero, posters `search.gridGap` apart, no next
 * row). [maxCards] caps the card row (Discover's grid width); by default it fills the screen.
 * [card] overrides the card size (Discover's landscape grid card).
 */
@Composable
internal fun MarqueeHomeSkeleton(
    landscape: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    maxCards: Int = Int.MAX_VALUE,
    card: ModernCatalogCardMetrics? = null
) {
    val marquee = NuvioTheme.marquee
    val home = marquee.home
    val cardGap = if (compact) marquee.search.gridGap else home.cardGap
    val fill = marquee.skeletonFill
    val start = NuvioTheme.layout.tvSafeHorizontal
    val rowTitleHeight = with(LocalDensity.current) { NuvioTheme.textStyles.rowTitle.lineHeight.toDp() }
    val cardWidth = card?.width ?: if (landscape) home.landscapeCardWidth else home.portraitCardWidth
    val cardHeight = card?.height ?: if (landscape) home.landscapeCardHeight else home.portraitCardHeight
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(NuvioPrimitives.black)
            .drawBehind {
                fun bar(x: Dp, y: Dp, w: Dp, h: Dp, r: Dp = BAR_RADIUS) = marqueeSkeletonBar(fill, x, y, w, h, r)
                fun textBar(x: Dp, lineTop: Dp, lineHeight: Dp, barSize: DpSize) =
                    marqueeSkeletonTextBar(fill, x, lineTop, lineHeight, barSize, BAR_RADIUS)

                val bandBottom = size.height.toDp() - home.focusedCardBottomInset
                val cardsTop = bandBottom - cardHeight
                val rowTitleTop = cardsTop - home.rowTitleGap - rowTitleHeight
                textBar(start, rowTitleTop, rowTitleHeight, if (compact) COMPACT_ROW_TITLE_BAR else ROW_TITLE_BAR)
                var x = start
                var cards = 0
                while (x.toPx() < size.width && cards < maxCards) {
                    bar(x, cardsTop, cardWidth, cardHeight, marquee.cardRadius)
                    x += cardWidth + cardGap
                    cards++
                }
                var y = rowTitleTop - home.heroAboveRow
                if (compact) {
                    COMPACT_HERO_BARS.withIndex().reversed().forEach { (index, line) ->
                        y -= line.height
                        val radius = if (index == COMPACT_HERO_TITLE_INDEX) TITLE_BAR_RADIUS else BAR_RADIUS
                        bar(start, y, line.width, line.height, radius)
                        y -= COMPACT_HERO_GAP
                    }
                    return@drawBehind
                }
                textBar(start, bandBottom + home.rowGap, rowTitleHeight, NEXT_ROW_TITLE_BAR)

                // Hero, stacked upward from its bottom edge with the hero gap.
                HERO_DESC_BARS.asReversed().forEach { line ->
                    y -= line.height
                    bar(start, y, line.width, line.height)
                    y -= home.heroGap
                }
                y -= HERO_DESC_TOP + HERO_META_BAR.height
                bar(start, y, HERO_META_BAR.width, HERO_META_BAR.height)
                y -= home.heroGap + HERO_TITLE_BOX
                bar(
                    start,
                    y + (HERO_TITLE_BOX - HERO_TITLE_BAR.height) / 2,
                    HERO_TITLE_BAR.width,
                    HERO_TITLE_BAR.height,
                    TITLE_BAR_RADIUS
                )
                y -= home.heroGap + HERO_EYEBROW_BAR.height
                bar(start, y, HERO_EYEBROW_BAR.width, HERO_EYEBROW_BAR.height)
            }
    )
}

/**
 * One placeholder row in a list (Search's "more catalogs still answering" row under its results):
 * stage 4's compact row title bar over portrait card placeholders in the result rows' geometry
 * (sandbox `u4-search-discover-marquee` stage 5, `home.cardGap` apart), filling the width.
 * Static, one draw pass.
 */
@Composable
internal fun MarqueeSkeletonRow(
    modifier: Modifier = Modifier,
    landscape: Boolean = false,
    card: ModernCatalogCardMetrics? = null
) {
    val marquee = NuvioTheme.marquee
    val home = marquee.home
    val cardWidth = card?.width ?: if (landscape) home.landscapeCardWidth else home.portraitCardWidth
    val cardHeight = card?.height ?: if (landscape) home.landscapeCardHeight else home.portraitCardHeight
    val fill = marquee.skeletonFill
    val start = NuvioTheme.layout.tvSafeHorizontal
    val rowTitleHeight = with(LocalDensity.current) { NuvioTheme.textStyles.rowTitle.lineHeight.toDp() }
    val cardsTop = rowTitleHeight + home.rowTitleGap
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(cardsTop + cardHeight)
            .drawBehind {
                marqueeSkeletonTextBar(fill, start, 0.dp, rowTitleHeight, COMPACT_ROW_TITLE_BAR, BAR_RADIUS)
                var x = start
                while (x.toPx() < size.width) {
                    marqueeSkeletonBar(fill, x, cardsTop, cardWidth, cardHeight, marquee.cardRadius)
                    x += cardWidth + home.cardGap
                }
            }
    )
}

/** What the empty / error block's one button does, with its label and icon. */
internal enum class HomeMessageAction(@param:StringRes val label: Int, val icon: ImageVector) {
    OPEN_ADDONS(R.string.home_empty_open_addons, Icons.Default.Add),
    RETRY(R.string.action_retry, Icons.Default.Refresh)
}

/**
 * Home empty / error block (Marquee spec §Screens "Home states", mockup stage 8): the shared
 * [MarqueeMessage] with the "HOME" eyebrow. The button carries the content focus requester, so
 * Down from the top bar and OK on the Home tab land on it.
 */
@Composable
internal fun MarqueeHomeMessage(
    title: String,
    body: AnnotatedString?,
    action: HomeMessageAction,
    onAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    MarqueeMessage(
        eyebrow = stringResource(R.string.nav_home),
        title = title,
        body = body,
        actionLabel = stringResource(action.label),
        actionIcon = action.icon,
        onAction = onAction,
        focusRequester = LocalContentFocusRequester.current,
        modifier = modifier
    )
}
