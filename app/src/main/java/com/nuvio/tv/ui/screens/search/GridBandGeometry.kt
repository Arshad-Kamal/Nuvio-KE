package com.nuvio.tv.ui.screens.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.tv.ui.theme.NuvioMarqueeTokens
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Where a grid band's layers sit (dp on the scaled canvas): Library's grid (u7 stage 6) and
 * FolderDetail's Tabs mode under a tab row, See All (Spec 28) without one. The list holds the tab
 * row (if any), the band lead, then the grid rows, [itemGap] apart; the focused row's cards start
 * at [cardsTop].
 */
@Immutable
internal data class GridBandGeometry(
    /** The list's top (the list clips there); the tab row sits `library.tabRowTop` below it. */
    val listTop: Dp,
    /** The tab row's bottom ([listTop] without one): what the lead and the hero's room are measured from. */
    val tabRowBottom: Dp,
    /** Between list items: `search.gridGap`. */
    val itemGap: Dp,
    /** The focused card's bottom from the screen bottom (Home's band anchor). */
    val bandBottomInset: Dp,
    /** The list's spacer before grid row 0 (after the tab row), so the first cards start at [cardsTop]. */
    val bandLeadHeight: Dp,
    /** Top of the focused grid row's cards. */
    val cardsTop: Dp,
    /** The grid's one fixed row title, `home.rowTitleGap` above the cards. */
    val rowTitleTop: Dp,
    val heroBottomInset: Dp,
    val heroMaxHeight: Dp
)

/**
 * [GridBandGeometry] for a window [screenHeight] tall: the tab row under [listTop] when [tabRow]
 * (else the lead is the list's first item), a grid of [form], row titles
 * [rowTitleHeight] tall. Pure (the tokens passed in) for tests.
 */
internal fun gridBandGeometryOf(
    screenHeight: Dp,
    listTop: Dp,
    rowTitleHeight: Dp,
    form: GridForm,
    marquee: NuvioMarqueeTokens,
    tabRow: Boolean = true
): GridBandGeometry {
    val home = marquee.home
    val itemGap = marquee.search.gridGap
    val tabRowBottom = if (tabRow) listTop + marquee.library.tabRowTop + marquee.search.filterPillHeight else listTop
    val cardsTop = discoverCardsTop(screenHeight, form, marquee)
    val rowTitleTop = cardsTop - home.rowTitleGap - rowTitleHeight
    val heroBottom = rowTitleTop - home.heroAboveRow
    return GridBandGeometry(
        listTop = listTop,
        tabRowBottom = tabRowBottom,
        itemGap = itemGap,
        bandBottomInset = home.focusedCardBottomInset,
        // Item gaps: tab row to lead (with a tab row), lead to grid row 0.
        bandLeadHeight = (cardsTop - tabRowBottom - itemGap * (if (tabRow) 2 else 1)).coerceAtLeast(0.dp),
        cardsTop = cardsTop,
        rowTitleTop = rowTitleTop,
        heroBottomInset = (screenHeight - heroBottom).coerceAtLeast(0.dp),
        heroMaxHeight = (heroBottom - tabRowBottom - home.heroGap).coerceAtLeast(0.dp)
    )
}

/** [gridBandGeometryOf] for this window (frame rule BC-D1: real pixels through the scaled density). */
@Composable
internal fun gridBandGeometry(listTop: Dp, form: GridForm, tabRow: Boolean = true): GridBandGeometry {
    val density = LocalDensity.current
    val screenHeight = with(density) { LocalContext.current.resources.displayMetrics.heightPixels.toDp() }
    val rowTitleHeight = with(density) { NuvioTheme.textStyles.rowTitle.lineHeight.toDp() }
    return gridBandGeometryOf(screenHeight, listTop, rowTitleHeight, form, NuvioTheme.marquee, tabRow)
}
