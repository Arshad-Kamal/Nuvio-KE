@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.nuvio.tv.ui.screens.search

import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.isPlaceholderItemId
import com.nuvio.tv.domain.model.stableKey
import com.nuvio.tv.ui.screens.home.HeroBackdropState
import com.nuvio.tv.ui.screens.home.HeroCarouselRow
import com.nuvio.tv.ui.screens.home.MarqueeBandFocus
import com.nuvio.tv.ui.screens.home.MarqueeRowSection
import com.nuvio.tv.ui.screens.home.MarqueeRowTile
import com.nuvio.tv.ui.screens.home.MarqueeSkeletonRow
import com.nuvio.tv.ui.screens.home.ModernCarouselItem
import com.nuvio.tv.ui.screens.home.isPlaceholderCard
import com.nuvio.tv.ui.screens.home.isWatchedTitle
import com.nuvio.tv.ui.screens.collection.CollectionBadge
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.StableRef
import com.nuvio.tv.ui.util.localizedContentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// Search's result band (approved sandbox `u4-search-discover-marquee` stages 5, 6): Marquee rows
// in the Card shape (Spec 41: each card its addon's shape, or Landscape) under the field row, Home's band
// shapes (MarqueeHomeContent). The rows are items of SearchScreen's list, so the field row scrolls
// out with them (F-4 B); SearchScreen keeps the field row, the state layer and the query state
// machine (review 2026-10-10-092534 M1).

/** Whether a card shows as watched (Search results and the Discover grid; their ViewModels' sets). */
@Composable
internal fun rememberWatchedPredicate(
    watchedMovieIds: StateFlow<Set<String>>,
    watchedSeriesIds: StateFlow<Set<String>>
): (MetaPreview) -> Boolean {
    val movieIds by watchedMovieIds.collectAsState()
    val seriesIds by watchedSeriesIds.collectAsState()
    return remember(movieIds, seriesIds) {
        { item: MetaPreview -> isWatchedTitle(item.id, item.apiType, movieIds, seriesIds) }
    }
}

/** List items before the first result row: SearchScreen's field row and the band lead. */
private const val RESULT_ROWS_FIRST_ITEM = 2

/**
 * The result band's state: the rows, their focus bookkeeping (F-4/F-13 on [focus]'s pending focus),
 * the hero's settled card and the card callbacks. The callbacks are stable: the rows' cards capture
 * them once.
 */
