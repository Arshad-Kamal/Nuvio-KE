package com.nuvio.tv.ui.screens.library

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.screens.home.ModernCarouselItem
import com.nuvio.tv.ui.screens.home.ModernPayload
import com.nuvio.tv.ui.screens.search.DiscoverGrid
import com.nuvio.tv.ui.screens.search.DiscoverGridAction
import com.nuvio.tv.ui.screens.search.DiscoverGridRow
import com.nuvio.tv.ui.screens.search.MarqueeHeroSettle
import com.nuvio.tv.ui.screens.search.focusGridCell
import com.nuvio.tv.ui.screens.search.gridForm
import com.nuvio.tv.ui.screens.search.rememberMarqueeHeroSettle
import com.nuvio.tv.ui.util.StableRef
import com.nuvio.tv.ui.util.asStable

// Library's Sort & filter grid (approved sandbox `u7-library-marquee` stages 4, 6, 9): Discover's
// grid rows (DiscoverGridRow: cards packed into lines by the grid's form) in place of the shelves, in the
// same list after the tab row and the band lead. LibraryScreen draws the fixed row title and the
// "Nothing matches" block.

/** The grid's row key; card keys hold across filter changes, so a settled title still shown stays. */
internal const val LIBRARY_GRID_KEY = "library_grid"

/** The list index of grid row 0: the tab row and the band lead come first. */
internal const val LIBRARY_FIRST_GRID_ITEM = 2

/** The grid's cards (no action tile) and their titles by card key (the hero's Added date, the click hand-off). */
@Immutable
internal class LibraryGridContent(val grid: DiscoverGrid, val entries: Map<String, LibraryEntry>)

/** [entries] as the grid, in the [gridForm] of its cards under the Card shape setting [landscapeSetting] (Spec 41). */
internal fun libraryGridContent(entries: List<LibraryEntry>, landscapeSetting: Boolean): LibraryGridContent {
    val byKey = LinkedHashMap<String, LibraryEntry>(entries.size)
    val items = entries.map { entry -> libraryCard(LIBRARY_GRID_KEY, entry).also { byKey[it.key] = entry } }
    val grid = DiscoverGrid(
        items = items.asStable(),
        action = DiscoverGridAction.None,
        addonBaseUrl = "",
        form = items.gridForm(landscapeSetting)
    )
    return LibraryGridContent(grid, byKey)
}

/**
 * The grid's state: the active row (rows above it hide), the focused card, the hero's settled
 * card, focus moves and the card callbacks (stable: the cards capture them once). [listState] is
 * the Library list's; the grid's rows start at [LIBRARY_FIRST_GRID_ITEM].
 */
