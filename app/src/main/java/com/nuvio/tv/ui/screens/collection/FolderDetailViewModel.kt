package com.nuvio.tv.ui.screens.collection

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.tmdb.TmdbApiKeyProvider
import com.nuvio.tv.core.tmdb.TmdbCollectionSourceResolver
import com.nuvio.tv.core.tmdb.rowCatalogId
import com.nuvio.tv.core.util.hasNoReleaseInfo
import com.nuvio.tv.core.util.isUnreleased
import com.nuvio.tv.data.trailer.TrailerService
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonCatalogCollectionSource
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.CollectionSource
import com.nuvio.tv.domain.model.CollectionFolder
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.FolderViewMode
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.TmdbCollectionSource
import com.nuvio.tv.domain.model.TraktCollectionSource
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.model.isNoFilterExtraValue
import com.nuvio.tv.domain.model.mergeCatalogPage
import com.nuvio.tv.domain.model.nextCatalogSkip
import com.nuvio.tv.domain.model.skipStep
import com.nuvio.tv.domain.model.supportsExtra
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import com.nuvio.tv.ui.screens.home.HomeRow
import com.nuvio.tv.ui.screens.home.HomeScreenFocusState
import com.nuvio.tv.ui.screens.home.savedHomeFocusState
import com.nuvio.tv.ui.screens.home.HomeUiState
import com.nuvio.tv.ui.screens.home.LayoutUiPrefs
import com.nuvio.tv.ui.screens.home.ModernCarouselRowBuildCache
import com.nuvio.tv.ui.screens.home.ModernHomePresentationInput
import com.nuvio.tv.ui.screens.home.ModernHomePresentationState
import com.nuvio.tv.domain.model.PLACEHOLDER_IMAGE_URL
import com.nuvio.tv.ui.screens.home.buildModernHomePresentation
import com.nuvio.tv.ui.screens.home.homeItemStatusKey
import com.nuvio.tv.ui.screens.home.layoutUiPrefsFlow
import com.nuvio.tv.ui.screens.home.withLayoutPrefs
import com.nuvio.tv.domain.repository.CatalogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.nuvio.tv.domain.model.PLACEHOLDER_ITEM_ID_PREFIX

data class FolderDetailUiState(
    val folder: CollectionFolder? = null,
    val collectionTitle: String = "",
    val viewMode: FolderViewMode = FolderViewMode.TABBED_GRID,
    /** Layout prefs, read once per folder load; null until the folder loads. */
    val layoutPrefs: LayoutUiPrefs? = null,
    val tabs: List<FolderTab> = emptyList(),
    val selectedTabIndex: Int = 0,
    val isLoading: Boolean = true,
    val followLayoutHomeState: HomeUiState? = null,
    val movieWatchedStatus: Map<String, Boolean> = emptyMap()
) {
    /** Read by the Tabs mode's tab row; the pref default (true) until prefs load. */
    val catalogTypeSuffixEnabled: Boolean
        get() = layoutPrefs?.catalogTypeSuffixEnabled ?: true

    /**
     * Every source tab finished loading, every one failed, and none ever loaded
     * content (ke13 blank-tiles report, 2026-09-03); the All tab then carries the
     * first error (withRebuiltAllTab). Tabs with loaded content are excluded:
     * TMDB pagination errors set `error` on content-bearing tabs and must not wipe
     * the grid. Trakt sources build no tab (buildSourceTabs).
     */
    val allSourcesFailed: Boolean
        get() {
            val sourceTabs = tabs.filter { !it.isAllTab }
            return sourceTabs.isNotEmpty() && sourceTabs.all { it.firstPageFailed }
        }
}

data class FolderTab(
    val label: String,
    val typeLabel: String = "",
    val rawType: String = "",
    val source: CollectionSource? = null,
    val catalogRow: CatalogRow? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    // Set by every successful load arm; append failures put `error` on tabs that
    // already hold content, and those must not count as "failed" for the grid.
    val hasLoadedContent: Boolean = false,
    // A TMDB source that failed because no TMDB key is set (Spec 52 T1); its `error` is set too.
    // The All tab carries it when every source failed and one of them needs the key.
    val needsTmdbKey: Boolean = false,
    val isAllTab: Boolean = false,
    // Addon sources only: the declared addon's base URL, resolved at folder load so the
    // loading shimmer row keys (stableKey) like the row the source loads.
    val addonBaseUrl: String = ""
)

