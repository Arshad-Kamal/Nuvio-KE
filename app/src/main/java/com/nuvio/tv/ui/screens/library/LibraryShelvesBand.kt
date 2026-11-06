@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.nuvio.tv.ui.screens.library

import android.content.Context
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.marquee.FACT_SEPARATOR
import com.nuvio.tv.ui.screens.detail.formatDayMonth
import com.nuvio.tv.ui.screens.detail.formatMonth
import com.nuvio.tv.ui.screens.detail.formatTimeOfDay
import com.nuvio.tv.ui.screens.detail.formatWeekdayTime
import com.nuvio.tv.ui.screens.home.HeroCarouselRow
import com.nuvio.tv.ui.screens.home.MarqueeBandFocus
import com.nuvio.tv.ui.screens.home.MarqueeRowSection
import com.nuvio.tv.ui.screens.home.MarqueeRowTile
import com.nuvio.tv.ui.screens.home.ModernCarouselItem
import com.nuvio.tv.ui.screens.home.catalogCarouselItem
import com.nuvio.tv.ui.screens.home.isWatchedTitle
import com.nuvio.tv.ui.screens.search.MarqueeHeroSettle
import com.nuvio.tv.ui.screens.search.rememberMarqueeHeroSettle
import com.nuvio.tv.ui.util.StableRef
import com.nuvio.tv.ui.util.asStable
import com.nuvio.tv.ui.util.localizedGenreLabel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

// Library's shelf band (approved sandbox `u7-library-marquee` stages 1-3, and History's rows,
// stage 7): Marquee rows in the Card shape under the tab row, on Search's band pattern
// (SearchResultsBand) without its query state. LibraryScreen keeps the tab row, the hero and the
// states.

/**
 * A shelf as a Marquee row; [entries] by card key (the hero's Added date, the click hand-off).
 * [restoreKeys] by card key: History's Hidden row, whose OK restores those dismissals (stage 8).
 */
@Immutable
internal data class LibraryShelfRow(
    val row: HeroCarouselRow,
    val entries: Map<String, LibraryEntry>,
    val restoreKeys: Map<String, List<String>> = emptyMap()
) {
    val isHidden: Boolean get() = restoreKeys.isNotEmpty()
}

/**
 * [shelves] as Marquee rows: the shelf title with its count as the row's small suffix (Recently
 * added: "N this month"), one card per title keyed by shelf + title. Cards build vertical; the band
 * applies the Card shape row rule.
 */
internal fun libraryShelfRows(context: Context, shelves: List<LibraryShelf>): List<LibraryShelfRow> =
    shelves.mapIndexed { index, shelf ->
        val count = shelf.entries.size
        val title = when (shelf.kind) {
            LibraryShelfKind.RECENTLY_ADDED -> context.getString(R.string.library_shelf_recently_added)
            LibraryShelfKind.NOT_WATCHED -> context.getString(R.string.library_shelf_not_watched)
            LibraryShelfKind.MOVIES -> context.getString(R.string.type_movies)
            LibraryShelfKind.SERIES -> context.getString(R.string.type_series_plural)
            LibraryShelfKind.GENRE -> localizedGenreLabel(context, shelf.genre.orEmpty())
            LibraryShelfKind.WATCHED -> context.getString(R.string.library_shelf_watched)
        }
        val suffix = if (shelf.kind == LibraryShelfKind.RECENTLY_ADDED) {
            context.getString(R.string.library_shelf_recent_count, count)
        } else {
            count.toString()
        }
        val entries = LinkedHashMap<String, LibraryEntry>(count)
        val items = shelf.entries.map { entry -> libraryCard(shelf.key, entry).also { entries[it.key] = entry } }
        LibraryShelfRow(
            row = HeroCarouselRow(
                key = shelf.key,
                title = title,
                titleSuffix = suffix,
                globalRowIndex = index,
                items = items.asStable()
            ),
            entries = entries
        )
    }

/**
 * History [groups] as band rows (stage 7): Today, This week or the month (its year when not this
 * year) with the title count; one card per title, its when line as the subtitle (the card
 * caption), the latest episode as the chip and the bar while unfinished. The band shows them
 * landscape. A title in the library takes its library entry (the hero's plot, Added date and
 * addon); a title only in the history opens Details without an addon (the progress keeps the
 * stream's addon, not the meta's).
 */