@Stable
internal class SearchResultsBand(
    private val viewModel: SearchViewModel,
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    private val heroSettle: MarqueeHeroSettle,
    private val rowsState: State<List<SearchResultRow>>,
    private val submittedQueryState: State<String>,
    private val isItemWatchedState: State<(MetaPreview) -> Boolean>,
    private val onCardFocusedState: State<() -> Unit>,
    private val onNavigateToDetailState: State<(String, String, String) -> Unit>,
    private val onNavigateToSeeAllState: State<(String, String, String) -> Unit>
) {
    val rows: List<SearchResultRow> get() = rowsState.value
    val isItemWatched: (MetaPreview) -> Boolean get() = isItemWatchedState.value

    // Band focus (active card, pending focus), each row's focused index and scroll, the requesters.
    val focus = MarqueeBandFocus()
    private val focusedItemByRow = mutableStateMapOf<String, Int>()
    private val rowListStates = mutableStateMapOf<String, LazyListState>()
    val focusedItemByRowRef = StableRef<MutableMap<String, Int>>(focusedItemByRow)
    val rowListStatesRef = StableRef<MutableMap<String, LazyListState>>(rowListStates)
    // Search rows never load more (searchCarouselRows); MarqueeRowSection still takes the map.
    val noLoadMoreTotals = StableRef<MutableMap<String, Int>>(mutableMapOf())
    val rowFocusRequesters = mutableMapOf<String, FocusRequester>()
    val itemFocusRequestersByRow = mutableMapOf<String, StableRef<MutableMap<Int, FocusRequester>>>()
    private var lastFocusedRowKey: String? = viewModel.savedFocusRowKey
    // F-4: the submitted query the last card was focused under (Down from the field returns there).
    private var lastCardQuery: String? = null

    val activeRowIndex: State<Int> = derivedStateOf {
        focus.activeRowKey.value
            ?.let { key -> rows.indexOfFirst { it.row.key == key }.takeIf { it >= 0 } } ?: 0
    }
    val isScrolling: State<Boolean> = derivedStateOf { listState.isScrollInProgress }

    // The restored card seeds the hero only under the query it was saved with.
    private val heroSeedQuery = submittedQueryState.value
    private val heroSeed = if (viewModel.hasSavedSearchFocus) viewModel.savedFocusRowKey to viewModel.savedFocusItemIndex else null

    /** The hero's result and its row: the settled card, before any settle the restored card, else the first. */
    private val settled: State<Pair<HeroCarouselRow, ModernCarouselItem>?> = derivedStateOf {
        val rows = rows
        fun HeroCarouselRow.with(item: ModernCarouselItem?) = item?.let { this to it }
        (
            heroSettle.settledKey?.let { key ->
                rows.firstNotNullOfOrNull { r -> r.row.with(r.row.items.list.firstOrNull { it.key == key }) }
            }
                ?: heroSeed?.takeIf { submittedQueryState.value == heroSeedQuery }?.let { (rowKey, index) ->
                    // Past the last card = the See All tile, which sits right after it.
                    rowOf(rowKey)?.row?.let { row -> row.with(row.items.list.let { it.getOrNull(index) ?: it.lastOrNull() }) }
                }
                ?: rows.firstOrNull()?.row?.let { row -> row.with(row.items.list.firstOrNull()) }
            )?.takeUnless { it.second.isPlaceholderCard() }
    }
    val settledItem: State<ModernCarouselItem?> = derivedStateOf { settled.value?.second }

    /** The row holding the hero's result: its card height places the hero (Home's settled row). */
    val settledRow: State<HeroCarouselRow?> = derivedStateOf { settled.value?.first }

    private fun rowOf(rowKey: String?): SearchResultRow? = rows.firstOrNull { it.row.key == rowKey }

    /** Drops the bookkeeping of rows that left the results. */
    fun retainRows(rowKeys: Set<String>) {
        rowListStates.keys.retainAll(rowKeys)
        focusedItemByRow.keys.retainAll(rowKeys)
        rowFocusRequesters.keys.retainAll(rowKeys)
        itemFocusRequestersByRow.keys.retainAll(rowKeys)
    }

    /**
     * The field row took focus: no card is the band's target while it holds focus, so the next
     * pending focus (Down, a submit) always fires, even onto the card that had focus last.
     */
    fun releaseActiveCard() {
        focus.activeRowKey.value = null
    }

    fun savedRowScroll(rowKey: String): Int = viewModel.savedRowScrollPositions[rowKey] ?: 0

    /**
     * F-4, Down from the field row: back to the last card (or See All tile) focused under the
     * current query, scrolled into view first. False when there is none: the caller enters the
     * first result.
     */
    fun restoreLastCard(): Boolean {
        val rowIndex = searchCardRestoreRowIndex(
            lastCardRowKey = lastFocusedRowKey,
            lastCardQuery = lastCardQuery,
            submittedQuery = submittedQueryState.value,
            rowKeys = rows.map { it.row.key }
        )
        val rowKey = lastFocusedRowKey
        val itemIndex = rowKey?.let { focusedItemByRow[it] }
        val restoreRow = rows.getOrNull(rowIndex)
        // Index items.size is the row's See All tile (F-13).
        val lastRestorable = restoreRow?.let {
            if (it.showSeeAll) it.source.items.size else it.source.items.lastIndex
        } ?: -1
        if (rowKey == null || itemIndex == null || itemIndex !in 0..lastRestorable) return false
        scope.launch {
            // The result rows follow the field row and the band lead in order (a card to restore
            // means they are composed).
            val listIndex = RESULT_ROWS_FIRST_ITEM + rowIndex
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == listIndex }) {
                listState.scrollToItem(listIndex)
            }
            focus.requestPendingFocus(rowKey, itemIndex)
        }
        return true
    }

    // Before leaving Search (Details or See All): Back restores this row, its scroll and the
    // focused card (F-13: See All too).
    private fun saveFocus(rowKey: String) {
        lastFocusedRowKey = rowKey
        viewModel.savedFocusRowKey = rowKey
        viewModel.savedFocusItemIndex = focusedItemByRow[rowKey] ?: 0
        viewModel.savedRowScrollPositions = rowListStates.mapValues { it.value.firstVisibleItemIndex }
        viewModel.hasSavedSearchFocus = true
    }

    /**
     * Every card or See All focus gain: the band's active card, ends a pending entry or restore
     * (the screen's part through onCardFocused), and records where Down from the field returns (F-4).
     */
    val onResultFocused: (String, Int, Boolean) -> Unit = { rowKey, index, _ ->
        focus.recordItemFocus(rowKey, index, focusedItemByRow)
        // Any card taking focus ends a pending entry, so it cannot pull focus later.
        focus.clearPendingRowFocus()
        lastFocusedRowKey = rowKey
        lastCardQuery = submittedQueryState.value
        onCardFocusedState.value()
        val item = rowOf(rowKey)?.row?.items?.list?.getOrNull(index)
        val meta = item?.metaPreview
        if (item != null && meta != null && !item.isPlaceholderCard()) {
            // Warms the Details cache; the See All tile never settles the hero (stage 11 rule).
            viewModel.prefetchMetaOnFocus(meta.id, meta.rawType)
            heroSettle.onFocused(item.key)
        }
    }

    val onResultClick: (String, String, String) -> Unit = { id, type, addonBaseUrl ->
        val rowKey = focus.activeRowKey.value
        rowKey?.let(::saveFocus)
        val clickedItem = rowOf(rowKey)?.source?.items?.firstOrNull { it.id == id }
        HeroBackdropState.update(viewModel.getCachedBackdrop(id, type) ?: clickedItem?.backdropUrl)
        onNavigateToDetailState.value(id, type, addonBaseUrl)
    }

    fun openSeeAll(rowKey: String) {
        rowOf(rowKey)?.source?.let { row ->
            saveFocus(rowKey)
            onNavigateToSeeAllState.value(row.catalogId, row.addonId, row.apiType)
        }
    }

    // Placeholder cards (the first search's skeleton rows) have no options, as built.
    val onResultLongPress: (MetaPreview, String) -> Unit = { item, addonBaseUrl ->
        if (!item.id.isPlaceholderItemId()) viewModel.posterOptions.show(item, addonBaseUrl)
    }
}