@HiltViewModel
class FolderDetailViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    savedStateHandle: SavedStateHandle,
    private val collectionsDataStore: CollectionsDataStore,
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val watchProgressRepository: WatchProgressRepository,
    private val watchedSeriesStateHolder: com.nuvio.tv.data.local.WatchedSeriesStateHolder,
    private val metaRepository: com.nuvio.tv.domain.repository.MetaRepository,
    private val trailerService: TrailerService,
    private val tmdbCollectionSourceResolver: TmdbCollectionSourceResolver,
    private val tmdbApiKeys: TmdbApiKeyProvider,
    val posterOptions: com.nuvio.tv.ui.components.posteroptions.PosterOptionsController
) : ViewModel() {

    private val collectionId: String = savedStateHandle["collectionId"] ?: ""
    private val folderId: String = savedStateHandle["folderId"] ?: ""

    private val _uiState = MutableStateFlow(FolderDetailUiState())
    val uiState: StateFlow<FolderDetailUiState> = _uiState.asStateFlow()

    private var movieWatchedJob: Job? = null
    private var enrichFocusJob: Job? = null
    private val enrichedItemIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val backgroundMetaPrefetchedIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val _enrichingItemId = MutableStateFlow<String?>(null)
    val enrichingItemId: StateFlow<String?> = _enrichingItemId.asStateFlow()
    private val _enrichedPreviews = MutableStateFlow<Map<String, MetaPreview>>(emptyMap())
    val enrichedPreviews: StateFlow<Map<String, MetaPreview>> = _enrichedPreviews.asStateFlow()
    private val _trailerPreviewUrls = MutableStateFlow<Map<String, String>>(emptyMap())
    val trailerPreviewUrls: StateFlow<Map<String, String>> = _trailerPreviewUrls.asStateFlow()
    private val _trailerPreviewAudioUrls = MutableStateFlow<Map<String, String>>(emptyMap())
    val trailerPreviewAudioUrls: StateFlow<Map<String, String>> = _trailerPreviewAudioUrls.asStateFlow()
    private val trailerPreviewNegativeCache = mutableSetOf<String>()
    private val modernCarouselRowBuildCache = ModernCarouselRowBuildCache()
    // B19: rebuildFollowLayoutState is called from many sites (main and IO). One worker
    // serializes writes to modernCarouselRowBuildCache (plain maps); latest-wins drops
    // superseded builds so an older snapshot never overwrites a newer one.
    private val followLayoutRebuilds = LatestWinsSerialBuilds(
        scope = viewModelScope,
        baseDispatcher = Dispatchers.Default
    )
    // Main-only. One preview request in flight; a newer request for another item cancels it.
    private var trailerPreviewJob: Job? = null
    private var trailerPreviewItemId: String? = null

    /** Items for which enrichment was attempted but produced no enriched data. */
    private val _failedEnrichmentIds = MutableStateFlow<Set<String>>(emptySet())
    val failedEnrichmentIds: StateFlow<Set<String>> = _failedEnrichmentIds.asStateFlow()

    private val adjacentItemPrefetchJobs = com.nuvio.tv.ui.screens.home.AdjacentPrefetchJobs()
    private val prefetchedExternalMetaIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    // The Rows mode's focus memory (the Tabs band keeps its own, saveable).
    private val _followLayoutFocusState = MutableStateFlow(HomeScreenFocusState())
    val followLayoutFocusState: StateFlow<HomeScreenFocusState> = _followLayoutFocusState.asStateFlow()

    init {
        posterOptions.bind(viewModelScope)
        loadFolder()
        // Observe watched status immediately so badges are ready when catalogs load.
        observeWatchedStatusCombined()
        reloadOnTmdbKey()
    }

    // Spec 52 T1: once a TMDB key is saved, the sources that waited for one load by themselves.
    private fun reloadOnTmdbKey() {
        viewModelScope.launch {
            tmdbApiKeys.state.filterNotNull()
                .map { it.hasKey }
                .distinctUntilChanged()
                .filter { it }
                .collect { reloadSourceTabs { s -> s.tabs.indices.filter { i -> s.tabs[i].waitsForTmdbKey } } }
        }
    }

    private fun observeWatchedStatusCombined() {
        movieWatchedJob = viewModelScope.launch {
            combine(
                watchProgressRepository.observeWatchedMovieIds(),
                watchedSeriesStateHolder.fullyWatchedSeriesIds,
                _uiState.map { state -> state.tabs.flatMap { it.catalogRow?.items.orEmpty() } }
                    .distinctUntilChanged()
            ) { movieWatchedIds, seriesWatchedIds, allItems ->
                Triple(movieWatchedIds, seriesWatchedIds, allItems)
            }.collectLatest { (movieWatchedIds, seriesWatchedIds, allItems) ->
                val newStatus = mutableMapOf<String, Boolean>()
                allItems.forEach { item ->
                    val key = com.nuvio.tv.ui.screens.home.homeItemStatusKey(item.id, item.apiType)
                    val isWatched = when (item.apiType) {
                        "movie" -> item.id in movieWatchedIds
                        "series", "tv" -> item.id in seriesWatchedIds
                        else -> false
                    }
                    newStatus[key] = isWatched
                }
                _uiState.update { s ->
                    if (s.movieWatchedStatus == newStatus) s else s.copy(movieWatchedStatus = newStatus)
                }
                rebuildFollowLayoutState()
            }
        }
    }

    private fun loadFolder() {
        viewModelScope.launch {
            val collections = collectionsDataStore.collections.first()
            val collection = collections.find { it.id == collectionId }
            val folder = collection?.folders?.find { it.id == folderId }

            if (folder == null) {
                _uiState.update {
                    it.copy(
                        folder = folder,
                        collectionTitle = collection?.title ?: "",
                        viewMode = collection?.viewMode ?: FolderViewMode.TABBED_GRID,
                        isLoading = false
                    )
                }
                return@launch
            }

            val addons = addonRepository.getInstalledAddons().first().enabledAddons()
            val layoutPrefs = layoutPreferenceDataStore.layoutUiPrefsFlow().first()

            val viewMode = collection.viewMode

            // May be empty (no sources, or Trakt sources only): the screen owns that state.
            val sourceTabs = buildSourceTabs(
                sources = folder.sources,
                addons = addons,
                addonTabLabels = { source, catalogName -> buildAddonTabLabels(source, catalogName) },
                tmdbTypeLabel = { buildTmdbTypeLabel(it) }
            )
            val showAll = collection.showAllTab && sourceTabs.size >= 2

            val tabs = if (showAll) {
                listOf(
                    FolderTab(
                        label = appContext.getString(com.nuvio.tv.R.string.collections_tab_all),
                        isLoading = true,
                        isAllTab = true
                    )
                ) + sourceTabs
            } else {
                sourceTabs
            }

            _uiState.update {
                it.copy(
                    folder = folder,
                    collectionTitle = collection.title,
                    viewMode = viewMode,
                    layoutPrefs = layoutPrefs,
                    tabs = tabs,
                    isLoading = false
                )
            }

            // Immediately build shimmer placeholders for the rows mode
            rebuildFollowLayoutState()

            // From the built tabs, not folder.sources: skipped sources and the All tab shift indices.
            tabs.forEachIndexed { index, tab ->
                tab.source?.let { loadSourceForTab(index, it) }
            }
        }
    }

    // Merged inside update{} so a concurrent IO enrichment (updateItemInTabs)
    // is never overwritten by a merge computed from an older snapshot.
    private fun rebuildAllTab() {
        _uiState.update { it.withRebuiltAllTab() }
    }

    private class FollowLayoutBuild(
        val allRows: List<CatalogRow>,
        val homeRows: List<HomeRow>,
        val anyLoading: Boolean,
        val heroEnrichmentEnabled: Boolean,
        val layoutPrefs: LayoutUiPrefs,
        val presentation: ModernHomePresentationState
    )

    private fun rebuildFollowLayoutState() {
        if (_uiState.value.viewMode.displayMode != FolderViewMode.ROWS) return
        followLayoutRebuilds.launch(
            build = { buildFollowLayout() },
            publish = { publishFollowLayout(it) }
        )
    }

    /**
     * Runs on the single follow-layout worker and reads the state when it runs, not when it
     * was requested, so the last build to run always sees the newest tabs.
     */
    private suspend fun buildFollowLayout(): FollowLayoutBuild? {
        val state = _uiState.value
        if (state.viewMode.displayMode != FolderViewMode.ROWS) return null
        val prefs = state.layoutPrefs ?: return null
        val sourceTabs = state.tabs.filter { !it.isAllTab }

        // May be empty once every source failed or settled empty: that still publishes,
        // so no earlier shimmer state is left on screen.
        val allRows = followLayoutRows(sourceTabs)
        val homeRows = allRows.map { HomeRow.Catalog(it) }

        val anyLoading = sourceTabs.any { it.isLoading }

        val heroEnrichmentEnabled = layoutPreferenceDataStore.preferExternalMetaAddonDetail.first()
        val modernPresentation = buildModernHomePresentation(
            input = ModernHomePresentationInput(
                homeRows = homeRows,
                catalogRows = allRows,
                continueWatchingItems = emptyList(),
                upcomingItems = emptyList(),
                useLandscapePosters = prefs.modernLandscapePostersEnabled,
                showCatalogTypeSuffix = prefs.catalogTypeSuffixEnabled,
                showAddonName = prefs.catalogAddonNameEnabled,
                showFullReleaseDate = prefs.showFullReleaseDate,
                localeTag = com.nuvio.tv.LocaleCache.localeTag
            ),
            cache = modernCarouselRowBuildCache,
            context = appContext
        )
        return FollowLayoutBuild(
            allRows = allRows,
            homeRows = homeRows,
            anyLoading = anyLoading,
            heroEnrichmentEnabled = heroEnrichmentEnabled,
            layoutPrefs = prefs,
            presentation = modernPresentation
        )
    }

    private fun publishFollowLayout(build: FollowLayoutBuild) {
        _uiState.update { s ->
            val homeState = HomeUiState(
                catalogRows = build.allRows,
                homeRows = build.homeRows,
                heroSectionEnabled = false,
                isLoading = build.anyLoading,
                movieWatchedStatus = s.movieWatchedStatus,
                heroEnrichmentEnabled = build.heroEnrichmentEnabled,
                modernHomePresentation = build.presentation
            ).withLayoutPrefs(build.layoutPrefs)
            s.copy(followLayoutHomeState = homeState)
        }
    }

    private fun loadSourceForTab(tabIndex: Int, source: CollectionSource) {
        when (source) {
            is AddonCatalogCollectionSource -> loadAddonCatalogForTab(tabIndex, source)
            is TmdbCollectionSource -> loadTmdbSourceForTab(tabIndex, source, page = 1, append = false)
            // buildSourceTabs skips Trakt sources (integration removed, Spec 28 Q3).
            is TraktCollectionSource -> Unit
        }
    }

    private fun loadAddonCatalogForTab(tabIndex: Int, source: AddonCatalogCollectionSource) {
        viewModelScope.launch {
            val addons = addonRepository.getInstalledAddons().first().enabledAddons()
            val addon = addons.find { it.id == source.addonId }

            if (addon == null) {
                _uiState.update { state ->
                    val tabs = state.tabs.toMutableList()
                    if (tabIndex < tabs.size) {
                        tabs[tabIndex] = tabs[tabIndex].copy(
                            isLoading = false,
                            error = appContext.getString(R.string.addon_error_not_found)
                        )
                    }
                    state.copy(tabs = tabs)
                }
                rebuildAllTab()
                rebuildFollowLayoutState()
                return@launch
            }

            var catalog = addon.catalogs.find { it.id == source.catalogId && it.apiType == source.type }
                ?: addon.catalogs.find { it.id == source.catalogId.substringBefore(",") && it.apiType == source.type }
            // If the catalog wasn't found in the declared addon, search all installed addons.
            var effectiveAddon: com.nuvio.tv.domain.model.Addon = addon
            if (catalog == null) {
                for (a in addons) {
                    val match = a.catalogs.find { it.id == source.catalogId && it.apiType == source.type }
                    if (match != null) {
                        effectiveAddon = a
                        catalog = match
                        break
                    }
                }
            }
            val tab = _uiState.value.tabs.getOrNull(tabIndex)
            val catalogName = catalog?.name
                ?: tab?.label?.takeIf { it.isNotBlank() }
                ?: source.catalogId

            val supportsSkip = catalog?.supportsExtra("skip") ?: false
            val skipStep = catalog?.skipStep() ?: 100
            val extraArgs = buildCatalogExtraArgs(source)

            catalogRepository.getCatalog(
                addonBaseUrl = effectiveAddon.baseUrl,
                addonId = effectiveAddon.id,
                addonName = effectiveAddon.displayName,
                catalogId = source.catalogId,
                catalogName = catalogName,
                type = source.type,
                skip = 0,
                skipStep = skipStep,
                extraArgs = extraArgs,
                supportsSkip = supportsSkip
            ).collect { result ->
                when (result) {
                    is NetworkResult.Success -> {
                        _uiState.update { state ->
                            val tabs = state.tabs.toMutableList()
                            if (tabIndex < tabs.size) {
                                tabs[tabIndex] = tabs[tabIndex].copy(
                                    catalogRow = result.data.filteredForRelease(state.hideUnreleased),
                                    isLoading = false,
                                    hasLoadedContent = true
                                )
                            }
                            state.copy(tabs = tabs)
                        }
                        rebuildAllTab()
                        rebuildFollowLayoutState()
                    }
                    is NetworkResult.Error -> {
                        _uiState.update { state ->
                            val tabs = state.tabs.toMutableList()
                            if (tabIndex < tabs.size) {
                                tabs[tabIndex] = tabs[tabIndex].copy(isLoading = false, error = result.message)
                            }
                            state.copy(tabs = tabs)
                        }
                        rebuildAllTab()
                        rebuildFollowLayoutState()
                    }
                    NetworkResult.Loading -> {}
                }
            }
        }
    }

    fun loadMoreItems(tabIndex: Int) {
        val state = _uiState.value
        val tab = state.tabs.getOrNull(tabIndex) ?: return

        // All tab: load more from all source tabs that still have more
        if (tab.isAllTab) {
            state.tabs.forEachIndexed { index, sourceTab ->
                val sourceRow = sourceTab.catalogRow?.takeIf { !sourceTab.isAllTab } ?: return@forEachIndexed
                if (sourceRow.hasMore && !sourceRow.isLoading) {
                    loadMoreItems(index)
                }
            }
            return
        }

        val row = tab.catalogRow ?: return
        if (!row.hasMore || row.isLoading) return

        if (tab.source is TmdbCollectionSource) {
            loadTmdbSourceForTab(tabIndex, tab.source, page = row.currentPage + 1, append = true)
            return
        }

        // Mark the tab's catalogRow as loading
        _uiState.update { s ->
            val tabs = s.tabs.toMutableList()
            if (tabIndex < tabs.size) {
                tabs[tabIndex] = tabs[tabIndex].copy(
                    catalogRow = row.copy(isLoading = true)
                )
            }
            s.copy(tabs = tabs)
        }
        rebuildAllTab()
        rebuildFollowLayoutState()

        viewModelScope.launch {
            val nextSkip = row.nextCatalogSkip()

            catalogRepository.getCatalog(
                addonBaseUrl = row.addonBaseUrl,
                addonId = row.addonId,
                addonName = row.addonName,
                catalogId = row.catalogId,
                catalogName = row.catalogName,
                type = row.apiType,
                skip = nextSkip,
                skipStep = row.skipStep,
                extraArgs = row.extraArgs,
                supportsSkip = row.supportsSkip
            ).collect { result ->
                when (result) {
                    is NetworkResult.Success -> {
                        _uiState.update { s ->
                            val currentTab = s.tabs.getOrNull(tabIndex)
                            val currentRow = currentTab?.catalogRow ?: return@update s
                            val incomingFiltered = if (s.hideUnreleased) {
                                val today = java.time.LocalDate.now()
                                result.data.items.filterNot { it.isUnreleased(today) }
                            } else {
                                result.data.items
                            }
                            val mergedRow = currentRow.mergeCatalogPage(result.data, incomingFiltered)

                            val tabs = s.tabs.toMutableList()
                            tabs[tabIndex] = tabs[tabIndex].copy(
                                catalogRow = mergedRow.copy(isLoading = false)
                            )
                            s.copy(tabs = tabs)
                        }
                        rebuildAllTab()
                        rebuildFollowLayoutState()
                    }
                    is NetworkResult.Error -> {
                        _uiState.update { s ->
                            val currentRow = s.tabs.getOrNull(tabIndex)?.catalogRow ?: return@update s
                            val tabs = s.tabs.toMutableList()
                            tabs[tabIndex] = tabs[tabIndex].copy(
                                catalogRow = currentRow.copy(isLoading = false)
                            )
                            s.copy(tabs = tabs)
                        }
                        // The All tab and follow row were marked loading with this page.
                        rebuildAllTab()
                        rebuildFollowLayoutState()
                    }
                    NetworkResult.Loading -> {}
                }
            }
        }
    }

    fun loadMoreForCatalog(catalogId: String, addonId: String, type: String) {
        val state = _uiState.value
        val tabIndex = state.tabs.indexOfFirst { tab ->
            val row = tab.catalogRow ?: return@indexOfFirst false
            row.catalogId == catalogId && row.addonId == addonId && row.apiType == type
        }
        if (tabIndex >= 0) loadMoreItems(tabIndex)
    }

    fun selectTab(index: Int) {
        _uiState.update { it.copy(selectedTabIndex = index) }
    }

    fun saveFollowLayoutFocusState(
        verticalScrollIndex: Int,
        verticalScrollOffset: Int,
        focusedRowKey: String?,
        focusedItemKeyByRow: Map<String, String>,
        catalogRowScrollStates: Map<String, Int>,
        focusedRowIndex: Int = 0,
        focusedItemIndex: Int = 0
    ) {
        _followLayoutFocusState.value = savedHomeFocusState(
            verticalScrollIndex = verticalScrollIndex,
            verticalScrollOffset = verticalScrollOffset,
            focusedRowKey = focusedRowKey,
            focusedItemKeyByRow = focusedItemKeyByRow,
            catalogRowScrollStates = catalogRowScrollStates,
            focusedRowIndex = focusedRowIndex,
            focusedItemIndex = focusedItemIndex
        )
    }

    private fun loadTmdbSourceForTab(tabIndex: Int, source: TmdbCollectionSource, page: Int, append: Boolean) {
        if (append) {
            _uiState.update { s ->
                val tabs = s.tabs.toMutableList()
                val row = tabs.getOrNull(tabIndex)?.catalogRow
                if (row != null) tabs[tabIndex] = tabs[tabIndex].copy(catalogRow = row.copy(isLoading = true))
                s.copy(tabs = tabs)
            }
            rebuildAllTab()
            rebuildFollowLayoutState()
        }
        viewModelScope.launch {
            tmdbCollectionSourceResolver.resolve(source, page).collect { result ->
                when (result) {
                    is NetworkResult.Success -> {
                        _uiState.update { s ->
                            val tabs = s.tabs.toMutableList()
                            val currentRow = tabs.getOrNull(tabIndex)?.catalogRow
                            val filteredData = result.data.filteredForRelease(
                                hideUnreleased = s.hideUnreleased,
                                treatMissingDateAsUnreleased = true
                            )
                            val row = if (append && currentRow != null) {
                                val existingIds = currentRow.items.map { "${it.apiType}:${it.id}" }.toHashSet()
                                val newItems = filteredData.items.filter { "${it.apiType}:${it.id}" !in existingIds }
                                filteredData.copy(
                                    items = currentRow.items + newItems,
                                    hasMore = filteredData.hasMore && newItems.isNotEmpty(),
                                    isLoading = false
                                )
                            } else {
                                filteredData
                            }
                            // A landed page clears an earlier page error.
                            if (tabIndex < tabs.size) {
                                tabs[tabIndex] = tabs[tabIndex].copy(
                                    catalogRow = row, isLoading = false, error = null, hasLoadedContent = true,
                                    needsTmdbKey = false
                                )
                            }
                            s.copy(tabs = tabs)
                        }
                        rebuildAllTab()
                        rebuildFollowLayoutState()
                    }
                    is NetworkResult.Error -> {
                        _uiState.update { s ->
                            val tabs = s.tabs.toMutableList()
                            val current = tabs.getOrNull(tabIndex)
                            if (current != null) {
                                tabs[tabIndex] = current.copy(
                                    isLoading = false,
                                    error = result.message,
                                    catalogRow = current.catalogRow?.copy(isLoading = false),
                                    needsTmdbKey = result.code == NetworkResult.NO_TMDB_KEY_CODE
                                )
                            }
                            s.copy(tabs = tabs)
                        }
                        rebuildAllTab()
                        rebuildFollowLayoutState()
                    }
                    NetworkResult.Loading -> {}
                }
            }
        }
    }

    private fun buildAddonTabLabels(source: AddonCatalogCollectionSource, catalogName: String?): Pair<String, String> {
        val typeLabel = when (source.type.lowercase()) {
            "movie" -> appContext.getString(R.string.type_movies)
            "series" -> appContext.getString(R.string.type_series_plural)
            else -> source.type.replaceFirstChar { it.uppercase() }
        }
        val baseName = if (!catalogName.isNullOrBlank()) {
            catalogName.replaceFirstChar { it.uppercase() }
        } else {
            typeLabel
        }
        val effectiveGenre = source.genre?.takeIf { it.isNotBlank() && !isNoFilterExtraValue(it) }
        val name = effectiveGenre?.let { "$baseName · $it" } ?: baseName
        return name to typeLabel
    }

    private fun buildTmdbTypeLabel(source: TmdbCollectionSource): String {
        return when (source.sourceType.name) {
            "LIST" -> appContext.getString(R.string.collections_editor_tmdb_default_list)
            "COLLECTION" -> appContext.getString(R.string.collections_editor_tmdb_movie_collection)
            "COMPANY" -> appContext.getString(R.string.collections_editor_tmdb_mode_production)
            "NETWORK" -> appContext.getString(R.string.collections_editor_tmdb_mode_network)
            "PERSON" -> appContext.getString(R.string.collections_editor_tmdb_person_credits)
            "DIRECTOR" -> appContext.getString(R.string.collections_editor_tmdb_director_credits)
            else -> appContext.getString(R.string.collections_editor_tmdb_default_discover)
        }
    }

    private fun buildCatalogExtraArgs(source: AddonCatalogCollectionSource): Map<String, String> {
        val genre = source.genre?.takeIf { it.isNotBlank() && !isNoFilterExtraValue(it) } ?: return emptyMap()
        return mapOf("genre" to genre)
    }

    fun onItemFocused(item: MetaPreview) {
        // Clear enriching for previous item immediately.
        if (_enrichingItemId.value != null && _enrichingItemId.value != item.id) {
            _enrichingItemId.value = null
        }
        if (item.id in enrichedItemIds) {
            // Ensure enrichedPreviews has this item for hero display.
            if (item.id !in _enrichedPreviews.value) publishEnrichedPreview(item.id)
            return
        }


        enrichFocusJob?.cancel()
        enrichFocusJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            kotlinx.coroutines.delay(350)

            // Background-prefetch meta from addons so detail screen opens instantly.
            if (backgroundMetaPrefetchedIds.add(item.id)) {
                viewModelScope.launch {
                    metaRepository.getMetaFromAllAddons(
                        type = item.apiType,
                        id = item.id
                    ).first { it !is com.nuvio.tv.core.network.NetworkResult.Loading }
                }
            }

            val externalMetaEnabled = layoutPreferenceDataStore.preferExternalMetaAddonDetail.first()

            // Only signal enriching if the external meta source is active and
            // the folder shows rows (Home's hero) — prevents hero from hiding
            // indefinitely when no enrichment source can provide data.
            val viewMode = _uiState.value.viewMode
            if (externalMetaEnabled && viewMode.displayMode == FolderViewMode.ROWS) {
                _enrichingItemId.value = item.id
            }

            if (!externalMetaEnabled) {
                // No enrichment source is active — mark as failed so the hero
                // shows addon data immediately instead of waiting indefinitely.
                _failedEnrichmentIds.update { it + item.id }
                if (_enrichingItemId.value == item.id) _enrichingItemId.value = null
                return@launch
            }

            // External meta addon enrichment. The item is marked enriched only after the
            // fetch completes: a focus move cancels this job, and a cancelled item must
            // stay retryable.
            val metaResult = metaRepository.getMetaFromAllAddons(item.apiType, item.id, item.sourceAddonBaseUrl)
                .first { it is NetworkResult.Success || it is NetworkResult.Error }
            when {
                metaResult is NetworkResult.Success -> {
                    val meta = metaResult.data
                    updateItemInTabs(item.id) { it.mergedWith(meta) }
                }
                metaResult is NetworkResult.Error && metaResult.code == NetworkResult.SOURCE_SUFFICIENT_CODE -> {
                    // Catalog already has the best available meta — no update needed.
                }
                else -> {
                    // External meta also failed — mark as failed enrichment.
                    if (item.id !in _enrichedPreviews.value) {
                        _failedEnrichmentIds.update { it + item.id }
                    }
                }
            }

            if (_enrichingItemId.value == item.id) _enrichingItemId.value = null
            // Emit enriched preview for Modern expanded poster cards.
            publishEnrichedPreview(item.id)
            enrichedItemIds.add(item.id)
            // Sync enriched tabs into followLayoutHomeState for the rows mode.
            rebuildFollowLayoutState()
        }
    }

    /**
     * Resolves the trailer preview for the focused item. A request for another item
     * cancels the one in flight (Home's shape), so refocusing an item whose request
     * was superseded (A-B-A) starts a fresh request instead of being gated out.
     * Main-only.
     */
    fun requestTrailerPreview(
        itemId: String,
        title: String,
        releaseInfo: String?,
        apiType: String
    ) {
        if (itemId in trailerPreviewNegativeCache) return
        if (_trailerPreviewUrls.value.containsKey(itemId)) return
        if (trailerPreviewItemId == itemId && trailerPreviewJob?.isActive == true) return

        trailerPreviewJob?.cancel()
        trailerPreviewItemId = itemId
        trailerPreviewJob = viewModelScope.launch {
            val trailerSource = trailerService.getTrailerPlaybackSourceForItem(
                itemId = itemId,
                title = title,
                releaseInfo = releaseInfo,
                apiType = apiType
            )

            if (trailerSource?.videoUrl.isNullOrBlank()) {
                trailerPreviewNegativeCache.add(itemId)
                _trailerPreviewUrls.update { it - itemId }
                _trailerPreviewAudioUrls.update { it - itemId }
            } else {
                _trailerPreviewUrls.update { it + (itemId to trailerSource.videoUrl) }
                val audioUrl = trailerSource.audioUrl
                if (audioUrl.isNullOrBlank()) {
                    _trailerPreviewAudioUrls.update { it - itemId }
                } else {
                    _trailerPreviewAudioUrls.update { it + (itemId to audioUrl) }
                }
            }
        }
    }

    private fun updateItemInTabs(itemId: String, transform: (MetaPreview) -> MetaPreview) {
        _uiState.update { state ->
            var changed = false
            val updatedTabs = state.tabs.map { tab ->
                val row = tab.catalogRow ?: return@map tab
                val idx = row.items.indexOfFirst { it.id == itemId }
                if (idx < 0) return@map tab
                val merged = transform(row.items[idx])
                if (merged == row.items[idx]) return@map tab
                changed = true
                val items = row.items.toMutableList()
                items[idx] = merged
                tab.copy(catalogRow = row.copy(items = items))
            }
            if (changed) state.copy(tabs = updatedTabs) else state
        }
    }

    /** Publishes the item's current (possibly enriched) tab copy for the hero / expanded cards. */
    private fun publishEnrichedPreview(itemId: String) {
        val enrichedItem = _uiState.value.tabs
            .firstNotNullOfOrNull { tab -> tab.catalogRow?.items?.firstOrNull { it.id == itemId } }
            ?: return
        _enrichedPreviews.update { it + (itemId to enrichedItem) }
    }

    /**
     * The rows mode's failure Retry: reloads every source whose first page failed, in place
     * (the screen shows the rows skeleton meanwhile; the folder itself was read once).
     */
    fun retry() = reloadSourceTabs { it.tabs.indices.filter { index -> it.tabs[index].firstPageFailed } }

    /**
     * Per-tab Retry (Spec 28 Q8): reloads the first page of each failed source the tab covers
     * (the All tab covers every source). Tabs still loading or holding a row are left alone.
     */
    fun retrySource(tabIndex: Int) = reloadSourceTabs { it.retryableSourceTabIndices(tabIndex) }

    // Picking and resetting in one update makes a repeated press a no-op: reset tabs are loading.
    private fun reloadSourceTabs(indicesOf: (FolderDetailUiState) -> List<Int>) {
        var toLoad: List<Pair<Int, CollectionSource>> = emptyList()
        _uiState.update { s ->
            val indices = indicesOf(s)
            toLoad = indices.mapNotNull { i -> s.tabs[i].source?.let { i to it } }
            s.withSourceTabsReloading(indices)
        }
        if (toLoad.isEmpty()) return
        rebuildFollowLayoutState()
        toLoad.forEach { (index, source) -> loadSourceForTab(index, source) }
    }

    /**
     * Preloads enrichment data for an adjacent item (either neighbour in the row) so that
     * when the user navigates to it, the hero/backdrop data is already available.
     * Mirrors HomeViewModel.preloadAdjacentItem behavior.
     */
    fun preloadAdjacentItem(item: MetaPreview) {
        if (item.id in enrichedItemIds) return
        if (item.id in prefetchedExternalMetaIds) return

        // Both neighbours of a settled card (upstream-sync T28 P29); a superseded one is evicted.
        adjacentItemPrefetchJobs.launch(viewModelScope, item.id, kotlinx.coroutines.Dispatchers.IO) {
            kotlinx.coroutines.delay(600)
            if (item.id in prefetchedExternalMetaIds) return@launch

            val externalMetaEnabled = layoutPreferenceDataStore.preferExternalMetaAddonDetail.first()

            if (!externalMetaEnabled) return@launch

            val result = metaRepository.getMetaFromAllAddons(item.apiType, item.id, item.sourceAddonBaseUrl)
                .first { it is NetworkResult.Success || it is NetworkResult.Error }
            // Marked only once the fetch completed: a cancelled prefetch stays retryable.
            prefetchedExternalMetaIds.add(item.id)
            if (result is NetworkResult.Success) {
                enrichedItemIds.add(item.id)
                val meta = result.data
                updateItemInTabs(item.id) { it.mergedWith(meta) }
                publishEnrichedPreview(item.id)
            } else if (result is NetworkResult.Error && result.code == NetworkResult.SOURCE_SUFFICIENT_CODE) {
                enrichedItemIds.add(item.id)
                publishEnrichedPreview(item.id)
            }
        }
    }

}

