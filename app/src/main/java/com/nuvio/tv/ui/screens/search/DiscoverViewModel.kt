package com.nuvio.tv.ui.screens.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.util.isUnreleased
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.WatchedSeriesStateHolder
import com.nuvio.tv.domain.model.DiscoverLocation
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.model.isNoFilterExtraValue
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import com.nuvio.tv.ui.components.MetaFocusPrefetcher
import com.nuvio.tv.ui.components.posteroptions.PosterOptionsController
import com.nuvio.tv.ui.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/**
 * Discover (the top-bar tab, or the route Search's Discover button opens). Wave C S6b: split out
 * of SearchViewModel, which no longer renders or loads Discover.
 */
@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository,
    private val metaPrefetcher: MetaFocusPrefetcher,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val watchProgressRepository: WatchProgressRepository,
    private val watchedSeriesStateHolder: WatchedSeriesStateHolder,
    val posterOptions: PosterOptionsController,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiscoverUiState())
    val uiState: StateFlow<DiscoverUiState> = _uiState.asStateFlow()

    private val _watchedMovieIds = MutableStateFlow<Set<String>>(emptySet())
    val watchedMovieIds: StateFlow<Set<String>> = _watchedMovieIds.asStateFlow()
    val watchedSeriesIds: StateFlow<Set<String>> = watchedSeriesStateHolder.fullyWatchedSeriesIds

    private var discoverJob: Job? = null
    // The catalog-list load (addon read + first fetch); cancelled with discoverJob on OFF so a
    // load suspended on the addon read cannot publish into the reset state (C6-R1).
    private var discoverLoadJob: Job? = null
    private var revealBatchAfterNextDiscoverFetch = false
    private var hideUnreleasedContent = false
    private var genreRouteArgs: Pair<String, String>? = run {
        val type = savedStateHandle.get<String>(Screen.Discover.TYPE_ARG)?.takeIf { it.isNotBlank() }
        val genre = savedStateHandle.get<String>(Screen.Discover.GENRE_ARG)?.takeIf { it.isNotBlank() }
        if (type != null && genre != null) type to genre else null
    }

    private companion object {
        const val DISCOVER_INITIAL_LIMIT = 100
        const val DISCOVER_SHOW_MORE_BATCH = 50
    }

    init {
        posterOptions.bind(viewModelScope)
        metaPrefetcher.bind(viewModelScope)
        viewModelScope.launch {
            watchProgressRepository.observeWatchedMovieIds()
                .collect { ids -> _watchedMovieIds.value = ids }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.discoverLocation.distinctUntilChanged().collectLatest { location ->
                _uiState.update { it.copy(discoverLocation = location) }
                if (location == DiscoverLocation.OFF) {
                    discoverLoadJob?.cancel()
                    discoverLoadJob = null
                    discoverJob?.cancel()
                    discoverJob = null
                    revealBatchAfterNextDiscoverFetch = false
                    // Every Discover field back to its default; only the layout prefs carry over.
                    _uiState.update {
                        DiscoverUiState(
                            discoverLocation = location,
                            catalogTypeSuffixEnabled = it.catalogTypeSuffixEnabled,
                            landscapePostersEnabled = it.landscapePostersEnabled
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.catalogTypeSuffixEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(catalogTypeSuffixEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.modernLandscapePostersEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(landscapePostersEnabled = enabled) }
            }
        }
        // Applies from the next fetch; already-shown results are not refiltered.
        viewModelScope.launch {
            layoutPreferenceDataStore.hideUnreleasedContent.collectLatest { enabled ->
                hideUnreleasedContent = enabled
            }
        }
    }

    fun ensureDiscoverLoaded() {
        val state = _uiState.value
        if (state.discoverLocation == DiscoverLocation.OFF) return
        if (state.discoverInitialized || state.discoverLoading) return
        discoverLoadJob = viewModelScope.launch { loadDiscoverCatalogs() }
    }

    fun prefetchMetaOnFocus(id: String, type: String) = metaPrefetcher.prefetchOnFocus(id, type)

    private suspend fun loadDiscoverCatalogs() {
        if (_uiState.value.discoverLocation == DiscoverLocation.OFF) return
        _uiState.update { it.copy(discoverLoading = true) }
        val addons = try {
            addonRepository.getInstalledAddons().first().enabledAddons()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _uiState.update { it.copy(discoverInitialized = true, discoverLoading = false) }
            return
        }

        val discoverCatalogs = addons.toDiscoverCatalogs()

        val availableTypes = discoverCatalogs.map { it.type }.distinct()
        // Opened from Detail's About sheet (GENRE_ROUTE): that type's first catalog listing the
        // genre, the genre selected; consumed once, so a later reload keeps the user's picks.
        val genreTarget = genreRouteArgs?.let { (type, genre) -> discoverCatalogs.genreTarget(type, genre) }
        genreRouteArgs = null
        val currentType = _uiState.value.selectedDiscoverType
        val selectedType = genreTarget?.catalog?.type
            ?: if (currentType in availableTypes) currentType else availableTypes.firstOrNull() ?: "movie"
        val selectedCatalog = genreTarget?.catalog ?: pickDiscoverCatalog(
            catalogs = discoverCatalogs,
            selectedType = selectedType,
            preferredKey = _uiState.value.selectedDiscoverCatalogKey
        )

        _uiState.update {
            it.copy(
                discoverCatalogs = discoverCatalogs,
                selectedDiscoverType = selectedType,
                selectedDiscoverCatalogKey = selectedCatalog?.key,
                selectedDiscoverGenre = genreTarget?.genre,
                discoverInitialized = true,
                discoverLoading = false,
                discoverResults = emptyList(),
                pendingDiscoverResults = emptyList(),
                discoverHasMore = true
            )
        }
        fetchDiscoverContent(reset = true)
    }

    fun selectDiscoverType(type: String) {
        val catalogs = _uiState.value.discoverCatalogs
        val selectedCatalog = pickDiscoverCatalog(
            catalogs = catalogs,
            selectedType = type,
            preferredKey = _uiState.value.selectedDiscoverCatalogKey
        )
        _uiState.update {
            it.copy(
                selectedDiscoverType = type,
                selectedDiscoverCatalogKey = selectedCatalog?.key,
                selectedDiscoverGenre = null,
                discoverResults = emptyList(),
                pendingDiscoverResults = emptyList(),
                discoverHasMore = true
            )
        }
        fetchDiscoverContent(reset = true)
    }

    fun selectDiscoverCatalog(catalogKey: String) {
        val catalog = _uiState.value.discoverCatalogs.firstOrNull { it.key == catalogKey } ?: return
        _uiState.update {
            it.copy(
                selectedDiscoverCatalogKey = catalog.key,
                selectedDiscoverType = catalog.type,
                selectedDiscoverGenre = null,
                discoverResults = emptyList(),
                pendingDiscoverResults = emptyList(),
                discoverHasMore = true
            )
        }
        fetchDiscoverContent(reset = true)
    }

    fun selectDiscoverGenre(genre: String?) {
        _uiState.update {
            it.copy(
                selectedDiscoverGenre = genre,
                discoverResults = emptyList(),
                pendingDiscoverResults = emptyList(),
                discoverHasMore = true
            )
        }
        fetchDiscoverContent(reset = true)
    }

    fun loadNextDiscoverResults() {
        if (_uiState.value.pendingDiscoverResults.isNotEmpty()) {
            showMoreDiscoverResults()
        } else {
            revealBatchAfterNextDiscoverFetch = true
            loadMoreDiscoverResults()
        }
    }

    private fun showMoreDiscoverResults() {
        val pending = _uiState.value.pendingDiscoverResults
        if (pending.isEmpty()) return
        val nextBatch = pending.take(DISCOVER_SHOW_MORE_BATCH)
        val remaining = pending.drop(DISCOVER_SHOW_MORE_BATCH)
        _uiState.update {
            it.copy(
                discoverResults = it.discoverResults + nextBatch,
                pendingDiscoverResults = remaining
            )
        }
    }

    private fun loadMoreDiscoverResults() {
        val state = _uiState.value
        if (!state.discoverHasMore || state.discoverLoadingMore || state.pendingDiscoverResults.isNotEmpty()) return
        fetchDiscoverContent(reset = false)
    }

    private fun fetchDiscoverContent(reset: Boolean) {
        discoverJob?.cancel()
        discoverJob = viewModelScope.launch {
            val state = _uiState.value
            val selectedCatalog = state.discoverCatalogs.firstOrNull { it.key == state.selectedDiscoverCatalogKey }
                ?: return@launch

            if (reset) {
                revealBatchAfterNextDiscoverFetch = false
                _uiState.update {
                    it.copy(
                        discoverLoading = true,
                        discoverResults = emptyList(),
                        pendingDiscoverResults = emptyList(),
                        discoverNextSkip = 0,
                        discoverHasMore = true
                    )
                }
            } else {
                _uiState.update { it.copy(discoverLoadingMore = true) }
            }

            // Same rule as home rows: advance by what the addon actually returned,
            // not a fixed skipStep, so pages smaller than skipStep lose nothing.
            val skip = if (reset) 0 else state.discoverNextSkip
            val visibleCountBeforeRequest = state.discoverResults.size
            val extraArgs = buildMap<String, String> {
                putAll(selectedCatalog.defaultExtraArgs)
                state.selectedDiscoverGenre?.takeIf { it.isNotBlank() }?.let { genre ->
                    // "None" is the addon's no-filter option: drop the genre, never send it.
                    if (isNoFilterExtraValue(genre)) remove("genre") else put("genre", genre)
                }
            }

            catalogRepository.getCatalog(
                addonBaseUrl = selectedCatalog.addonBaseUrl,
                addonId = selectedCatalog.addonId,
                addonName = selectedCatalog.addonName,
                catalogId = selectedCatalog.catalogId,
                catalogName = selectedCatalog.catalogName,
                type = selectedCatalog.type,
                skip = skip,
                skipStep = selectedCatalog.skipStep,
                extraArgs = extraArgs,
                supportsSkip = selectedCatalog.supportsSkip
            ).collect { result ->
                if (_uiState.value.discoverLocation == DiscoverLocation.OFF) return@collect
                when (result) {
                    is NetworkResult.Success -> {
                        val incoming = result.data.items
                        val existing = if (reset) {
                            emptyList()
                        } else {
                            _uiState.value.discoverResults + _uiState.value.pendingDiscoverResults
                        }
                        val existingKeys = existing.asSequence()
                            .map { "${it.apiType}:${it.id}" }
                            .toSet()
                        val hasNewUniqueIncoming = incoming.any { item ->
                            "${item.apiType}:${item.id}" !in existingKeys
                        }
                        val merged = if (reset) incoming else (existing + incoming)
                        val rawDeduped = merged.distinctBy { "${it.apiType}:${it.id}" }
                        val deduped = if (hideUnreleasedContent) {
                            val today = LocalDate.now()
                            rawDeduped.filterNot { it.isUnreleased(today) }
                        } else {
                            rawDeduped
                        }
                        val shouldRevealBatch = !reset && revealBatchAfterNextDiscoverFetch
                        val visibleLimit = if (reset) {
                            DISCOVER_INITIAL_LIMIT
                        } else if (shouldRevealBatch) {
                            (visibleCountBeforeRequest + DISCOVER_SHOW_MORE_BATCH)
                                .coerceAtLeast(DISCOVER_INITIAL_LIMIT)
                        } else {
                            visibleCountBeforeRequest.coerceAtLeast(DISCOVER_INITIAL_LIMIT)
                        }
                        val visible = deduped.take(visibleLimit)
                        val pending = deduped.drop(visibleLimit)
                        val shouldStopPagination = !reset && !hasNewUniqueIncoming
                        _uiState.update {
                            it.copy(
                                discoverLoading = false,
                                discoverLoadingMore = false,
                                discoverResults = visible,
                                pendingDiscoverResults = pending,
                                discoverHasMore = if (shouldStopPagination) false else result.data.hasMore,
                                discoverNextSkip = if (shouldStopPagination) it.discoverNextSkip else result.data.nextSkip
                            )
                        }
                        revealBatchAfterNextDiscoverFetch = false
                    }
                    is NetworkResult.Error -> {
                        revealBatchAfterNextDiscoverFetch = false
                        _uiState.update {
                            it.copy(
                                discoverLoading = false,
                                discoverLoadingMore = false,
                                discoverHasMore = false
                            )
                        }
                    }
                    NetworkResult.Loading -> Unit
                }
            }
        }
    }

    private fun pickDiscoverCatalog(
        catalogs: List<DiscoverCatalog>,
        selectedType: String,
        preferredKey: String?
    ): DiscoverCatalog? {
        val filtered = catalogs.filter { it.type == selectedType }
        return filtered.firstOrNull { it.key == preferredKey } ?: filtered.firstOrNull()
    }
}
