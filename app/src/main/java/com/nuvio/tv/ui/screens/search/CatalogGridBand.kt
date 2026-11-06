package com.nuvio.tv.ui.screens.search

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.screens.home.ModernCarouselItem
import com.nuvio.tv.ui.screens.home.ModernPayload
import com.nuvio.tv.ui.util.StableRef
import com.nuvio.tv.ui.util.asStable
import com.nuvio.tv.ui.util.dpadVerticalFastScroll
import com.nuvio.tv.ui.util.requestFocusAfterFrames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// One catalog row as a Marquee grid band with no ViewModel behind it (Spec 28c): FolderDetail's
// Tabs mode (the selected tab's row) and Catalog See All. DiscoverBand's pattern (saved focused
// cell, Back-from-Details restore on resume, hero settle, auto-load near the end), with paging
// automatic: a loading tile while the next page loads, never a Load more button. The host draws
// the hero, row title, messages and the list; geometry: GridBandGeometry.kt.

/** The band's tile after the last card: loading while [rowLoading] (the next page is on its way). */
internal fun catalogGridAction(rowLoading: Boolean): DiscoverGridAction =
    if (rowLoading) DiscoverGridAction.Loading else DiscoverGridAction.None

/**
 * [row] as the band's grid (empty when null): its items as cards keyed under [rowKey], in the
 * [gridForm] of those cards under the Card shape setting [landscapeSetting] (Spec 41).
 */
internal fun catalogGrid(row: CatalogRow?, rowKey: String, landscapeSetting: Boolean): DiscoverGrid {
    val items = discoverCarouselItems(row?.items.orEmpty(), rowKey, row?.addonBaseUrl.orEmpty())
    return DiscoverGrid(
        items = items.asStable(),
        action = catalogGridAction(row?.isLoading == true),
        addonBaseUrl = row?.addonBaseUrl.orEmpty(),
        form = items.gridForm(landscapeSetting)
    )
}

/** Auto-load may wait for the end: the band is [active], [row] has more, no page in flight, a first page shown. */
internal fun catalogGridCanAutoLoad(row: CatalogRow?, active: Boolean): Boolean =
    active && row != null && row.hasMore && !row.isLoading && row.items.isNotEmpty()

/** The card a restore focuses: [savedCell] clamped to the cards shown; null when there are none. */
internal fun catalogGridRestoreCell(savedCell: Int, itemCount: Int): Int? =
    if (itemCount <= 0) null else savedCell.coerceIn(0, itemCount - 1)

/**
 * The band's state in a host list: focus memory across Details (the focused cell, saved), the
 * first open's focus on card 1 (window NOTE N5), the active row, fast scroll, the hero's settled
 * card, and the card callbacks (stable: the cards capture them once). [listState] and
 * [firstGridItem] (the list index of grid row 0) are fixed for the band's lifetime.
 */