private const val PLACEHOLDER_ITEM_COUNT = 8

// loadFolder prepends the All tab only when it shows (two or more loadable sources).
private val FolderDetailUiState.hasAllTab: Boolean
    get() = tabs.firstOrNull()?.isAllTab == true

/** What the Rows display mode draws (Spec 28 Q8 folder states; stage 2-4). */
internal enum class FolderRowsState { LOADING, NO_SOURCES, NO_KEY, FAILED, EMPTY, ROWS }

/**
 * Rows as soon as any source has items (the still-loading ones show shimmer rows); before
 * that the skeleton while a source loads; then the no-key message when a TMDB source failed
 * for want of a key, a failure (Retry) when a first page failed, else empty. No source tabs
 * at all (none, or Trakt only) is its own message.
 */
internal fun FolderDetailUiState.rowsState(): FolderRowsState {
    val sourceTabs = tabs.filter { !it.isAllTab }
    return when {
        sourceTabs.isEmpty() -> FolderRowsState.NO_SOURCES
        sourceTabs.any { it.catalogRow?.items?.isNotEmpty() == true } -> FolderRowsState.ROWS
        sourceTabs.any { it.isLoading } -> FolderRowsState.LOADING
        // The one failure the user can fix here (Spec 52 T1); a key also lets the rest retry.
        sourceTabs.any { it.waitsForTmdbKey } -> FolderRowsState.NO_KEY
        sourceTabs.any { it.firstPageFailed } -> FolderRowsState.FAILED
        else -> FolderRowsState.EMPTY
    }
}

