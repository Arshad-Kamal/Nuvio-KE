package com.nuvio.tv.ui.screens.search

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.screens.home.HeroBackdropState
import com.nuvio.tv.ui.screens.home.ModernCarouselItem
import com.nuvio.tv.ui.screens.home.ModernPayload
import com.nuvio.tv.ui.theme.NuvioMarquee
import com.nuvio.tv.ui.theme.NuvioMarqueeTokens
import com.nuvio.tv.ui.util.StableList
import com.nuvio.tv.ui.util.StableRef
import com.nuvio.tv.ui.util.asStable
import com.nuvio.tv.ui.util.dpadVerticalFastScroll
import com.nuvio.tv.ui.util.requestFocusAfterFrames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

// The Discover grid's state in a host list: the Discover tab's (SearchDiscoverSection.kt) or
// Search's (SearchScreen.kt stage 3). The composables that draw it (pills, grid rows, hero layer)
// live in SearchDiscoverSection.kt.

internal fun DiscoverUiState.selectedCatalog(): DiscoverCatalog? =
    discoverCatalogs.firstOrNull { it.key == selectedDiscoverCatalogKey }

internal enum class DiscoverGridAction { None, LoadMore, Loading }

/**
 * The grid's cells: the result cards, then the action tile when there is one, packed into lines
 * (grid rows) by their [gridCellSize] under [form] ([gridForm], Spec 41).
 */
@Immutable
internal class DiscoverGrid(
    val items: StableList<ModernCarouselItem>,
    val action: DiscoverGridAction,
    val addonBaseUrl: String,
    val form: GridForm,
    marquee: NuvioMarqueeTokens = NuvioMarquee.tokens
) {
    // No grid (not even a lone Load more tile) until there are results, as built.
    val cellCount = if (items.isEmpty()) 0 else items.size + if (action != DiscoverGridAction.None) 1 else 0
    private val lineStarts: IntArray = packGridLines(
        List(cellCount) { gridCellSize(items.getOrNull(it), form, marquee).width },
        marquee.search.gridLineWidth,
        marquee.search.gridGap
    )
    val rowCount = lineStarts.size

    /** The grid row holding [cell]. */
    fun rowOf(cell: Int): Int = gridRowOf(lineStarts, cell)

    /** [cell]'s place in its row (fast scroll keeps it across rows). */
    fun columnOf(cell: Int): Int = cell - (lineStarts.getOrNull(rowOf(cell)) ?: 0)

    /** The cells of grid row [row]; empty past the last. */
    fun cellsOf(row: Int): IntRange {
        val first = lineStarts.getOrNull(row) ?: return IntRange.EMPTY
        return first until (lineStarts.getOrNull(row + 1) ?: cellCount)
    }

    /** Fast-scroll landing on [row] at [column] ([gridLandingCell]). */
    fun landingCell(row: Int, column: Int): Int? = gridLandingCell(lineStarts, row, column, items.size)
}

/**
 * The Discover grid's state in a host list: focus memory across Details (the focused cell, saved),
 * the active row, fast scroll, Load more focus, the hero's settled card, and the card and filter
 * callbacks (stable: the cards capture them once). [listState] and [firstGridItem] (the list index
 * of grid row 0) are fixed for the band's lifetime.
 */