@Stable
internal class CatalogGridBand(
    val listState: LazyListState,
    private val scope: CoroutineScope,
    private val firstGridItem: Int,
    private val settle: MarqueeHeroSettle,
    private val focusedIndex: MutableState<Int>,
    private val restoreFocus: MutableState<Boolean>,
    private val restoreOnResume: MutableState<Boolean>,
    private val initialFocusPending: MutableState<Boolean>,
    initialRowKey: String,
    private val gridState: State<DiscoverGrid>,
    private val headerFocusRequesterState: State<FocusRequester?>,
    private val isItemWatchedState: State<(MetaPreview) -> Boolean>,
    private val onPrefetchState: State<(MetaPreview) -> Unit>,
    private val onLongPressState: State<(MetaPreview, String) -> Unit>,
    private val onOpenDetailState: State<(MetaPreview, String) -> Unit>
) {
    val grid: DiscoverGrid get() = gridState.value
    val isItemWatched: (MetaPreview) -> Boolean get() = isItemWatchedState.value

    // Read in draw (row alpha) and in callbacks only: a focus move never recomposes the host.
    val activeRow = mutableIntStateOf(grid.rowOf(settle.seedIndex))
    private val fastScrollColumn = mutableIntStateOf(0)
    val isFastScrolling = mutableStateOf(false)
    val requesters = StableRef(mutableMapOf<Int, FocusRequester>())
    private var previousRowKey = initialRowKey
    // The card count when the band last asked for a page; still the count = that page failed.
    private var loadAskedAtCount = -1

    /** The hero's card: the settled one, before any settle the restored or first one. */
    val settledItem: State<ModernCarouselItem?> = derivedStateOf {
        val items = grid.items
        items.firstOrNull { it.key == settle.settledKey } ?: items.getOrNull(settle.seedIndex) ?: items.firstOrNull()
    }

    /** A card is to take focus: back from Details (set on resume), or the first open's card 1. */
    val focusPending: Boolean get() = restoreFocus.value || initialFocusPending.value

    fun onResumed() {
        if (restoreOnResume.value) {
            restoreFocus.value = true
            restoreOnResume.value = false
        }
    }

    /** Leaving for Details (a card, or the poster options): Back restores the focused card. */
    fun onLeavingForDetail() {
        restoreOnResume.value = true
    }

    /** The user moved focus before card 1 took it (the host's header got it): leave it there. */
    fun cancelInitialFocus() {
        initialFocusPending.value = false
    }

    /**
     * Another row (a tab switch) starts at the top; not on first composition or a return. The
     * user is on the header then, so card 1 no longer takes focus when the row loads.
     */
    suspend fun onRowKey(rowKey: String) {
        if (rowKey == previousRowKey) return
        previousRowKey = rowKey
        initialFocusPending.value = false
        loadAskedAtCount = -1
        focusedIndex.value = 0
        activeRow.intValue = 0
        settle.reset()
        listState.scrollToItem(0)
    }

    /**
     * Back from Details focuses the saved card (clamped to the cards shown), waiting while
     * [loading] (a new ViewModel after process death); with no card the header takes focus.
     * Else, on the first open, card 1 takes focus once the first page shows. The flags clear
     * after the request: they key the calling effect, which a write before the frame waits
     * would cancel.
     */
    suspend fun focusPendingCell(loading: Boolean) {
        if (restoreFocus.value) {
            val saved = focusedIndex.value
            if (saved !in 0 until grid.items.size && loading) return
            val cell = catalogGridRestoreCell(saved, grid.items.size)
            if (cell != null) {
                focusGridCell(listState, firstGridItem, grid, requesters, cell)
            } else {
                headerFocusRequesterState.value?.requestFocusAfterFrames()
            }
            restoreFocus.value = false
            initialFocusPending.value = false
        } else if (initialFocusPending.value && grid.items.isNotEmpty()) {
            focusGridCell(listState, firstGridItem, grid, requesters, 0)
            initialFocusPending.value = false
        }
    }

    /**
     * Waits for the band's turn to ask for the next page: the last laid-out grid row within two
     * rows of the end. After an ask that brought no card (a failed page keeps `hasMore`), it
     * first waits for focus to move, so a failing page retries per D-pad press, not in a loop.
     */
    suspend fun awaitLoadMoreTurn() {
        if (grid.items.size == loadAskedAtCount) {
            val cell = focusedIndex.value
            snapshotFlow { focusedIndex.value }.first { it != cell }
        }
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .first { discoverShouldAutoLoad(it - firstGridItem, grid.rowCount) }
        loadAskedAtCount = grid.items.size
    }

    /** Back from the grid: the list's top (the header), its focus requester focused. */
    fun backToHeader() {
        activeRow.intValue = 0
        scope.launch {
            listState.scrollToItem(0)
            headerFocusRequesterState.value?.requestFocusAfterFrames()
        }
    }

    private fun onCellFocused(cell: Int) {
        activeRow.intValue = grid.rowOf(cell)
        focusedIndex.value = cell
        initialFocusPending.value = false
    }

    val onItemFocused: (Int, ModernCarouselItem) -> Unit = { cell, item ->
        onCellFocused(cell)
        settle.onFocused(item.key)
        item.metaPreview?.let { onPrefetchState.value(it) }
    }

    val onItemClick: (ModernCarouselItem) -> Unit = { item ->
        val payload = item.payload as? ModernPayload.Catalog
        val meta = item.metaPreview
        if (payload != null && meta != null) {
            onLeavingForDetail()
            onOpenDetailState.value(meta, payload.addonBaseUrl)
        }
    }

    val onItemLongPress: (ModernCarouselItem) -> Unit = { item ->
        item.metaPreview?.let { onLongPressState.value(it, grid.addonBaseUrl) }
    }

    fun onFastScrollingChanged(active: Boolean) {
        // Snapshot on drag start: the focused card scrolls away before landing.
        if (active) fastScrollColumn.intValue = grid.columnOf(focusedIndex.value)
        isFastScrolling.value = active
    }

    /** Fast-scroll landing: [gridFastScrollLandingCell] in the column the drag began in. */
    fun landFastScroll(anchorInsetPx: Int) {
        val cell = gridFastScrollLandingCell(listState.layoutInfo, firstGridItem, grid, fastScrollColumn.intValue, anchorInsetPx)
            ?: return
        runCatching { requesters.value[cell]?.requestFocus() }
    }
}

/**
 * The band over [row] (null until the host has one) for a host list whose grid rows start at
 * [firstGridItem]; [listState] and [firstGridItem] must not change for the band's lifetime.
 * [rowKey] names the row (card keys; a change starts the band at the top). [loading]: the row's
 * first page (or a reload) is on its way, so a restore waits. [active]: the grid is in the host's
 * list (focus and auto-load run only then). [headerFocusRequester]: what
 * Back from the grid, and a restore with no card left, focus (the selected tab); null for none.
 * [onLoadMore] asks for the next page; [onOpenDetail] gets the card's title and addon after the
 * band marked the return (the host updates the hero hand-off and navigates).
 */