/** A source tab whose first page failed: settled, errored, never loaded content. */
private val FolderTab.firstPageFailed: Boolean
    get() = !isAllTab && !isLoading && error != null && !hasLoadedContent

/** A source tab whose first page failed for want of a TMDB key: it reloads once one is saved. */
internal val FolderTab.waitsForTmdbKey: Boolean
    get() = firstPageFailed && needsTmdbKey

/** Source tabs [retrySource] reloads for the tab at [tabIndex]; the All tab covers every source tab. */
internal fun FolderDetailUiState.retryableSourceTabIndices(tabIndex: Int): List<Int> {
    val tab = tabs.getOrNull(tabIndex) ?: return emptyList()
    return if (tab.isAllTab) {
        tabs.indices.filter { tabs[it].firstPageFailed }
    } else {
        listOfNotNull(tabIndex.takeIf { tab.firstPageFailed })
    }
}

/** Puts the tabs at [indices] back to their first-page loading state, then rebuilds the All tab. */
internal fun FolderDetailUiState.withSourceTabsReloading(indices: List<Int>): FolderDetailUiState {
    if (indices.isEmpty()) return this
    val reloading = tabs.mapIndexed { index, tab ->
        if (index in indices) {
            tab.copy(isLoading = true, error = null, catalogRow = null, hasLoadedContent = false, needsTmdbKey = false)
        } else {
            tab
        }
    }
    return copy(tabs = reloading).withRebuiltAllTab()
}

