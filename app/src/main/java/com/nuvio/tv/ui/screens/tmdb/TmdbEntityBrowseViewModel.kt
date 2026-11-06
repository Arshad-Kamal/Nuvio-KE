package com.nuvio.tv.ui.screens.tmdb

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.LocaleCache
import com.nuvio.tv.R
import com.nuvio.tv.core.tmdb.TmdbApiKeyProvider
import com.nuvio.tv.core.tmdb.TmdbEntityBrowseData
import com.nuvio.tv.core.tmdb.TmdbEntityKind
import com.nuvio.tv.core.tmdb.TmdbEntityMediaType
import com.nuvio.tv.core.tmdb.TmdbEntityRail
import com.nuvio.tv.core.tmdb.TmdbEntityRailType
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tmdb.awaitKey
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.ui.screens.collection.LatestWinsSerialBuilds
import com.nuvio.tv.ui.screens.home.HomeRow
import com.nuvio.tv.ui.screens.home.HomeScreenFocusState
import com.nuvio.tv.ui.screens.home.HomeUiState
import com.nuvio.tv.ui.screens.home.LayoutUiPrefs
import com.nuvio.tv.ui.screens.home.ModernCarouselRowBuildCache
import com.nuvio.tv.ui.screens.home.ModernHomePresentationInput
import com.nuvio.tv.ui.screens.home.buildModernHomePresentation
import com.nuvio.tv.ui.screens.home.layoutUiPrefsFlow
import com.nuvio.tv.ui.screens.home.savedHomeFocusState
import com.nuvio.tv.ui.screens.home.withLayoutPrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TmdbEntityBrowseViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val tmdbMetadataService: TmdbMetadataService,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val tmdbApiKeys: TmdbApiKeyProvider,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val watchProgressRepository: com.nuvio.tv.domain.repository.WatchProgressRepository,
    private val watchedSeriesStateHolder: com.nuvio.tv.data.local.WatchedSeriesStateHolder,
    val posterOptions: com.nuvio.tv.ui.components.posteroptions.PosterOptionsController,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val inFlightRailLoads = mutableSetOf<String>()

    val entityKind: TmdbEntityKind = TmdbEntityKind.fromRouteValue(
        savedStateHandle.get<String>("entityKind").orEmpty()
    )
    val entityId: Int = savedStateHandle.get<Int>("entityId") ?: 0
    // Navigation already decoded the arg once; decoding again would turn '+' into a space.
    val entityName: String = savedStateHandle.get<String>("entityName").orEmpty()
    val sourceType: String = savedStateHandle.get<String>("sourceType").orEmpty()
    // The breadcrumb's source title + section (Spec 29b).
    val sourceTitle: String = savedStateHandle.get<String>("sourceTitle").orEmpty()
    val sectionLabel: String = savedStateHandle.get<String>("sectionLabel").orEmpty()
    // The header frame's breadcrumb (stage 7): "Tears of Steel › Studios"; null when the route has neither.
    val breadcrumb: List<String>? = listOf(sourceTitle, sectionLabel).filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }

    private val _watchedMovieIds = MutableStateFlow<Set<String>>(emptySet())
    val watchedMovieIds: StateFlow<Set<String>> = _watchedMovieIds.asStateFlow()
    private val _watchedSeriesIds = MutableStateFlow<Set<String>>(emptySet())
    val watchedSeriesIds: StateFlow<Set<String>> = _watchedSeriesIds.asStateFlow()

    private val _uiState = MutableStateFlow<TmdbEntityBrowseUiState>(TmdbEntityBrowseUiState.Loading)
    val uiState: StateFlow<TmdbEntityBrowseUiState> = _uiState.asStateFlow()

    // Rows for MarqueeHomeContent (Spec 29g), built as FolderDetail builds its Rows mode. The
    // presentation cache is plain maps: LatestWinsSerialBuilds runs one build at a time and lets
    // only the newest publish. Prefs are read once per load (main thread only).
    private var layoutPrefs: LayoutUiPrefs? = null
    private val rowBuildCache = ModernCarouselRowBuildCache()
    private val rowBuilds = LatestWinsSerialBuilds(scope = viewModelScope, baseDispatcher = Dispatchers.Default)

    // MarqueeHomeContent's focus memory, kept here so a return from Detail restores the card.
    private val _focusState = MutableStateFlow(HomeScreenFocusState())
    val focusState: StateFlow<HomeScreenFocusState> = _focusState.asStateFlow()

    // Header frame or rows (Spec 29i), kept here so a return from Detail lands in the rows again.
    // Null until decided: entityEntryMode at load, else the description's first layout.
    private val _pageMode = MutableStateFlow<EntityPageMode?>(null)
    internal val pageMode: StateFlow<EntityPageMode?> = _pageMode.asStateFlow()

    init {
        posterOptions.bind(viewModelScope)
        load()
        viewModelScope.launch {
            watchProgressRepository.observeWatchedMovieIds()
                .collect { ids -> _watchedMovieIds.value = ids }
        }
        viewModelScope.launch {
            watchedSeriesStateHolder.fullyWatchedSeriesIds
                .collect { ids -> _watchedSeriesIds.value = ids }
        }
    }

    // The load shows Loading itself.
    fun retry() = load()

    internal fun setPageMode(mode: EntityPageMode) {
        _pageMode.value = mode
    }

    /** MarqueeHomeContent's load-more callback: the row's catalog id names the rail. */
    fun loadMoreForCatalog(catalogId: String, addonId: String) {
        val data = (_uiState.value as? TmdbEntityBrowseUiState.Success)?.data ?: return
        val rail = data.railForCatalog(catalogId, addonId) ?: return
        loadMoreRail(rail.mediaType, rail.railType)
    }

    fun saveFocusState(
        verticalScrollIndex: Int,
        verticalScrollOffset: Int,
        focusedRowKey: String?,
        focusedItemKeyByRow: Map<String, String>,
        catalogRowScrollStates: Map<String, Int>,
        focusedRowIndex: Int = 0,
        focusedItemIndex: Int = 0
    ) {
        _focusState.value = savedHomeFocusState(
            verticalScrollIndex = verticalScrollIndex,
            verticalScrollOffset = verticalScrollOffset,
            focusedRowKey = focusedRowKey,
            focusedItemKeyByRow = focusedItemKeyByRow,
            catalogRowScrollStates = catalogRowScrollStates,
            focusedRowIndex = focusedRowIndex,
            focusedItemIndex = focusedItemIndex
        )
    }

    private fun loadMoreRail(mediaType: TmdbEntityMediaType, railType: TmdbEntityRailType) {
        val railKey = entityRailCatalogId(mediaType, railType)
        val currentSuccess = _uiState.value as? TmdbEntityBrowseUiState.Success ?: return
        val targetRail = currentSuccess.data.rails.firstOrNull {
            it.mediaType == mediaType && it.railType == railType
        } ?: return
        if (!targetRail.hasMore || targetRail.isLoading || !inFlightRailLoads.add(railKey)) return

        updateRail(mediaType, railType) { it.copy(isLoading = true) }

        viewModelScope.launch {
            try {
                val language = tmdbSettingsDataStore.settings.first().language
                val nextPage = targetRail.currentPage + 1
                val pageResult = tmdbMetadataService.fetchEntityRailPage(
                    entityKind = entityKind,
                    entityId = entityId,
                    mediaType = mediaType,
                    railType = railType,
                    language = language,
                    page = nextPage
                )
                // Merged into the rail as it is now: another rail may have appended meanwhile.
                updateRail(mediaType, railType) { rail ->
                    rail.copy(
                        items = (rail.items + pageResult.items).distinctBy { it.id },
                        currentPage = nextPage,
                        hasMore = pageResult.hasMore,
                        isLoading = false
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                updateRail(mediaType, railType) { it.copy(isLoading = false) }
            } finally {
                inFlightRailLoads.remove(railKey)
            }
        }
    }

    // With no TMDB key the page waits for one (Spec 52 T1), then loads by itself.
    private fun load() {
        viewModelScope.launch {
            tmdbApiKeys.awaitKey { _uiState.value = TmdbEntityBrowseUiState.NeedsTmdbKey }
            _uiState.value = TmdbEntityBrowseUiState.Loading
            try {
                val language = tmdbSettingsDataStore.settings.first().language
                layoutPrefs = entityLayoutPrefs(layoutPreferenceDataStore.layoutUiPrefsFlow().first())
                // Null = the header and every rail failed (or nothing names the entity): Error.
                val browseData = tmdbMetadataService.fetchEntityBrowse(
                    entityKind = entityKind,
                    entityId = entityId,
                    sourceType = sourceType,
                    fallbackName = entityName,
                    language = language
                )
                if (browseData != null) {
                    if (_pageMode.value == null) _pageMode.value = entityEntryMode(browseData.header)
                    _uiState.value = TmdbEntityBrowseUiState.Success(
                        data = browseData,
                        subline = entityHeaderSubline(browseData.header)
                    )
                    rebuildRows()
                } else {
                    _uiState.value = TmdbEntityBrowseUiState.Error(
                        if (entityName.isNotBlank()) {
                            context.getString(R.string.tmdb_entity_error_load_named, entityName)
                        } else {
                            context.getString(R.string.tmdb_entity_error_load)
                        }
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = TmdbEntityBrowseUiState.Error(
                    e.message ?: context.getString(R.string.tmdb_entity_error_load)
                )
            }
        }
    }

    /** Applies [transform] to one rail of the loaded page (no-op otherwise), then rebuilds the rows. */
    private fun updateRail(
        mediaType: TmdbEntityMediaType,
        railType: TmdbEntityRailType,
        transform: (TmdbEntityRail) -> TmdbEntityRail
    ) {
        _uiState.update { state ->
            if (state is TmdbEntityBrowseUiState.Success) {
                state.copy(data = state.data.withUpdatedRail(mediaType, railType, transform))
            } else {
                state
            }
        }
        rebuildRows()
    }

    private fun rebuildRows() {
        val prefs = layoutPrefs ?: return
        rowBuilds.launch(
            build = { buildRows(prefs) },
            publish = { homeState ->
                _uiState.update { state ->
                    if (state is TmdbEntityBrowseUiState.Success) state.copy(homeState = homeState) else state
                }
            }
        )
    }

    /** Runs on the single build worker and reads the rails when it runs, so the newest build sees the newest rails. */
    private fun buildRows(prefs: LayoutUiPrefs): HomeUiState? {
        val data = (_uiState.value as? TmdbEntityBrowseUiState.Success)?.data ?: return null
        val catalogRows = entityCatalogRows(data.rails, ::rowName)
        val homeRows = catalogRows.map { HomeRow.Catalog(it) }
        val presentation = buildModernHomePresentation(
            input = ModernHomePresentationInput(
                homeRows = homeRows,
                catalogRows = catalogRows,
                continueWatchingItems = emptyList(),
                upcomingItems = emptyList(),
                useLandscapePosters = prefs.modernLandscapePostersEnabled,
                showCatalogTypeSuffix = prefs.catalogTypeSuffixEnabled,
                showAddonName = prefs.catalogAddonNameEnabled,
                showFullReleaseDate = prefs.showFullReleaseDate,
                localeTag = LocaleCache.localeTag
            ),
            cache = rowBuildCache,
            context = context
        )
        return HomeUiState(
            catalogRows = catalogRows,
            homeRows = homeRows,
            isLoading = false,
            heroSectionEnabled = false,
            modernHomePresentation = presentation
        ).withLayoutPrefs(prefs)
    }

    /** "Popular · Movies" (stage 8). */
    private fun rowName(mediaType: TmdbEntityMediaType, railType: TmdbEntityRailType): String =
        context.getString(
            R.string.tmdb_entity_row_title,
            context.getString(
                when (railType) {
                    TmdbEntityRailType.POPULAR -> R.string.tmdb_entity_rail_popular
                    TmdbEntityRailType.TOP_RATED -> R.string.tmdb_entity_rail_top_rated
                    TmdbEntityRailType.RECENT -> R.string.tmdb_entity_rail_recent
                }
            ),
            context.getString(
                when (mediaType) {
                    TmdbEntityMediaType.MOVIE -> R.string.type_movies
                    TmdbEntityMediaType.TV -> R.string.type_series_plural
                }
            )
        )

    private fun TmdbEntityBrowseData.withUpdatedRail(
        mediaType: TmdbEntityMediaType,
        railType: TmdbEntityRailType,
        transform: (TmdbEntityRail) -> TmdbEntityRail
    ): TmdbEntityBrowseData {
        return copy(
            rails = rails.map { rail ->
                if (rail.mediaType == mediaType && rail.railType == railType) {
                    transform(rail)
                } else {
                    rail
                }
            }
        )
    }
}