@Composable
internal fun rememberCatalogGridBand(
    row: CatalogRow?,
    rowKey: String,
    loading: Boolean,
    landscapeSetting: Boolean,
    listState: LazyListState,
    firstGridItem: Int,
    active: Boolean,
    headerFocusRequester: FocusRequester?,
    isItemWatched: (MetaPreview) -> Boolean,
    onLoadMore: () -> Unit,
    onPrefetch: (MetaPreview) -> Unit,
    onLongPress: (MetaPreview, String) -> Unit,
    onOpenDetail: (MetaPreview, String) -> Unit
): CatalogGridBand {
    val focusedIndex = rememberSaveable { mutableStateOf(0) }
    val restoreFocus = rememberSaveable { mutableStateOf(false) }
    val restoreOnResume = rememberSaveable { mutableStateOf(false) }
    val initialFocusPending = rememberSaveable { mutableStateOf(true) }

    val grid = remember(row?.items, row?.isLoading, row?.addonBaseUrl, rowKey, landscapeSetting) {
        catalogGrid(row, rowKey, landscapeSetting)
    }

    // Read once: the focused cell is written on every card focus.
    val initialFocusedIndex = remember { focusedIndex.value }
    val settle = rememberMarqueeHeroSettle(initialFocusedIndex, listState)
    val gridState = rememberUpdatedState(grid)
    val headerFocusRequesterState = rememberUpdatedState(headerFocusRequester)
    val isItemWatchedState = rememberUpdatedState(isItemWatched)
    val onLoadMoreState = rememberUpdatedState(onLoadMore)
    val onPrefetchState = rememberUpdatedState(onPrefetch)
    val onLongPressState = rememberUpdatedState(onLongPress)
    val onOpenDetailState = rememberUpdatedState(onOpenDetail)
    val scope = rememberCoroutineScope()
    val band = remember {
        CatalogGridBand(
            listState = listState,
            scope = scope,
            firstGridItem = firstGridItem,
            settle = settle,
            focusedIndex = focusedIndex,
            restoreFocus = restoreFocus,
            restoreOnResume = restoreOnResume,
            initialFocusPending = initialFocusPending,
            initialRowKey = rowKey,
            gridState = gridState,
            headerFocusRequesterState = headerFocusRequesterState,
            isItemWatchedState = isItemWatchedState,
            onPrefetchState = onPrefetchState,
            onLongPressState = onLongPressState,
            onOpenDetailState = onOpenDetailState
        )
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) band.onResumed()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Also while inactive: a tab without cards in between must still reset the next grid.
    LaunchedEffect(rowKey) { band.onRowKey(rowKey) }
    LaunchedEffect(active, band.focusPending, grid.items.size, loading) {
        if (active) band.focusPendingCell(loading)
    }

    // Infinite scroll: ask for the next page as the band nears the end. A page in flight ends
    // the effect (canAutoLoad); the loop covers an ask whose loading frame never showed (a
    // debounced host coalescing a fast failure), which awaitLoadMoreTurn retries per focus move.
    val canAutoLoad = catalogGridCanAutoLoad(row, active)
    LaunchedEffect(canAutoLoad) {
        if (!canAutoLoad) return@LaunchedEffect
        while (true) {
            band.awaitLoadMoreTurn()
            onLoadMoreState.value()
        }
    }
    return band
}

/** The grid rows as items of the host list. */
internal fun LazyListScope.catalogGridItems(band: CatalogGridBand, accent: () -> Color) {
    val grid = band.grid
    items(
        count = grid.rowCount,
        key = { "catalog_grid_row_$it" },
        contentType = { "catalog_grid_row" }
    ) { rowIndex ->
        DiscoverGridRow(
            rowIndex = rowIndex,
            grid = grid,
            activeRow = band.activeRow,
            requesters = band.requesters,
            accent = accent,
            isWatched = band.isItemWatched,
            onItemFocused = band.onItemFocused,
            // The loading tile is never focusable.
            onActionFocused = {},
            onItemClick = band.onItemClick,
            onItemLongPress = band.onItemLongPress,
            onAction = {}
        )
    }
}

/** Vertical fast scroll over the band's host list, landing on the grid row at the band anchor. */
internal fun Modifier.catalogGridFastScroll(band: CatalogGridBand, anchorInsetPx: Int): Modifier =
    dpadVerticalFastScroll(
        scrollableState = band.listState,
        onFastScrollingChanged = band::onFastScrollingChanged,
        resolveVerticalLanding = {
            band.landFastScroll(anchorInsetPx)
            null // Focus is moved there, not through the returned key.
        }
    )