private val FolderDetailUiState.hideUnreleased: Boolean
    get() = layoutPrefs?.hideUnreleasedContent == true

/**
 * Rebuilds the All tab from the source tabs. While any source is still loading and the
 * All tab already shows items, it only flags the All row as loading; once every load
 * settled it round-robin merges the loaded rows and APPENDS the new items to the
 * existing All content (stable scroll position). Unchanged when there is no All tab.
 * With no settled source row it waits (loading) while any source loads, then settles
 * rowless: carrying the first source error when every source failed, else empty.
 */
internal fun FolderDetailUiState.withRebuiltAllTab(): FolderDetailUiState {
    if (!hasAllTab) return this
    val sourceTabs = tabs.drop(1) // skip the All tab
    val anyLoading = sourceTabs.any { tab ->
        tab.catalogRow?.isLoading == true || tab.isLoading
    }
    // Only settled rows (a row mid-pagination is excluded until its page lands)
    val loadedRows = sourceTabs.mapNotNull { tab ->
        tab.catalogRow?.takeIf { !it.isLoading }
    }
    val allTab = tabs[0]
    val currentAllRow = allTab.catalogRow
    // A shown All row stays (flagged loading) while a page or a source is in flight.
    val keepsAllRow = anyLoading && currentAllRow != null && currentAllRow.items.isNotEmpty()
    if (loadedRows.isEmpty() && !keepsAllRow) {
        val rowless = if (anyLoading) {
            allTab.copy(isLoading = true, error = null, catalogRow = null, needsTmdbKey = false)
        } else {
            allTab.copy(
                isLoading = false,
                error = if (allSourcesFailed) sourceTabs.first().error else null,
                catalogRow = null,
                needsTmdbKey = allSourcesFailed && sourceTabs.any { it.needsTmdbKey }
            )
        }
        return if (rowless == allTab) this else copy(tabs = listOf(rowless) + sourceTabs)
    }

    val rebuiltAllTab = if (keepsAllRow) {
        allTab.copy(isLoading = false, error = null, catalogRow = currentAllRow.copy(isLoading = true), needsTmdbKey = false)
    } else {
        val mergedItems = roundRobinMerge(loadedRows.map { it.items })
        val existingItems = currentAllRow?.items.orEmpty()
        val existingIds = existingItems.mapTo(mutableSetOf()) { it.id }
        val newItems = mergedItems.filter { it.id !in existingIds }
        val mergedRow = loadedRows.first().copy(
            catalogName = "All",
            items = existingItems + newItems,
            hasMore = sourceTabs.any { tab -> tab.catalogRow?.hasMore == true },
            isLoading = false
        )
        allTab.copy(catalogRow = mergedRow, isLoading = false, error = null, needsTmdbKey = false)
    }
    return copy(tabs = listOf(rebuiltAllTab) + sourceTabs)
}