@Stable
internal class DiscoverBand(
    private val viewModel: DiscoverViewModel,
    val listState: LazyListState,
    private val scope: CoroutineScope,
    private val firstGridItem: Int,
    private val settle: MarqueeHeroSettle,
    private val focusedIndex: MutableState<Int>,
    private val restoreFocus: MutableState<Boolean>,
    private val restoreOnResume: MutableState<Boolean>,
    initialFilterKey: String,
    private val gridState: State<DiscoverGrid>,
    private val isItemWatchedState: State<(MetaPreview) -> Boolean>,
    private val onNavigateToDetailState: State<(String, String, String) -> Unit>
) {
    val grid: DiscoverGrid get() = gridState.value
    val isItemWatched: (MetaPreview) -> Boolean get() = isItemWatchedState.value

    // Read in draw (row alpha), in callbacks, and by Search through a row >= 1 derived state (Spec 38
    // Q4 B): a focus move recomposes the host only when it crosses row 1.
    val activeRow = mutableIntStateOf(grid.rowOf(settle.seedIndex))
    private val fastScrollColumn = mutableIntStateOf(0)
    val isFastScrolling = mutableStateOf(false)
    val requesters = StableRef(mutableMapOf<Int, FocusRequester>())
    val filterFocusRequester = FocusRequester()

    // Load more: the cell the first new card will take, then (once it exists) the cell to focus.
    var pendingNewItemIndex by mutableStateOf<Int?>(null)
        private set
    var newItemFocusIndex by mutableStateOf<Int?>(null)
        private set
    private var previousFilterKey = initialFilterKey

    /** The hero's result: the settled card, before any settle the restored or first one. */
    val settledItem: State<ModernCarouselItem?> = derivedStateOf {
        val items = grid.items
        items.firstOrNull { it.key == settle.settledKey } ?: items.getOrNull(settle.seedIndex) ?: items.firstOrNull()
    }

    /** Returning from Details: the pills stay unfocusable until the grid card has focus again. */
    val blocksFilterFocus: Boolean get() = restoreFocus.value || restoreOnResume.value

    /** Back from Details: the saved card is to be focused (set on resume). */
    val restorePending: Boolean get() = restoreFocus.value

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

    /**
     * A new filter combination starts at the top (not on first composition or a return); a Load
     * more intent from the old results no longer applies.
     */
    suspend fun onFilterKey(filterKey: String) {
        if (filterKey == previousFilterKey) return
        previousFilterKey = filterKey
        dropPendingFocus()
        activeRow.intValue = 0
        settle.reset()
        listState.scrollToItem(0)
    }

    /** The grid left the host's list: a Load more focus no longer applies. */
    fun dropPendingFocus() {
        pendingNewItemIndex = null
        newItemFocusIndex = null
    }

    /** Load more was pressed: the first new card takes focus once it arrives. */
    fun promoteNewItem(itemCount: Int) {
        val target = pendingNewItemIndex ?: return
        if (itemCount <= target) return
        pendingNewItemIndex = null
        newItemFocusIndex = target
    }

    /**
     * Focuses the first new card after Load more, else (back from Details) the saved card. A
     * restore waits while the grid is still loading (a new ViewModel after process death); when
     * the card is gone, the pills take focus (the host skipped its own entry focus).
     */
    suspend fun focusPendingCell() {
        val newItem = newItemFocusIndex
        if (!restoreFocus.value && newItem == null) return
        val target = newItem ?: focusedIndex.value
        if (target in 0 until grid.cellCount) {
            focusGridCell(listState, firstGridItem, grid, requesters, target)
        } else if (newItem == null) {
            val state = viewModel.uiState.value
            if (state.discoverLoading || !state.discoverInitialized) return
            // Cleared after the request: restoreFocus keys the calling effect, which a write
            // before the frame waits would cancel.
            filterFocusRequester.requestFocusAfterFrames()
            restoreFocus.value = false
            return
        }
        if (newItem != null) newItemFocusIndex = null else restoreFocus.value = false
    }

    /** Auto-load once the last laid-out grid row is within two rows of the end. */
    suspend fun awaitNearEnd() {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .first { discoverShouldAutoLoad(it - firstGridItem, grid.rowCount) }
    }

    /**
     * Back from the grid returns to the filters (as built): the host list's top (index 0; the
     * tab's pills, or the host's own leading items above them), Type pill focused.
     */
    fun backToFilters() {
        activeRow.intValue = 0
        scope.launch {
            listState.scrollToItem(0)
            filterFocusRequester.requestFocusAfterFrames()
        }
    }

    fun selectType(type: String) {
        focusedIndex.value = 0
        viewModel.selectDiscoverType(type)
    }

    fun selectCatalog(key: String) {
        focusedIndex.value = 0
        viewModel.selectDiscoverCatalog(key)
    }

    fun selectGenre(genre: String?) {
        focusedIndex.value = 0
        viewModel.selectDiscoverGenre(genre)
    }

    private fun onCellFocused(cell: Int) {
        activeRow.intValue = grid.rowOf(cell)
        focusedIndex.value = cell
    }

    val onItemFocused: (Int, ModernCarouselItem) -> Unit = { cell, item ->
        onCellFocused(cell)
        settle.onFocused(item.key)
        item.metaPreview?.let { viewModel.prefetchMetaOnFocus(it.id, it.rawType) }
    }

    // The tile never settles the hero: it keeps the last title (stage 11).
    val onActionFocused: (Int) -> Unit = { cell -> onCellFocused(cell) }

    val onItemClick: (ModernCarouselItem) -> Unit = { item ->
        val payload = item.payload as? ModernPayload.Catalog
        if (payload != null) {
            HeroBackdropState.update(item.metaPreview?.backdropUrl)
            onLeavingForDetail()
            onNavigateToDetailState.value(payload.itemId, payload.itemType, payload.addonBaseUrl)
        }
    }

    val onItemLongPress: (ModernCarouselItem) -> Unit = { item ->
        item.metaPreview?.let { viewModel.posterOptions.show(it, grid.addonBaseUrl) }
    }

    val onAction: () -> Unit = {
        pendingNewItemIndex = grid.items.size
        viewModel.loadNextDiscoverResults()
    }

    fun onFastScrollingChanged(active: Boolean) {
        // Snapshot on drag start: the focused card scrolls away before landing.
        if (active) fastScrollColumn.intValue = grid.columnOf(focusedIndex.value)
        isFastScrolling.value = active
    }

    /**
     * Fast-scroll landing: focus the cell of the grid row nearest the band anchor ([anchorInsetPx]
     * above the viewport end), in the column the drag began in; a tile-only row lands on the row
     * above it.
     */
    fun landFastScroll(anchorInsetPx: Int) {
        val cell = gridFastScrollLandingCell(listState.layoutInfo, firstGridItem, grid, fastScrollColumn.intValue, anchorInsetPx)
            ?: return
        runCatching { requesters.value[cell]?.requestFocus() }
    }
}

