package com.nuvio.tv.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.util.extractReleaseYear
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.ui.screens.home.HeroBackdropState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.nuvio.tv.R
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject

enum class LibrarySortOption(
    val key: String,
    val labelResId: Int
) {
    ADDED_DESC("added_desc", R.string.library_sort_added_desc),
    ADDED_ASC("added_asc", R.string.library_sort_added_asc),
    TITLE_ASC("title_asc", R.string.library_sort_title_asc),
    TITLE_DESC("title_desc", R.string.library_sort_title_desc),
    /** Last watched first (U1, from the watch progress). */
    RECENTLY_WATCHED("recently_watched", R.string.library_sort_recently_watched)
}

enum class LibraryWatchedFilter(val key: String, val labelResId: Int) {
    ALL("all", R.string.library_watched_filter_all),
    WATCHED("watched", R.string.library_watched_filter_watched),
    UNWATCHED("unwatched", R.string.library_watched_filter_unwatched);
}

data class FilterOption(
    val key: String,
    val label: String,
    val count: Int
)

/**
 * Library screen state. [typeTabs]/[activeTypeKey]/[shelves] drive the shelves (Spec 27a); the
 * sort and filter fields (session-only), [seeAllShelfKey] and [visibleItems] drive the Sort &
 * filter grid (Spec 27b), shown instead of the shelves when [showsGrid]; [history] drives the
 * History tab (Spec 27c, [isHistory]; the screen groups it by day); [history] and [watchCounts]
 * also give the hero eyebrow its watch fact (Spec 27e).
 */