private fun roundRobinMerge(lists: List<List<MetaPreview>>): List<MetaPreview> {
    val result = mutableListOf<MetaPreview>()
    val seen = mutableSetOf<String>()
    val maxSize = lists.maxOfOrNull { it.size } ?: 0
    for (i in 0 until maxSize) {
        for (list in lists) {
            val item = list.getOrNull(i) ?: continue
            if (seen.add(item.id)) {
                result.add(item)
            }
        }
    }
    return result
}

/**
 * One loading tab per loadable folder source. Trakt sources are skipped: the integration
 * was removed, so they never load (Spec 28 Q3; the model, import and sync keep them).
 * Tabs start with no catalogRow: the follow layout draws a shimmer row for a loading tab
 * and drops a tab whose source fails (followLayoutRows). A row seeded here outlives a
 * failed load as an endless shimmer (L4-01).
 */
internal fun buildSourceTabs(
    sources: List<CollectionSource>,
    addons: List<Addon>,
    addonTabLabels: (AddonCatalogCollectionSource, String?) -> Pair<String, String>,
    tmdbTypeLabel: (TmdbCollectionSource) -> String
): List<FolderTab> = sources.mapNotNull { source ->
    when (source) {
        is AddonCatalogCollectionSource -> {
            val addon = addons.find { it.id == source.addonId }
            val catalog = addon?.catalogs?.find { it.id == source.catalogId && it.apiType == source.type }
                ?: addon?.catalogs?.find { it.id == source.catalogId.substringBefore(",") && it.apiType == source.type }
                ?: addons.firstNotNullOfOrNull { a -> a.catalogs.find { it.id == source.catalogId && it.apiType == source.type } }
            val (label, typeLabel) = addonTabLabels(source, catalog?.name)
            FolderTab(
                label = label,
                typeLabel = typeLabel,
                rawType = source.type,
                source = source,
                addonBaseUrl = addon?.baseUrl.orEmpty()
            )
        }
        is TmdbCollectionSource -> FolderTab(
            label = source.title,
            typeLabel = tmdbTypeLabel(source),
            rawType = source.mediaType.value.toCollectionRawType(),
            source = source
        )
        is TraktCollectionSource -> null
    }
}