internal fun libraryHistoryRows(
    context: Context,
    groups: List<LibraryHistoryGroup>,
    hidden: List<LibraryHiddenNextUp>,
    library: List<LibraryEntry>,
    zone: ZoneId
): List<LibraryShelfRow> {
    val libraryById = library.associateBy { it.id }
    fun entryOf(watched: LibraryHistoryEntry): LibraryEntry =
        libraryById[watched.id]?.let { it.copy(background = it.background ?: watched.backdrop) } ?: watched.toLibraryEntry()
    val thisYear = LocalDate.now(zone).year
    val rows = groups.mapIndexed { index, group ->
        val count = group.entries.size
        val title = when (group.kind) {
            LibraryHistoryGroupKind.TODAY -> context.getString(R.string.library_history_today)
            LibraryHistoryGroupKind.THIS_WEEK -> context.getString(R.string.library_history_this_week)
            LibraryHistoryGroupKind.MONTH -> group.month?.let { formatMonth(it, withYear = it.year != thisYear) }.orEmpty()
        }
        val entries = LinkedHashMap<String, LibraryEntry>(count)
        val items = group.entries.map { watched ->
            val entry = entryOf(watched)
            val episode = watched.episodeLabel(context)
            libraryCard(group.key, entry).copy(
                subtitle = historyWhenLine(context, watched, episode, group.kind, zone),
                cornerLabel = episode,
                progressFraction = watched.progressFraction
            ).also { entries[it.key] = entry }
        }
        LibraryShelfRow(
            row = HeroCarouselRow(
                key = group.key,
                title = title,
                titleSuffix = context.resources.getQuantityString(R.plurals.library_title_count, count, count),
                globalRowIndex = index,
                items = items.asStable()
            ),
            entries = entries
        )
    }
    if (hidden.isEmpty()) return rows
    return rows + libraryHiddenRow(context, hidden, rows.size, ::entryOf)
}

/**
 * The Hidden row (stage 8): "Hidden from Continue Watching", the count and "OK restores"; one
 * dimmed card per title, captioned "Next Up after S1 E2" (the stored seed episode).
 */
private fun libraryHiddenRow(
    context: Context,
    hidden: List<LibraryHiddenNextUp>,
    index: Int,
    entryOf: (LibraryHistoryEntry) -> LibraryEntry
): LibraryShelfRow {
    val entries = LinkedHashMap<String, LibraryEntry>(hidden.size)
    val restoreKeys = HashMap<String, List<String>>(hidden.size)
    val items = hidden.map { card ->
        val entry = entryOf(card.entry)
        val seed = if (card.seedSeason != null && card.seedEpisode != null) {
            context.getString(R.string.season_episode_format, card.seedSeason, card.seedEpisode)
        } else null
        libraryCard(HISTORY_HIDDEN_KEY, entry).copy(
            subtitle = seed?.let { context.getString(R.string.library_hidden_next_up_after, it) }
                ?: context.getString(R.string.library_hidden_next_up)
        ).also {
            entries[it.key] = entry
            restoreKeys[it.key] = card.keys
        }
    }
    return LibraryShelfRow(
        row = HeroCarouselRow(
            key = HISTORY_HIDDEN_KEY,
            title = context.getString(R.string.library_hidden_title),
            titleSuffix = listOf(hidden.size.toString(), context.getString(R.string.library_hidden_hint))
                .joinToString(FACT_SEPARATOR),
            globalRowIndex = index,
            items = items.asStable()
        ),
        entries = entries,
        restoreKeys = restoreKeys
    )
}

private fun LibraryHistoryEntry.toLibraryEntry() = LibraryEntry(
    id = id,
    type = type,
    name = name,
    poster = poster,
    background = backdrop,
    logo = logo,
    description = null,
    releaseInfo = null,
    imdbRating = null,
    genres = emptyList(),
    addonBaseUrl = null
)

/** "S1 E2" for the latest episode (series); shared by the History card and the hero eyebrow. */
internal fun LibraryHistoryEntry.episodeLabel(context: Context): String? =
    if (season != null && episode != null) context.getString(R.string.season_episode_format, season, episode) else null

/** "35%" while the latest play is unfinished; shared by the History card and the hero eyebrow. */
internal fun LibraryHistoryEntry.playedLabel(context: Context): String? =
    progressFraction?.let { context.getString(R.string.library_history_percent, (it * 100).roundToInt()) }

