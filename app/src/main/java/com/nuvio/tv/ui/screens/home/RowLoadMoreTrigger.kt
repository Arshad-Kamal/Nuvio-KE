package com.nuvio.tv.ui.screens.home

// Spec 117 P5b paging (D24, D26 rev 13): when a Marquee row asks for its next page. Shared by
// every MarqueeRowSection host that pages (Home, folders, TMDB entity pages). Keyed on the row
// model's page and drawn count, never the layout's item count, so a page that adds no drawn card
// (all duplicates, unreleased, or hidden by content filters) still moves the key and the next
// page loads; bounded to ROW_LOAD_MORE_MAX_FIRES pages per user move, so a row never pages on
// its own without end.

/** Fires when the last laid-out item is at most this far from the drawn end. */
internal const val ROW_LOAD_MORE_END_DISTANCE = 4

/** Distinct pages fired at between resets (focus entry, card move, reload). */
internal const val ROW_LOAD_MORE_MAX_FIRES = 3

/** Everything one decision reads, taken in one snapshot. */
internal data class RowLoadMoreInputs(
    /** The last laid-out item's index; null while nothing is laid out (never fires). */
    val lastVisibleIndex: Int?,
    /** Drawn focus targets: the cards plus a trailing tile. */
    val drawnCount: Int,
    val currentPage: Int,
    val hasMore: Boolean,
    val isLoading: Boolean,
    val isActiveRow: Boolean,
    /** The row's remembered focused card; null before any. */
    val focusedIndex: Int?
)

/** The (drawn count, page) a fire was made at. */
internal data class RowLoadMoreKey(val drawnCount: Int, val page: Int)

/**
 * Per-row memory kept by the host across effect restarts (a dropped row above shifts the row's
 * LazyColumn key): the `seen` fields make a restart with unchanged inputs no reset.
 */
internal data class RowLoadMoreEntry(
    /** Distinct pages fired at since the last reset. */
    val firedPages: Set<Int> = emptySet(),
    /** The last fire's key; null once the user is away from the end or after a reset. */
    val firedKey: RowLoadMoreKey? = null,
    val seenActive: Boolean = false,
    val seenFocusedIndex: Int? = null,
    val seenPage: Int? = null
)

internal data class RowLoadMoreDecision(val entry: RowLoadMoreEntry, val fire: Boolean)

/**
 * Resets (fire set and key cleared) when focus enters the row, when its focused card changes (a
 * user move, so after a load-more error a move is the retry), and when the page decreases (a
 * reload). Fires only for the focused, laid-out row within [ROW_LOAD_MORE_END_DISTANCE] of its
 * drawn end, with more to load, not loading, at a key other than the last fire's, and while
 * fewer than [ROW_LOAD_MORE_MAX_FIRES] pages were fired at; a re-fire at a page already in the
 * set adds nothing to it.
 */
internal fun decideRowLoadMore(inputs: RowLoadMoreInputs, previous: RowLoadMoreEntry?): RowLoadMoreDecision {
    val prev = previous ?: RowLoadMoreEntry()
    val focusEntered = inputs.isActiveRow && !prev.seenActive
    val cardMoved = inputs.focusedIndex != prev.seenFocusedIndex
    val reloaded = prev.seenPage != null && inputs.currentPage < prev.seenPage
    val entry = (if (focusEntered || cardMoved || reloaded) RowLoadMoreEntry() else prev).copy(
        seenActive = inputs.isActiveRow,
        seenFocusedIndex = inputs.focusedIndex,
        seenPage = inputs.currentPage
    )
    val lastVisible = inputs.lastVisibleIndex ?: return RowLoadMoreDecision(entry, fire = false)
    if (lastVisible < inputs.drawnCount - ROW_LOAD_MORE_END_DISTANCE) {
        return RowLoadMoreDecision(entry.copy(firedKey = null), fire = false)
    }
    val key = RowLoadMoreKey(inputs.drawnCount, inputs.currentPage)
    val fire = inputs.isActiveRow && inputs.hasMore && !inputs.isLoading &&
        entry.firedKey != key && entry.firedPages.size < ROW_LOAD_MORE_MAX_FIRES
    if (!fire) return RowLoadMoreDecision(entry, fire = false)
    return RowLoadMoreDecision(entry.copy(firedKey = key, firedPages = entry.firedPages + inputs.currentPage), fire = true)
}