private fun String.toCollectionRawType(): String {
    return if (lowercase() == "tv") "series" else this
}

/**
 * The follow layout's rows: each source tab's loaded row, a shimmer placeholder while
 * its first page loads, and nothing for a tab with neither (a failed source).
 */
internal fun followLayoutRows(sourceTabs: List<FolderTab>): List<CatalogRow> =
    sourceTabs.mapNotNull { tab ->
        when {
            tab.catalogRow != null -> tab.catalogRow
            tab.isLoading -> placeholderRow(tab)
            else -> null
        }
    }

/** Shimmer row for a loading tab, keyed (stableKey) like the row its source loads. */
private fun placeholderRow(tab: FolderTab): CatalogRow {
    val (addonId, catalogId) = when (val source = tab.source) {
        is AddonCatalogCollectionSource -> source.addonId to source.catalogId
        is TmdbCollectionSource -> "tmdb" to source.rowCatalogId()
        // buildSourceTabs builds no Trakt tab.
        is TraktCollectionSource, null -> "placeholder" to tab.label
    }
    val apiType = tab.rawType.ifBlank { "movie" }
    val contentType = ContentType.fromString(apiType)
    return CatalogRow(
        addonId = addonId,
        addonName = "",
        addonBaseUrl = tab.addonBaseUrl,
        catalogId = catalogId,
        catalogName = tab.label,
        type = contentType,
        rawType = apiType,
        items = List(PLACEHOLDER_ITEM_COUNT) { i ->
            MetaPreview(
                id = "${PLACEHOLDER_ITEM_ID_PREFIX}${catalogId}_$i",
                type = contentType,
                rawType = apiType,
                name = " ",
                poster = PLACEHOLDER_IMAGE_URL,
                posterShape = PosterShape.POSTER,
                background = null,
                logo = null,
                description = null,
                releaseInfo = " ",
                imdbRating = null,
                genres = emptyList()
            )
        },
        isLoading = true,
        hasMore = false
    )
}