/**
 * Search's result band over [catalogRows] (the rows with items become the result rows).
 * [submittedQuery] is trimmed. [onCardFocused]: a result took focus (the screen ends its pending
 * entry or restore).
 */
@Composable
internal fun rememberSearchResultsBand(
    viewModel: SearchViewModel,
    listState: LazyListState,
    catalogRows: List<CatalogRow>,
    showAddonName: Boolean,
    showTypeSuffix: Boolean,
    submittedQuery: String,
    onCardFocused: () -> Unit,
    onNavigateToDetail: (String, String, String) -> Unit,
    onNavigateToSeeAll: (catalogId: String, addonId: String, type: String) -> Unit
): SearchResultsBand {
    val context = LocalContext.current
    val isItemWatched = rememberWatchedPredicate(viewModel.watchedMovieIds, viewModel.watchedSeriesIds)
    val visibleCatalogRows = remember(catalogRows) { catalogRows.filter { it.items.isNotEmpty() } }
    // The last build by row key, reused row by row; a settings change starts a new cache.
    val lastResultRows = remember(showAddonName, showTypeSuffix, context) { mutableMapOf<String, SearchResultRow>() }
    val resultRows = remember(visibleCatalogRows, lastResultRows) {
        searchCarouselRows(
            rows = visibleCatalogRows,
            showAddonName = showAddonName,
            showTypeSuffix = showTypeSuffix,
            typeLabel = { localizedContentType(context, it) },
            previous = lastResultRows
        ).also { rows ->
            lastResultRows.clear()
            rows.associateByTo(lastResultRows) { it.row.key }
        }
    }
    val heroSettle = rememberMarqueeHeroSettle(0, listState)
    LaunchedEffect(submittedQuery) { heroSettle.reset() }

    val rowsState = rememberUpdatedState(resultRows)
    val submittedQueryState = rememberUpdatedState(submittedQuery)
    val isItemWatchedState = rememberUpdatedState(isItemWatched)
    val onCardFocusedState = rememberUpdatedState(onCardFocused)
    val onNavigateToDetailState = rememberUpdatedState(onNavigateToDetail)
    val onNavigateToSeeAllState = rememberUpdatedState(onNavigateToSeeAll)
    val scope = rememberCoroutineScope()
    val band = remember {
        SearchResultsBand(
            viewModel = viewModel,
            listState = listState,
            scope = scope,
            heroSettle = heroSettle,
            rowsState = rowsState,
            submittedQueryState = submittedQueryState,
            isItemWatchedState = isItemWatchedState,
            onCardFocusedState = onCardFocusedState,
            onNavigateToDetailState = onNavigateToDetailState,
            onNavigateToSeeAllState = onNavigateToSeeAllState
        )
    }
    // Clean up stale keys when the catalog rows change.
    val rowKeys = remember(catalogRows) { catalogRows.mapTo(mutableSetOf()) { it.stableKey() } }
    LaunchedEffect(rowKeys) { band.retainRows(rowKeys) }
    return band
}

/**
 * The band's list items after SearchScreen's field row: the lead spacer ([leadHeight] puts row 1
 * at Home's band anchor), the result rows, then a placeholder row while [showLoadingMore] (results
 * are up but more catalogs are still answering, as on mobile), then the search-on-Enter catalogs
 * the live run left out ([waits], Spec 40 D3). The waiting lines follow the rows, as the approved
 * stage 3 draws them, rather than sitting between rows: interleaving would shift the row list
 * indices [SearchResultsBand.restoreLastCard] scrolls to.
 */