/** Focuses [cell] of [grid] (rows from list index [firstGridItem]), scrolling its row in first when it is not laid out. */
internal suspend fun focusGridCell(
    listState: LazyListState,
    firstGridItem: Int,
    grid: DiscoverGrid,
    requesters: StableRef<MutableMap<Int, FocusRequester>>,
    cell: Int
) {
    val listIndex = firstGridItem + grid.rowOf(cell)
    if (listState.layoutInfo.visibleItemsInfo.none { it.index == listIndex }) listState.scrollToItem(listIndex)
    requesters.value.getOrPut(cell) { FocusRequester() }.requestFocusAfterFrames()
}

/**
 * A grid band's fast-scroll landing (Discover's and [CatalogGridBand]'s): the cell of the laid-out
 * grid row (rows from list index [firstGridItem]) nearest the band anchor ([anchorInsetPx] above
 * the viewport end), in [column]; a tile-only row lands on the row above it. Null when no grid
 * row is laid out.
 */
internal fun gridFastScrollLandingCell(
    info: LazyListLayoutInfo,
    firstGridItem: Int,
    grid: DiscoverGrid,
    column: Int,
    anchorInsetPx: Int
): Int? {
    val anchorPx = info.viewportEndOffset - anchorInsetPx
    val row = info.visibleItemsInfo
        .filter { it.index >= firstGridItem && it.index < firstGridItem + grid.rowCount }
        .minByOrNull { abs(it.offset + it.size - anchorPx) }
        ?.let { it.index - firstGridItem }
        ?: return null
    return grid.landingCell(row, column) ?: grid.landingCell(row - 1, column)
}

/**
 * The Discover grid's state for a host list whose grid rows start at [firstGridItem]; [listState]
 * and [firstGridItem] must not change for the band's lifetime. [active]: the grid is in the host's
 * list (Load more, focus restore and the filter-change scroll run only then). The focused cell and
 * the return-from-Details flags survive navigation (saveable).
 */