/**
 * When a title was watched (stage 7): "S1 E2 · Tue 20:02", "35% · Mon 22:40", else "Watched Sun
 * 19:15"; the time as its row reads (today: the time, this week: weekday + time, else the date).
 */
private fun historyWhenLine(
    context: Context,
    watched: LibraryHistoryEntry,
    episode: String?,
    kind: LibraryHistoryGroupKind,
    zone: ZoneId
): String {
    val time = Instant.ofEpochMilli(watched.watchedAt).atZone(zone).toLocalDateTime()
    val at = when (kind) {
        LibraryHistoryGroupKind.TODAY -> formatTimeOfDay(time)
        LibraryHistoryGroupKind.THIS_WEEK -> formatWeekdayTime(time)
        LibraryHistoryGroupKind.MONTH -> formatDayMonth(time.toLocalDate())
    }
    val facts = listOfNotNull(episode, watched.playedLabel(context))
    return if (facts.isEmpty()) context.getString(R.string.library_history_watched_when, at)
    else (facts + at).joinToString(FACT_SEPARATOR)
}

/** [entry] as a card of row [rowKey], keyed by row + title; built vertical (the row or grid applies the Card shape). */
internal fun libraryCard(rowKey: String, entry: LibraryEntry): ModernCarouselItem =
    catalogCarouselItem(
        item = entry.toMetaPreview(),
        rowKey = rowKey,
        addonBaseUrl = entry.addonBaseUrl.orEmpty(),
        useLandscapePosters = false,
        occurrence = 0
    ).copy(key = "$rowKey|${entry.typeKey()}:${entry.id}")

/** A shelf with this many titles ends in a See all tile (u7 stage 4; Search's rule). */
internal const val LIBRARY_SEE_ALL_MIN_TITLES = 15

/**
 * The shelf band's state: the rows, their focus bookkeeping (on [focus]'s pending focus), the
 * hero's settled card and the card callbacks. The callbacks are stable: the rows' cards capture
 * them once.
 */