internal fun LazyListScope.searchResultItems(
    band: SearchResultsBand,
    leadHeight: Dp,
    useLandscapePosters: Boolean,
    accent: () -> Color,
    defaultBringIntoViewSpec: BringIntoViewSpec,
    showLoadingMore: Boolean,
    waits: List<SearchOnEnterWait>
) {
    item(key = "search_band_lead") { Spacer(modifier = Modifier.height(leadHeight)) }
    itemsIndexed(
        items = band.rows,
        key = { index, result -> "${result.row.key}_$index" },
        contentType = { _, _ -> "search_result_row" }
    ) { rowIndex, result ->
        SearchResultRowItem(band, result, rowIndex, useLandscapePosters, accent, defaultBringIntoViewSpec)
    }
    if (showLoadingMore) {
        item(key = "search_loading_more") { MarqueeSkeletonRow(landscape = useLandscapePosters) }
    }
    itemsIndexed(
        items = waits,
        key = { index, _ -> "search_on_enter_wait_$index" },
        contentType = { _, _ -> "search_on_enter_wait" }
    ) { _, wait -> SearchOnEnterWaitLine(wait) }
}

/**
 * Spec 40 D3 (`.l40hint`): a search-on-Enter catalog waiting for a submit. Catalog name in the
 * row-title style, then the addon and "runs when you press" muted, then the SEARCH key chip. Not
 * focusable: it is a note, and focus stays on the rows above.
 */
@Composable
private fun SearchOnEnterWaitLine(wait: SearchOnEnterWait) {
    val marquee = NuvioTheme.marquee
    Row(
        modifier = Modifier
            .padding(horizontal = NuvioTheme.layout.tvSafeHorizontal)
            .padding(top = NuvioTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = wait.title,
            style = NuvioTheme.textStyles.rowTitle,
            color = marquee.onImage2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Text(
            text = searchOnEnterWaitingText(wait),
            style = NuvioTheme.textStyles.meta,
            color = marquee.onImage3,
            maxLines = 1
        )
        CollectionBadge(text = stringResource(R.string.search_on_enter_key), color = NuvioTheme.colors.TextPrimary)
    }
}

/** The muted part of a waiting line: the addon (unless it is already the title) and "runs when you press". */
@Composable
internal fun searchOnEnterWaitingText(wait: SearchOnEnterWait): String =
    if (wait.catalogName.isBlank()) {
        stringResource(R.string.search_on_enter_waiting_no_addon)
    } else {
        stringResource(R.string.search_on_enter_waiting, wait.addonName)
    }

@Composable
private fun SearchResultRowItem(
    band: SearchResultsBand,
    result: SearchResultRow,
    rowIndex: Int,
    useLandscapePosters: Boolean,
    accent: () -> Color,
    defaultBringIntoViewSpec: BringIntoViewSpec
) {
    val rowKey = result.row.key
    val seeAllLabel = stringResource(R.string.action_see_all)
    val seeAllTile = remember(rowKey, seeAllLabel) {
        MarqueeRowTile(seeAllLabel, Icons.AutoMirrored.Filled.ArrowForward) { band.openSeeAll(rowKey) }
    }
    MarqueeRowSection(
        row = result.row,
        rowIndex = rowIndex,
        activeRowIndex = band.activeRowIndex,
        isActiveRow = remember(rowKey) { { band.focus.activeRowKey.value == rowKey } },
        rowFocusRequester = band.rowFocusRequesters.getOrPut(rowKey) { FocusRequester() },
        itemFocusRequesters = band.itemFocusRequestersByRow.getOrPut(rowKey) { StableRef(mutableMapOf()) },
        defaultBringIntoViewSpec = defaultBringIntoViewSpec,
        focusStateCatalogRowScrollIndex = band.savedRowScroll(rowKey),
        focusedItemByRow = band.focusedItemByRowRef,
        rowListStates = band.rowListStatesRef,
        loadMoreRequestedTotals = band.noLoadMoreTotals,
        isVerticalRowsScrollingState = band.isScrolling,
        focus = band.focus,
        onRowItemFocused = band.onResultFocused,
        useLandscapePosters = useLandscapePosters,
        accent = accent,
        isCatalogItemWatched = band.isItemWatched,
        onCatalogItemLongPress = band.onResultLongPress,
        onNavigateToDetail = band.onResultClick,
        trailingTile = seeAllTile.takeIf { result.showSeeAll }
    )
}