@Composable
internal fun rememberDiscoverBand(
    viewModel: DiscoverViewModel,
    uiState: DiscoverUiState,
    listState: LazyListState,
    firstGridItem: Int,
    active: Boolean,
    onNavigateToDetail: (String, String, String) -> Unit
): DiscoverBand {
    val focusedIndex = rememberSaveable { mutableStateOf(0) }
    val restoreFocus = rememberSaveable { mutableStateOf(false) }
    val restoreOnResume = rememberSaveable { mutableStateOf(false) }
    val isItemWatched = rememberWatchedPredicate(viewModel.watchedMovieIds, viewModel.watchedSeriesIds)

    val addonBaseUrl = uiState.selectedCatalog()?.addonBaseUrl.orEmpty()
    val filterKey = "${uiState.selectedDiscoverType}|${uiState.selectedDiscoverCatalogKey}|${uiState.selectedDiscoverGenre}"
    val items = remember(uiState.discoverResults, filterKey, addonBaseUrl) {
        discoverCarouselItems(uiState.discoverResults, "discover|$filterKey", addonBaseUrl).asStable()
    }
    val action = when {
        // Pending results ("show more" from the fetched page) beat a load in progress, as built.
        uiState.pendingDiscoverResults.isNotEmpty() -> DiscoverGridAction.LoadMore
        uiState.discoverLoadingMore -> DiscoverGridAction.Loading
        uiState.discoverHasMore -> DiscoverGridAction.LoadMore
        else -> DiscoverGridAction.None
    }
    val form = remember(items, uiState.landscapePostersEnabled) { items.list.gridForm(uiState.landscapePostersEnabled) }
    val grid = remember(items, action, addonBaseUrl, form) { DiscoverGrid(items, action, addonBaseUrl, form) }

    // Read once: the focused cell is written on every card focus.
    val initialFocusedIndex = remember { focusedIndex.value }
    val settle = rememberMarqueeHeroSettle(initialFocusedIndex, listState)
    val gridState = rememberUpdatedState(grid)
    val isItemWatchedState = rememberUpdatedState(isItemWatched)
    val onNavigateToDetailState = rememberUpdatedState(onNavigateToDetail)
    val scope = rememberCoroutineScope()
    val band = remember {
        DiscoverBand(
            viewModel = viewModel,
            listState = listState,
            scope = scope,
            firstGridItem = firstGridItem,
            settle = settle,
            focusedIndex = focusedIndex,
            restoreFocus = restoreFocus,
            restoreOnResume = restoreOnResume,
            initialFilterKey = filterKey,
            gridState = gridState,
            isItemWatchedState = isItemWatchedState,
            onNavigateToDetailState = onNavigateToDetailState
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

    LaunchedEffect(filterKey, active) {
        if (active) band.onFilterKey(filterKey) else band.dropPendingFocus()
    }
    LaunchedEffect(active, band.restorePending, band.newItemFocusIndex, grid.cellCount, uiState.discoverLoading) {
        if (active) band.focusPendingCell()
    }
    LaunchedEffect(items.size, band.pendingNewItemIndex) { band.promoteNewItem(items.size) }

    // Infinite scroll: load the next page as the band nears the end.
    val shouldAutoLoad = active && uiState.discoverHasMore && !uiState.discoverLoadingMore && items.isNotEmpty()
    LaunchedEffect(grid.rowCount, shouldAutoLoad) {
        if (!shouldAutoLoad) return@LaunchedEffect
        band.awaitNearEnd()
        viewModel.loadNextDiscoverResults()
    }
    return band
}

/** Vertical fast scroll over the band's host list, landing on the grid row at the band anchor. */
internal fun Modifier.discoverFastScroll(band: DiscoverBand, anchorInsetPx: Int): Modifier =
    dpadVerticalFastScroll(
        scrollableState = band.listState,
        onFastScrollingChanged = band::onFastScrollingChanged,
        resolveVerticalLanding = {
            band.landFastScroll(anchorInsetPx)
            null // Focus is moved there, not through the returned key.
        }
    )