/**
 * External-meta merge for FolderDetail: non-blank meta fields win. Its field set is
 * narrower than Home's mergeItem; unifying the two is deferred (wave U).
 */
private fun MetaPreview.mergedWith(meta: Meta): MetaPreview = copy(
    name = meta.name.takeIf { it.isNotBlank() } ?: name,
    description = meta.description?.takeIf { it.isNotBlank() } ?: description,
    background = meta.background?.takeIf { it.isNotBlank() } ?: background,
    logo = meta.logo?.takeIf { it.isNotBlank() } ?: logo,
    genres = meta.genres.takeIf { it.isNotEmpty() } ?: genres,
    imdbRating = meta.imdbRating ?: imdbRating,
    releaseInfo = meta.releaseInfo?.takeIf { it.isNotBlank() } ?: releaseInfo
)

/**
 * Drops unreleased items from a freshly-loaded row when the user toggle is on.
 * [treatMissingDateAsUnreleased] additionally drops items that have no release
 * information at all — used for TMDB- and Trakt-resolved rows, where a missing
 * release date means the title is unannounced (#2793). Addon rows keep the
 * lenient behavior because sparse addon metadata often omits dates for
 * released content.
 */
private fun CatalogRow.filteredForRelease(
    hideUnreleased: Boolean,
    treatMissingDateAsUnreleased: Boolean = false
): CatalogRow {
    if (!hideUnreleased) return this
    val today = java.time.LocalDate.now()
    val filtered = items.filterNot { item ->
        item.isUnreleased(today) || (treatMissingDateAsUnreleased && item.hasNoReleaseInfo())
    }
    return if (filtered.size == items.size) this else copy(items = filtered)
}