@Stable
internal class LibraryShelvesBand(
    private val viewModel: LibraryViewModel,
    listState: LazyListState,
    shownTypeKey: String,
    private val heroSettle: MarqueeHeroSettle,
    private val rowsState: State<List<LibraryShelfRow>>,
    private val onCardFocusedState: State<() -> Unit>,
    private val onNavigateToDetailState: State<(String, String, String?) -> Unit>,
    private val onRestoreState: State<(List<String>) -> Unit>
) {
    val rows: List<LibraryShelfRow> get() = rowsState.value

    val focus = MarqueeBandFocus()
    private val focusedItemByRow = mutableStateMapOf<String, Int>()
    private val rowListStates = mutableStateMapOf<String, LazyListState>()
    val focusedItemByRowRef = StableRef<MutableMap<String, Int>>(focusedItemByRow)
    val rowListStatesRef = StableRef<MutableMap<String, LazyListState>>(rowListStates)
    // Shelves never load more; MarqueeRowSection still takes the map.
    val noLoadMoreTotals = StableRef<MutableMap<String, Int>>(mutableMapOf())
    val rowFocusRequesters = mutableMapOf<String, FocusRequester>()
    val itemFocusRequestersByRow = mutableMapOf<String, StableRef<MutableMap<Int, FocusRequester>>>()

    val activeRowIndex: State<Int> = derivedStateOf {
        focus.activeRowKey.value
            ?.let { key -> rows.indexOfFirst { it.row.key == key }.takeIf { it >= 0 } } ?: 0
    }
    val isScrolling: State<Boolean> = derivedStateOf { listState.isScrollInProgress }

    // Back from Details: the restored card seeds the hero until the first settle, on its own tab only.
    private var heroSeed = if (viewModel.hasSavedFocus) viewModel.savedFocusRowKey to viewModel.savedFocusItemIndex else null
    private var shownTypeKey = shownTypeKey
    // The card holding focus (row key, card key); null while focus is outside the shelves.
    private var focusedCard: Pair<String, String>? = null
    // The focused card's row position, for a row that leaves (the Hidden row's last restore).
    private var focusedRowIndex = 0

    /** The hero's card and its row: the settled card, before any settle the restored card, else the first. */
    private val settled: State<Pair<LibraryShelfRow, ModernCarouselItem>?> = derivedStateOf {
        val rows = rows
        heroSettle.settledKey?.let { key ->
            rows.firstNotNullOfOrNull { r -> r.row.items.list.firstOrNull { it.key == key }?.let { r to it } }
        }
            ?: heroSeed?.let { (rowKey, index) ->
                rowOf(rowKey)?.let { r -> r.row.items.list.let { it.getOrNull(index) ?: it.lastOrNull() }?.let { r to it } }
            }
            ?: rows.firstOrNull()?.let { r -> r.row.items.list.firstOrNull()?.let { r to it } }
    }
    val settledItem: State<ModernCarouselItem?> = derivedStateOf { settled.value?.second }
    val settledEntry: State<LibraryEntry?> = derivedStateOf { settled.value?.let { (r, item) -> r.entries[item.key] } }

    /** The row holding the hero's card: its card height places the hero (Home's settled row). */
    val settledRow: State<HeroCarouselRow?> = derivedStateOf { settled.value?.first?.row }

    private fun rowOf(rowKey: String?): LibraryShelfRow? = rows.firstOrNull { it.row.key == rowKey }

    /** Drops the bookkeeping of shelves that left (another tab, a shelf that emptied). */
    fun retainRows(rowKeys: Set<String>) {
        if (focus.activeRowKey.value !in rowKeys) focus.activeRowKey.value = null
        rowListStates.keys.retainAll(rowKeys)
        focusedItemByRow.keys.retainAll(rowKeys)
        rowFocusRequesters.keys.retainAll(rowKeys)
        itemFocusRequestersByRow.keys.retainAll(rowKeys)
    }

    /** The tab shown: a new one starts the hero again from its first shelf. */
    fun onTabShown(typeKey: String) {
        if (typeKey == shownTypeKey) return
        shownTypeKey = typeKey
        heroSeed = null
        heroSettle.reset()
    }

    /**
     * The tab row took focus: no card is the band's target, so a shelf that comes back (another
     * tab, then this one) never pulls focus down, and the next entry goes through the row's restorer.
     */
    fun releaseFocusedCard() {
        focusedCard = null
        focus.activeRowKey.value = null
    }

    /**
     * After the rows changed: when the focused card left its row (marked watched, removed from the
     * library or the history, restored from Hidden, the watched sets arriving), focus goes to the
     * card now at its place, else to the first card of the row now at the row's place (the one
     * above when it was the last). False when there is no row left (the caller focuses the tab).
     */
    fun refocusIfCardLeft(): Boolean {
        val (rowKey, cardKey) = focusedCard ?: return true
        val row = rowOf(rowKey)?.row
        if (row != null && row.items.list.any { it.key == cardKey }) return true
        val target = row ?: rows.getOrNull(focusedRowIndex.coerceAtMost(rows.lastIndex))?.row ?: return false
        val index = if (row != null) (focusedItemByRow[rowKey] ?: 0).coerceIn(0, row.items.list.lastIndex) else 0
        focusedCard = null
        focus.requestPendingFocus(target.key, index)
        return true
    }

    fun savedRowScroll(rowKey: String): Int = viewModel.savedRowScrollPositions[rowKey] ?: 0

    val onCardFocused: (String, Int, Boolean) -> Unit = { rowKey, index, _ ->
        focus.recordItemFocus(rowKey, index, focusedItemByRow)
        // Any card taking focus ends a pending entry, so it cannot pull focus later.
        focus.clearPendingRowFocus()
        onCardFocusedState.value()
        val item = rowOf(rowKey)?.row?.items?.list?.getOrNull(index)
        val meta = item?.metaPreview
        focusedCard = item?.let { rowKey to it.key }
        focusedRowIndex = rows.indexOfFirst { it.row.key == rowKey }.coerceAtLeast(0)
        if (item != null && meta != null) {
            // Warms the Details cache.
            viewModel.prefetchMetaOnFocus(meta.id, meta.rawType)
            heroSettle.onFocused(item.key)
        }
    }

    val onCardClick: (String, String, String) -> Unit = { id, type, addonBaseUrl ->
        val rowKey = focus.activeRowKey.value
        val shelf = rowOf(rowKey)
        val restoreKeys = focusedCard?.second?.let { cardKey -> shelf?.restoreKeys?.get(cardKey) }
        if (restoreKeys != null) {
            // A Hidden card (stage 8): OK restores it; focus takes the card now at its place.
            onRestoreState.value(restoreKeys)
        } else {
            openDetail(rowKey, shelf, id, type, addonBaseUrl)
        }
    }

    private fun openDetail(rowKey: String?, shelf: LibraryShelfRow?, id: String, type: String, addonBaseUrl: String) {
        // Back restores this shelf, its scroll and the focused card.
        if (rowKey != null) {
            viewModel.saveFocus(rowKey, focusedItemByRow[rowKey] ?: 0, rowListStates.mapValues { it.value.firstVisibleItemIndex })
        }
        val entry = shelf?.entries?.values?.firstOrNull { it.id == id }
        viewModel.openDetail(id, type, addonBaseUrl, entry?.background, onNavigateToDetailState.value)
    }

    val onCardLongPress: (MetaPreview, String) -> Unit = viewModel::showPosterOptions
}

