package com.nuvio.tv.ui.screens.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.tv.ui.components.marquee.marqueeCardCaptionHeight
import com.nuvio.tv.ui.screens.home.HeroCarouselRow
import com.nuvio.tv.ui.screens.home.marqueeBandCardHeight
import com.nuvio.tv.ui.screens.search.GridForm
import com.nuvio.tv.ui.screens.search.gridBandGeometryOf
import com.nuvio.tv.ui.theme.NuvioTheme

/** Where Library's layers sit (dp on the scaled canvas); [libraryGeometry] derives it per mode. */
@Immutable
internal data class LibraryGeometry(
    /** The list's top: the top bar's `contentTop` (the list clips there). */
    val listTop: Dp,
    /** Between list items: Home's row gap, the grid's `search.gridGap`. */
    val itemGap: Dp,
    /** The focused card's bottom from the screen bottom (History: its caption's bottom). */
    val bandBottomInset: Dp,
    /** The band lead: the first row's title at the band anchor, or the grid's first cards at their top. */
    val bandLeadHeight: Dp,
    /** The grid's one fixed row title (stage 6). */
    val gridRowTitleTop: Dp,
    val heroBottomInset: Dp,
    val heroMaxHeight: Dp
)

/**
 * Library's geometry (stages 1, 6, 7): Search's band geometry under the tab row, the hero
 * `home.heroAboveRow` above the settled row's title ([settledRow]); over the grid the shared
 * [gridBandGeometryOf], its focused row's cards at the band anchor under one fixed row title. History's
 * cards ([captionedCards]) carry their caption under the card, so the card rises by its height.
 * [landscapeRows]: the band's rows are landscape (History, or the Card shape setting).
 */
@Composable
internal fun libraryGeometry(
    showsGrid: Boolean,
    gridForm: GridForm,
    firstRow: HeroCarouselRow?,
    settledRow: HeroCarouselRow?,
    landscapeRows: Boolean,
    captionedCards: Boolean
): LibraryGeometry {
    val marquee = NuvioTheme.marquee
    val home = marquee.home
    val styles = NuvioTheme.textStyles
    val density = LocalDensity.current
    // Frame rule (BC-D1): real window pixels through the current (scaled) density.
    val screenHeight = with(density) { LocalContext.current.resources.displayMetrics.heightPixels.toDp() }
    val rowTitleHeight = with(density) { styles.rowTitle.lineHeight.toDp() }
    val captionHeight = if (captionedCards) marqueeCardCaptionHeight(marquee.library.cardCaptionGap) else 0.dp
    val bandBottomInset = home.focusedCardBottomInset + captionHeight
    fun rowTitleTopOf(row: HeroCarouselRow?): Dp = screenHeight - bandBottomInset -
        marqueeBandCardHeight(row, landscapeRows, continueWatchingPoster = false, home = home) -
        home.rowTitleGap - rowTitleHeight
    val listTop = marquee.topBar.contentTop
    // The grid's own placement (shared with FolderDetail's Tabs and See All); it also names the
    // fixed row title's top, drawn only over the grid.
    val grid = gridBandGeometryOf(screenHeight, listTop, rowTitleHeight, gridForm, marquee)
    if (showsGrid) {
        return LibraryGeometry(
            listTop = listTop,
            itemGap = grid.itemGap,
            bandBottomInset = bandBottomInset,
            bandLeadHeight = grid.bandLeadHeight,
            gridRowTitleTop = grid.rowTitleTop,
            heroBottomInset = grid.heroBottomInset,
            heroMaxHeight = grid.heroMaxHeight
        )
    }
    val tabRowBottom = grid.tabRowBottom
    val itemGap = home.rowGap
    val heroBottom = rowTitleTopOf(settledRow) - home.heroAboveRow
    return LibraryGeometry(
        listTop = listTop,
        itemGap = itemGap,
        bandBottomInset = bandBottomInset,
        // Two item gaps: tab row to lead, lead to the first row.
        bandLeadHeight = (rowTitleTopOf(firstRow) - tabRowBottom - itemGap * 2).coerceAtLeast(0.dp),
        gridRowTitleTop = grid.rowTitleTop,
        heroBottomInset = (screenHeight - heroBottom).coerceAtLeast(0.dp),
        heroMaxHeight = (heroBottom - tabRowBottom - home.heroGap).coerceAtLeast(0.dp)
    )
}