data class LibraryUiState(
    val allItems: List<LibraryEntry> = emptyList(),
    val visibleItems: List<LibraryEntry> = emptyList(),
    val typeTabs: List<LibraryTypeTab> = libraryTypeTabs(emptyList()),
    /** The tab the user picked (persisted per profile); null until the persisted one loads. */
    val selectedTypeKey: String? = null,
    /** The tab shown: [selectedTypeKey] when the library has it (History always), else All. */
    val activeTypeKey: String = LibraryTypeTab.ALL_KEY,
    val shelves: List<LibraryShelf> = emptyList(),
    val selectedSortOption: LibrarySortOption = LibrarySortOption.ADDED_DESC,
    val availableGenres: List<FilterOption> = emptyList(),
    val availableYears: List<FilterOption> = emptyList(),
    val selectedGenre: String? = null,
    val selectedYear: String? = null,
    val selectedWatchedFilter: LibraryWatchedFilter = LibraryWatchedFilter.ALL,
    /** A shelf's See all tile opened the grid on that shelf (u7 stage 4); cleared by any panel choice. */
    val seeAllShelfKey: String? = null,
    /** Title id -> last watched (epoch ms, the History time), for the Recently watched sort. */
    val lastWatchedById: Map<String, Long> = emptyMap(),
    /** The History tab's titles, newest first (stage 7). */
    val history: List<LibraryHistoryEntry> = emptyList(),
    /** History's Hidden group: the Next Up cards removed from Continue Watching (stage 8). */
    val hiddenNextUp: List<LibraryHiddenNextUp> = emptyList(),
    /** Movie id -> plays to the end on this device (Spec 27e, the hero's "Watched 2×"); null until loaded. */
    val watchCounts: Map<String, Int>? = null,
    /** The watch progress and the watched set have arrived. */
    val historyLoaded: Boolean = false,
    val watchedMovieIds: Set<String> = emptySet(),
    val watchedSeriesIds: Set<String> = emptySet(),
    val isNuvioAccount: Boolean = false,
    /** The Card shape setting: Landscape = true overrides every card, else each card its addon's shape (Spec 41 D1/D2). */
    val landscapePostersEnabled: Boolean = false,
    /** The first sync is running and the library is still empty. */
    val isSyncingEmpty: Boolean = true,
    /** The watched movie set has arrived (it comes debounced): until then the shelves would
     *  file every title under Not watched yet and reshuffle a moment later. */
    val watchedMoviesLoaded: Boolean = false
) {
    /** The hero's watch fact can be told: the history and the counts have arrived (no "Not watched" flash). */
    val watchFactLoaded: Boolean get() = historyLoaded && watchCounts != null

    /** The History tab is shown (stages 7, 10). */
    val isHistory: Boolean get() = activeTypeKey == LibraryTypeTab.HISTORY_KEY

    /** The skeleton shows (stage 11); History waits for its own data only. */
    val isLoading: Boolean get() = if (isHistory) !historyLoaded else isSyncingEmpty || !watchedMoviesLoaded

    /** Any filter or sort choice other than the shelves' own (Added, newest first): the grid shows. */
    val hasFilterChoice: Boolean get() = selectedGenre != null || selectedYear != null ||
        selectedWatchedFilter != LibraryWatchedFilter.ALL || selectedSortOption != LibrarySortOption.ADDED_DESC

    /** The grid replaces the shelves (stage 6): a filter choice, or a See all tile. Never on History. */
    val showsGrid: Boolean get() = !isHistory && (hasFilterChoice || seeAllShelfKey != null)

    /** What the grid lists: a change starts it at the top. Every input of [visibleItems]' choice. */
    val gridQueryKey: List<Any?>
        get() = listOf(activeTypeKey, selectedGenre, selectedYear, selectedWatchedFilter, selectedSortOption, seeAllShelfKey)
}

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val metaPrefetcher: com.nuvio.tv.ui.components.MetaFocusPrefetcher,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val libraryPreferences: LibraryPreferences,
    private val authManager: AuthManager,
    private val watchProgressRepository: com.nuvio.tv.domain.repository.WatchProgressRepository,
    private val watchedSeriesStateHolder: com.nuvio.tv.data.local.WatchedSeriesStateHolder,
    private val continueWatchingSettings: com.nuvio.tv.data.local.ContinueWatchingSettingsDataStore,
    private val watchCountDataStore: com.nuvio.tv.data.local.WatchCountDataStore,
    val posterOptions: com.nuvio.tv.ui.components.posteroptions.PosterOptionsController
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState())
    // Titles whose Remove from history is still writing (see removeFromHistory).
    private val removingFromHistory = MutableStateFlow<Set<String>>(emptySet())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    // Focus restore on return from Details (the screen leaves composition; this ViewModel stays
    // on the back stack): the shelf, its focused card and every shelf's scroll, as Search does.
    var savedFocusRowKey: String? = null
    var savedFocusItemIndex: Int = 0
    var savedRowScrollPositions: Map<String, Int> = emptyMap()
    var hasSavedFocus: Boolean = false

    init {
        posterOptions.bind(viewModelScope)
        metaPrefetcher.bind(viewModelScope)
        viewModelScope.launch {
            layoutPreferenceDataStore.modernLandscapePostersEnabled
                .distinctUntilChanged()
                .collect { enabled -> _uiState.update { it.copy(landscapePostersEnabled = enabled) } }
        }
        observeLibraryData()
        viewModelScope.launch {
            watchProgressRepository.observeWatchedMovieIds()
                .collect { ids ->
                    _uiState.update { current ->
                        current.copy(watchedMovieIds = ids, watchedMoviesLoaded = true).withDerived()
                    }
                }
        }
        viewModelScope.launch {
            combine(
                watchProgressRepository.allProgress,
                watchProgressRepository.watchedItems,
                removingFromHistory
            ) { progress, watched, removing ->
                libraryHistory(progress, watched).filterNot { it.id in removing }
            }
                .distinctUntilChanged()
                .combine(continueWatchingSettings.dismissedNextUpKeys.distinctUntilChanged()) { history, dismissed ->
                    history to libraryHiddenNextUp(dismissed, history)
                }
                .collect { (history, hidden) ->
                    val byId = history.associate { it.id to it.watchedAt }
                    _uiState.update {
                        it.copy(
                            history = history,
                            hiddenNextUp = hidden,
                            historyLoaded = true,
                            lastWatchedById = byId
                        ).withDerived()
                    }
                }
        }
        viewModelScope.launch {
            watchCountDataStore.counts
                .distinctUntilChanged()
                .collect { counts -> _uiState.update { it.copy(watchCounts = counts) } }
        }
        viewModelScope.launch {
            watchedSeriesStateHolder.fullyWatchedSeriesIds
                .collect { ids ->
                    _uiState.update { current ->
                        current.copy(watchedSeriesIds = ids).withDerived()
                    }
                }
        }
    }

    fun prefetchMetaOnFocus(id: String, type: String) = metaPrefetcher.prefetchOnFocus(id, type)

    /** Before leaving for Details: Back restores [rowKey], its card [itemIndex] and the shelves' scroll. */
    fun saveFocus(rowKey: String, itemIndex: Int, rowScrollPositions: Map<String, Int>) {
        savedFocusRowKey = rowKey
        savedFocusItemIndex = itemIndex
        savedRowScrollPositions = rowScrollPositions
        hasSavedFocus = true
    }

    /**
     * A card's OK (shelves, grid, History): Details opens on the title's cached backdrop, else
     * [fallbackBackdrop] (the card's own art), so the backdrop does not flash.
     */
    fun openDetail(
        id: String,
        type: String,
        addonBaseUrl: String?,
        fallbackBackdrop: String?,
        navigate: (String, String, String?) -> Unit
    ) {
        HeroBackdropState.update(metaPrefetcher.cachedBackdrop(id, type) ?: fallbackBackdrop)
        navigate(id, type, addonBaseUrl?.takeIf { it.isNotBlank() })
    }

    /** A card's long-press: the options dialog, on the poster art (as the old grid did). */
    fun showPosterOptions(item: MetaPreview, addonBaseUrl: String?) {
        posterOptions.show(item.copy(posterShape = PosterShape.POSTER), addonBaseUrl?.takeIf { it.isNotBlank() })
    }

    /** A Hidden card's OK (stage 8): its Next Up card returns to Continue Watching (the set syncs). */
    fun restoreNextUp(keys: List<String>) {
        viewModelScope.launch { continueWatchingSettings.removeDismissedNextUpKeys(keys) }
    }

    /**
     * History's menu (stage 7): the title leaves the history (progress, watched marks, remote
     * copies). The repository clears the progress store before the watched set, so the title is
     * hidden until both are written: it must not come back for a frame at its watched-mark time.
     */
    fun removeFromHistory(contentId: String) {
        removingFromHistory.update { it + contentId }
        viewModelScope.launch {
            try {
                watchProgressRepository.removeFromHistory(contentId, season = null, episode = null)
            } finally {
                removingFromHistory.update { it - contentId }
            }
        }
    }

    /**
     * Tabs select on focus: a focus on the tab already shown changes nothing and writes nothing
     * (the skeleton's focused All tab must not overwrite the persisted tab before it loads).
     */
    fun onSelectTypeTab(key: String) {
        if (key == _uiState.value.activeTypeKey) return
        // A See all grid belongs to its tab's shelf; filters carry over.
        _uiState.update { current -> current.copy(selectedTypeKey = key, seeAllShelfKey = null).withDerived() }
        viewModelScope.launch {
            libraryPreferences.setLastSelectedType(key)
        }
    }

    // Sort & filter panel choices (stage 5): each applies to the whole tab, so it ends a See all grid.
    fun onSelectGenre(key: String?) = applyPanelChoice { copy(selectedGenre = key) }

    fun onSelectYear(key: String?) = applyPanelChoice { copy(selectedYear = key) }

    fun onSelectWatchedFilter(filter: LibraryWatchedFilter) = applyPanelChoice { copy(selectedWatchedFilter = filter) }

    fun onSelectSortOption(option: LibrarySortOption) = applyPanelChoice { copy(selectedSortOption = option) }

    /** Show shelves (stage 5): every choice back to the shelves' own. */
    fun onShowShelves() = applyPanelChoice {
        copy(
            selectedSortOption = LibrarySortOption.ADDED_DESC,
            selectedGenre = null,
            selectedYear = null,
            selectedWatchedFilter = LibraryWatchedFilter.ALL
        )
    }

    /**
     * A panel choice ends a See all grid. The panel sorts and filters the library, not the history:
     * a choice made on History shows it on the All tab (the pill shows on History, stage 7).
     */
    private fun applyPanelChoice(choice: LibraryUiState.() -> LibraryUiState) {
        val leavesHistory = _uiState.value.isHistory
        _uiState.update { current ->
            current.choice().copy(
                seeAllShelfKey = null,
                selectedTypeKey = if (leavesHistory) LibraryTypeTab.ALL_KEY else current.selectedTypeKey
            ).withDerived()
        }
        if (leavesHistory) viewModelScope.launch { libraryPreferences.setLastSelectedType(LibraryTypeTab.ALL_KEY) }
    }

    /** A shelf's See all tile (stage 4): the grid lists that shelf. */
    fun onOpenSeeAll(shelfKey: String) {
        _uiState.update { current -> current.copy(seeAllShelfKey = shelfKey).withDerived() }
    }

    private fun observeLibraryData() {
        viewModelScope.launch {
            // The sort is session-only (the grid is a view; Library opens on the shelves), so the
            // as-built persisted sort is no longer read.
            combine(
                libraryRepository.isSyncing,
                libraryRepository.libraryItems,
                authManager.authState,
                libraryPreferences.lastSelectedType
            ) { isSyncing, items, authState, persistedTypeKey ->
                DataBundle(
                    isSyncing = isSyncing,
                    items = items,
                    authState = authState,
                    persistedTypeKey = persistedTypeKey
                )
            }.collectLatest { bundle ->
                val (isSyncing, items, authState, persistedTypeKey) = bundle
                _uiState.update { current ->
                    val updated = current.copy(
                        allItems = items,
                        selectedTypeKey = current.selectedTypeKey ?: persistedTypeKey,
                        isNuvioAccount = authState is AuthState.FullAccount,
                        isSyncingEmpty = isSyncing && items.isEmpty()
                    )
                    updated.withDerived()
                }
            }
        }
    }

    private data class DataBundle(
        val isSyncing: Boolean,
        val items: List<LibraryEntry>,
        val authState: AuthState,
        val persistedTypeKey: String? = null
    )

    private fun LibraryEntry.extractYear(): String? = extractReleaseYear(releaseInfo)

    private fun LibraryEntry.hasGenre(genre: String): Boolean = genres.any { it.equals(genre, ignoreCase = true) }

    /** Everything derived from the library, the watched sets and the selections: tabs, shelves and the grid. */
    private fun LibraryUiState.withDerived(): LibraryUiState {
        val tabs = libraryTypeTabs(allItems)
        val activeKey = activeTypeKey(selectedTypeKey, tabs)
        val isHistoryTab = activeKey == LibraryTypeTab.HISTORY_KEY
        val shelves = if (isHistoryTab) emptyList() else libraryShelves(
            entries = allItems,
            typeKey = activeKey,
            isWatched = { it.isWatchedIn(watchedMovieIds, watchedSeriesIds) },
            recentSinceMs = startOfThisMonthMs()
        )

        // Type filter; History's panel lists the whole library's genres and years (as All).
        val typeFiltered = entriesOfType(allItems, if (isHistoryTab) LibraryTypeTab.ALL_KEY else activeKey)

        // A genre or year this tab no longer holds (another tab, its last title removed) is
        // cleared before anything filters on it, so the grid matches what the panel shows.
        val validGenre = selectedGenre?.takeIf { g -> typeFiltered.any { it.hasGenre(g) } }
        val validYear = selectedYear?.takeIf { y -> typeFiltered.any { it.extractYear() == y } }
        val seeAllShelf = seeAllShelfKey?.let { key -> shelves.firstOrNull { it.key == key } }

        val genreFiltered = if (validGenre != null) typeFiltered.filter { it.hasGenre(validGenre) } else typeFiltered
        val yearFiltered = if (validYear != null) genreFiltered.filter { it.extractYear() == validYear } else genreFiltered
        val watchedFiltered = when (selectedWatchedFilter) {
            LibraryWatchedFilter.ALL -> yearFiltered
            LibraryWatchedFilter.WATCHED -> yearFiltered.filter { it.isWatchedIn(watchedMovieIds, watchedSeriesIds) }
            LibraryWatchedFilter.UNWATCHED -> yearFiltered.filterNot { it.isWatchedIn(watchedMovieIds, watchedSeriesIds) }
        }

        // Faceted counts — each filter counts items matching all OTHER active filters

        // Genre counts: from typeFiltered, applying year filter but NOT genre filter
        val itemsForGenreCounts = if (validYear != null) typeFiltered.filter { it.extractYear() == validYear } else typeFiltered
        val genreCounts = mutableMapOf<String, Int>()
        itemsForGenreCounts.forEach { entry ->
            entry.genres.forEach { genre ->
                val normalized = genre.trim()
                if (normalized.isNotBlank()) {
                    genreCounts[normalized] = (genreCounts[normalized] ?: 0) + 1
                }
            }
        }
        val genreOptions = genreCounts.entries
            .sortedBy { it.key.lowercase(Locale.ROOT) }
            .map { (genre, count) -> FilterOption(key = genre, label = genre, count = count) }

        // Year counts: from typeFiltered, applying genre filter but NOT year filter
        val yearCounts = mutableMapOf<String, Int>()
        genreFiltered.forEach { entry ->
            val year = entry.extractYear() ?: return@forEach
            yearCounts[year] = (yearCounts[year] ?: 0) + 1
        }
        val yearOptions = yearCounts.entries
            .sortedByDescending { it.key }
            .map { (year, count) -> FilterOption(key = year, label = year, count = count) }

        // The grid: a See all shelf as listed (a shelf that left returns the shelves), else the
        // filtered titles in the chosen order.
        val gridItems = seeAllShelf?.entries ?: sortLibraryEntries(watchedFiltered, selectedSortOption, lastWatchedById)

        return copy(
            typeTabs = tabs,
            activeTypeKey = activeKey,
            shelves = shelves,
            visibleItems = gridItems,
            availableGenres = genreOptions,
            availableYears = yearOptions,
            selectedGenre = validGenre,
            selectedYear = validYear,
            seeAllShelfKey = seeAllShelf?.key
        )
    }
}

/** The Recently added shelf's cut-off ("12 this month"): local midnight on the 1st. */
private fun startOfThisMonthMs(): Long {
    val zone = ZoneId.systemDefault()
    return LocalDate.now(zone).withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
}