/**
 * The band over [rows]; [onCardFocused]: a card took focus (the screen ends its pending restore);
 * [onRestore]: a Hidden card's OK, with its dismissal keys.
 */
@Composable
internal fun rememberLibraryShelvesBand(
    viewModel: LibraryViewModel,
    listState: LazyListState,
    rows: List<LibraryShelfRow>,
    shownTypeKey: String,
    onCardFocused: () -> Unit,
    onNavigateToDetail: (String, String, String?) -> Unit,
    onRestore: (List<String>) -> Unit
): LibraryShelvesBand {
    val heroSettle = rememberMarqueeHeroSettle(0, listState)
    val rowsState = rememberUpdatedState(rows)
    val onCardFocusedState = rememberUpdatedState(onCardFocused)
    val onNavigateToDetailState = rememberUpdatedState(onNavigateToDetail)
    val onRestoreState = rememberUpdatedState(onRestore)
    val band = remember {
        LibraryShelvesBand(
            viewModel, listState, shownTypeKey, heroSettle, rowsState, onCardFocusedState, onNavigateToDetailState, onRestoreState
        )
    }
    val rowKeys = remember(rows) { rows.mapTo(mutableSetOf()) { it.row.key } }
    LaunchedEffect(rowKeys) { band.retainRows(rowKeys) }
    return band
}

/** Whether a card shows as watched (the tick); the shelves use the same [isWatchedTitle]. */
@Composable
internal fun rememberLibraryWatchedPredicate(
    watchedMovieIds: Set<String>,
    watchedSeriesIds: Set<String>
): (MetaPreview) -> Boolean = remember(watchedMovieIds, watchedSeriesIds) {
    { item: MetaPreview -> isWatchedTitle(item.id, item.apiType, watchedMovieIds, watchedSeriesIds) }
}

/**
 * The band's rows as list items after the tab row and the band lead. Shelves: a long shelf ends in
 * a See all tile whose OK calls [onSeeAll] with the shelf key. History ([onSeeAll] null): no tile,
 * captioned cards ([cardCaptions]).
 */
internal fun LazyListScope.libraryShelfItems(
    band: LibraryShelvesBand,
    useLandscapePosters: Boolean,
    accent: () -> Color,
    isItemWatched: (MetaPreview) -> Boolean,
    defaultBringIntoViewSpec: BringIntoViewSpec,
    onSeeAll: ((String) -> Unit)?,
    cardCaptions: Boolean
) {
    itemsIndexed(
        items = band.rows,
        key = { _, shelf -> shelf.row.key },
        contentType = { _, _ -> "library_shelf" }
    ) { rowIndex, shelf ->
        val rowKey = shelf.row.key
        val count = shelf.row.items.list.size
        val seeAllLabel = stringResource(R.string.library_see_all)
        val seeAllDetail = pluralStringResource(R.plurals.library_title_count, count, count)
        val seeAllTile = remember(rowKey, seeAllLabel, seeAllDetail, onSeeAll) {
            onSeeAll?.let { MarqueeRowTile(seeAllLabel, Icons.AutoMirrored.Filled.ArrowForward, seeAllDetail) { it(rowKey) } }
        }?.takeIf { count >= LIBRARY_SEE_ALL_MIN_TITLES }
        MarqueeRowSection(
            row = shelf.row,
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
            onRowItemFocused = band.onCardFocused,
            useLandscapePosters = useLandscapePosters,
            accent = accent,
            isCatalogItemWatched = isItemWatched,
            onCatalogItemLongPress = band.onCardLongPress,
            onNavigateToDetail = band.onCardClick,
            trailingTile = seeAllTile,
            cardCaptions = cardCaptions,
            dimUntilFocused = shelf.isHidden,
            focusChip = if (shelf.isHidden) stringResource(R.string.library_hidden_restore) else null
        )
    }
}