@Stable
internal class LibraryGridState(
    private val viewModel: LibraryViewModel,
    private val listState: LazyListState,
    private val settle: MarqueeHeroSettle,
    private val contentState: State<LibraryGridContent>,
    private val onCardFocusedState: State<() -> Unit>,
    private val onNavigateToDetailState: State<(String, String, String?) -> Unit>,
    initialQueryKey: List<Any?>
) {
    val content: LibraryGridContent get() = contentState.value
    val grid: DiscoverGrid get() = content.grid

    // Read in draw (row alpha) and in callbacks only.
    val activeRow = mutableIntStateOf(0)
    val requesters = StableRef(mutableMapOf<Int, FocusRequester>())

    private var queryKey = initialQueryKey
    private var focusedCell = 0
    // The card holding focus; null while focus is outside the grid.
    private var focusedKey: String? = null
    // A See all tile opened the grid: its first card takes focus once shown.
    private var focusFirstOnShow = false
    // Back from Details on a grid card: it seeds the hero until a card settles.
    private var heroSeed: Int? =
        if (viewModel.hasSavedFocus && viewModel.savedFocusRowKey == LIBRARY_GRID_KEY) viewModel.savedFocusItemIndex else null

    private val settled: State<ModernCarouselItem?> = derivedStateOf {
        val items = grid.items.list
        settle.settledKey?.let { key -> items.firstOrNull { it.key == key } }
            ?: heroSeed?.let { items.getOrNull(it) ?: items.lastOrNull() }
    }

    /**
     * The hero's card: the settled one while the grid shows it, else null (the screen keeps the
     * shelves' last title, so opening the grid does not swap the backdrop, stage 4).
     */
    val settledItem: ModernCarouselItem? get() = settled.value
    val settledEntry: LibraryEntry? get() = settled.value?.let { content.entries[it.key] }

    /** A See all tile (stage 4): the grid lists that shelf, its first card focused. */
    val openSeeAll: (String) -> Unit = { shelfKey ->
        focusFirstOnShow = true
        viewModel.onOpenSeeAll(shelfKey)
    }

    /**
     * The grid lists [key]'s titles ([shown]: it is on screen). A new list starts at the top with
     * no row hidden; a pending See all focuses its first card. While hidden the hero forgets the
     * grid's title, so the next opening keeps the shelves' last one (stage 4).
     */
    suspend fun onQuery(key: List<Any?>, shown: Boolean) {
        if (!shown) {
            settle.reset()
            heroSeed = null
        }
        if (key != queryKey) {
            queryKey = key
            activeRow.intValue = 0
            focusedCell = 0
            if (shown) listState.scrollToItem(0)
        }
        if (focusFirstOnShow && shown) {
            focusFirstOnShow = false
            if (grid.items.isNotEmpty()) focusCell(0)
        }
    }

    /** Focus left the grid (the tab row took it): no card is the grid's to restore. */
    fun releaseFocusedCard() {
        focusedKey = null
    }

    /**
     * After the grid changed: when the focused card left it (marked watched under a Watched
     * filter, removed) focus takes the card now at its cell. False when the grid is gone or empty
     * (the caller focuses the pill).
     */
    suspend fun refocusIfCardLeft(shown: Boolean): Boolean {
        val key = focusedKey ?: return true
        if (shown && grid.items.list.any { it.key == key }) return true
        focusedKey = null
        if (!shown || grid.items.isEmpty()) return false
        focusCell(focusedCell.coerceAtMost(grid.items.size - 1))
        return true
    }

    /** Focuses [cell], scrolling its row into the list first when it is not laid out. */
    suspend fun focusCell(cell: Int) {
        focusGridCell(listState, LIBRARY_FIRST_GRID_ITEM, grid, requesters, cell)
    }

    /** Back from the grid (stage 6): the rows show from the top again; the caller focuses the pill. */
    suspend fun backToTop() {
        activeRow.intValue = 0
        listState.scrollToItem(0)
    }

    val onItemFocused: (Int, ModernCarouselItem) -> Unit = { cell, item ->
        activeRow.intValue = grid.rowOf(cell)
        focusedCell = cell
        focusedKey = item.key
        heroSeed = null
        onCardFocusedState.value()
        settle.onFocused(item.key)
        // Warms the Details cache.
        item.metaPreview?.let { viewModel.prefetchMetaOnFocus(it.id, it.rawType) }
    }

    val onItemClick: (ModernCarouselItem) -> Unit = { item ->
        val payload = item.payload as? ModernPayload.Catalog
        if (payload != null) {
            // Back from Details restores this card.
            viewModel.saveFocus(LIBRARY_GRID_KEY, focusedCell, rowScrollPositions = emptyMap())
            viewModel.openDetail(
                payload.itemId,
                payload.itemType,
                payload.addonBaseUrl,
                content.entries[item.key]?.background,
                onNavigateToDetailState.value
            )
        }
    }

    val onItemLongPress: (ModernCarouselItem) -> Unit = { item ->
        item.metaPreview?.let { viewModel.showPosterOptions(it, (item.payload as? ModernPayload.Catalog)?.addonBaseUrl) }
    }
}

/**
 * The grid over [content]; [queryKey] names what it lists (a change starts it at the top);
 * [onCardFocused]: a card took focus (the screen ends its pending restore).
 */
@Composable
internal fun rememberLibraryGridState(
    viewModel: LibraryViewModel,
    listState: LazyListState,
    content: LibraryGridContent,
    queryKey: List<Any?>,
    onCardFocused: () -> Unit,
    onNavigateToDetail: (String, String, String?) -> Unit
): LibraryGridState {
    val settle = rememberMarqueeHeroSettle(0, listState)
    val contentState = rememberUpdatedState(content)
    val onCardFocusedState = rememberUpdatedState(onCardFocused)
    val onNavigateToDetailState = rememberUpdatedState(onNavigateToDetail)
    return remember {
        LibraryGridState(viewModel, listState, settle, contentState, onCardFocusedState, onNavigateToDetailState, queryKey)
    }
}

/** The grid rows as list items after the tab row and the band lead. */
internal fun LazyListScope.libraryGridItems(
    state: LibraryGridState,
    accent: () -> Color,
    isItemWatched: (MetaPreview) -> Boolean
) {
    val grid = state.grid
    items(
        count = grid.rowCount,
        key = { "library_grid_row_$it" },
        contentType = { "library_grid_row" }
    ) { rowIndex ->
        DiscoverGridRow(
            rowIndex = rowIndex,
            grid = grid,
            activeRow = state.activeRow,
            requesters = state.requesters,
            accent = accent,
            isWatched = isItemWatched,
            onItemFocused = state.onItemFocused,
            onActionFocused = {},
            onItemClick = state.onItemClick,
            onItemLongPress = state.onItemLongPress,
            onAction = {}
        )
    }
}
