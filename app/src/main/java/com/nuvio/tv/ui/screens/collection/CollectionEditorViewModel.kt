package com.nuvio.tv.ui.screens.collection

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.sync.CollectionSyncService
import com.nuvio.tv.core.tmdb.TmdbCollectionSourceResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import com.nuvio.tv.data.remote.api.TmdbCollectionSearchResult
import com.nuvio.tv.data.remote.api.TmdbCompanySearchResult
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.AddonCatalogCollectionSource
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.CollectionFolder
import com.nuvio.tv.domain.model.CollectionSource
import com.nuvio.tv.domain.model.FolderViewMode
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.TmdbCollectionFilters
import com.nuvio.tv.domain.model.TmdbCollectionMediaType
import com.nuvio.tv.domain.model.TmdbCollectionSort
import com.nuvio.tv.domain.model.TmdbCollectionSource
import com.nuvio.tv.domain.model.TmdbCollectionSourceType
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.AddonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CollectionEditorUiState(
    val isNew: Boolean = true,
    val collectionId: String = "",
    val title: String = "",
    val backdropImageUrl: String = "",
    val pinToTop: Boolean = false,
    val focusGlowEnabled: Boolean = true,
    val viewMode: FolderViewMode = FolderViewMode.TABBED_GRID,
    val showAllTab: Boolean = true,
    val folders: List<CollectionFolder> = emptyList(),
    val isLoading: Boolean = true,
    val availableCatalogs: List<AvailableCatalog> = emptyList(),
    /** Friendly catalog/addon names for every installed addon catalog, keyed by
     *  `"addonId|type|catalogId"`. Unlike [availableCatalogs], this map is not
     *  filtered to picker-compatible catalogs. */
    val addonCatalogInfoByKey: Map<String, AddonCatalogInfo> = emptyMap(),
    val editingFolder: CollectionFolder? = null,
    val showFolderEditor: Boolean = false,
    val showCatalogPicker: Boolean = false,
    val showTmdbSourcePicker: Boolean = false,
    val editingTmdbSourceIndex: Int? = null,
    val tmdbBuilderMode: TmdbBuilderMode = TmdbBuilderMode.PRESETS,
    val tmdbInput: String = "",
    val tmdbTitleInput: String = "",
    val tmdbMediaType: TmdbCollectionMediaType = TmdbCollectionMediaType.MOVIE,
    val tmdbMediaBoth: Boolean = false,
    val tmdbSortBy: String = TmdbCollectionSort.POPULAR_DESC.value,
    val tmdbFilters: TmdbCollectionFilters = TmdbCollectionFilters(),
    val tmdbCompanyResults: List<TmdbCompanySearchResult> = emptyList(),
    val tmdbCollectionResults: List<TmdbCollectionSearchResult> = emptyList(),
    val tmdbSearchError: String? = null,
    /** The folder editor's Cover panel (u8 stage 12) is open. */
    val showCoverPanel: Boolean = false,
    /** The Card shape setting: folder tiles (preview, thumbs) are landscape when true. */
    val landscapePosters: Boolean = false
)

data class AvailableCatalog(
    val addonId: String,
    val addonName: String,
    val type: String,
    val catalogId: String,
    val catalogName: String,
    val genreOptions: List<String> = emptyList(),
    val genreRequired: Boolean = false
)

data class AddonCatalogInfo(
    val catalogName: String,
    val addonName: String
)

enum class TmdbBuilderMode {
    PRESETS,
    LIST,
    PRODUCTION,
    NETWORK,
    COLLECTION,
    PERSON,
    DIRECTOR,
    DISCOVER
}

/** Lists and collections sort in their own order by default (and offer it); the rest by popularity. */
internal val TmdbBuilderMode.usesOriginalOrder: Boolean
    get() = this == TmdbBuilderMode.LIST || this == TmdbBuilderMode.COLLECTION

data class TmdbPresetSource(
    val title: String,
    val source: TmdbCollectionSource
)

@HiltViewModel
class CollectionEditorViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    savedStateHandle: SavedStateHandle,
    private val collectionsDataStore: CollectionsDataStore,
    private val addonRepository: AddonRepository,
    private val tmdbCollectionSourceResolver: TmdbCollectionSourceResolver,
    private val collectionSyncService: CollectionSyncService,
    layoutPreferenceDataStore: LayoutPreferenceDataStore
) : ViewModel() {

    private val collectionIdArg: String = savedStateHandle["collectionId"] ?: ""

    private val _uiState = MutableStateFlow(CollectionEditorUiState())
    val uiState: StateFlow<CollectionEditorUiState> = _uiState.asStateFlow()

    // The drafts as opened, for "Discard changes?" (Spec 28 Q4). Null until loaded / no folder open.
    private var originalCollection: Collection? = null
    private var originalFolder: CollectionFolder? = null

    /**
     * The TMDB add waiting on a metadata fetch, and the running TMDB search. Opening, editing or
     * closing the TMDB panel, a mode change and leaving the folder cancel both ([stopTmdbWork]),
     * so a late result never lands in another panel session (Spec 28 28n). Any add while the
     * fetch runs is ignored.
     */
    private var tmdbAddJob: Job? = null
    private var tmdbSearchJob: Job? = null

    private fun launchTmdbAdd(block: suspend () -> Unit) {
        if (tmdbAddJob?.isActive == true) return
        tmdbAddJob = viewModelScope.launch { block() }
    }

    private fun stopTmdbWork() {
        tmdbAddJob?.cancel()
        tmdbSearchJob?.cancel()
    }

    init {
        loadData()
        viewModelScope.launch {
            layoutPreferenceDataStore.modernLandscapePostersEnabled
                .distinctUntilChanged()
                .collect { enabled -> _uiState.update { it.copy(landscapePosters = enabled) } }
        }
    }

    private fun string(resId: Int): String = appContext.getString(resId)

    private fun loadData() {
        viewModelScope.launch {
            val addons = addonRepository.getInstalledAddons().first().enabledAddons()
            val availableCatalogs = addons.flatMap { addon ->
                addon.catalogs
                    .filter { catalog ->
                        catalog.extra.none { extra -> extra.isRequired && !extra.name.equals("genre", ignoreCase = true) }
                    }
                    .map { catalog ->
                        val genreExtra = catalog.extra.firstOrNull { it.name.equals("genre", ignoreCase = true) }
                        AvailableCatalog(
                            addonId = addon.id,
                            addonName = addon.displayName,
                            type = catalog.apiType,
                            catalogId = catalog.id,
                            catalogName = catalog.name,
                            genreOptions = genreExtra?.options.orEmpty(),
                            genreRequired = genreExtra?.isRequired == true
                        )
                    }
            }
            val addonCatalogInfoByKey = addons.flatMap { addon ->
                addon.catalogs.map { catalog ->
                    "${addon.id}|${catalog.apiType}|${catalog.id}" to
                        AddonCatalogInfo(catalogName = catalog.name, addonName = addon.displayName)
                }
            }.toMap()

            if (collectionIdArg.isNotBlank()) {
                val collections = collectionsDataStore.collections.first()
                val existing = collections.find { it.id == collectionIdArg }
                if (existing != null) {
                    originalCollection = existing
                    _uiState.update {
                        it.copy(
                            isNew = false,
                            collectionId = existing.id,
                            title = existing.title,
                            backdropImageUrl = existing.backdropImageUrl ?: "",
                            pinToTop = existing.pinToTop,
                            focusGlowEnabled = existing.focusGlowEnabled,
                            viewMode = existing.viewMode,
                            showAllTab = existing.showAllTab,
                            folders = existing.folders,
                            availableCatalogs = availableCatalogs,
                            addonCatalogInfoByKey = addonCatalogInfoByKey,
                            isLoading = false
                        )
                    }
                    return@launch
                }
            }

            _uiState.update {
                it.copy(
                    isNew = true,
                    collectionId = collectionsDataStore.generateId(),
                    availableCatalogs = availableCatalogs,
                    addonCatalogInfoByKey = addonCatalogInfoByKey,
                    isLoading = false
                )
            }
            // The form's own defaults are the baseline, so they never read as an edit.
            originalCollection = _uiState.value.draftCollection()
        }
    }

    fun setTitle(title: String) {
        _uiState.update { it.copy(title = title) }
    }

    fun setBackdropImageUrl(url: String) {
        _uiState.update { it.copy(backdropImageUrl = url) }
    }

    fun setPinToTop(pinToTop: Boolean) {
        _uiState.update { it.copy(pinToTop = pinToTop) }
    }

    fun setFocusGlowEnabled(enabled: Boolean) {
        _uiState.update { it.copy(focusGlowEnabled = enabled) }
    }

    fun addFolder() {
        val newFolder = CollectionFolder(
            id = collectionsDataStore.generateId(),
            title = "",
            tileShape = PosterShape.POSTER,
            sources = emptyList()
        )
        originalFolder = newFolder
        _uiState.update {
            it.copy(editingFolder = newFolder, showFolderEditor = true)
        }
    }

    fun editFolder(folderId: String) {
        val folder = _uiState.value.folders.find { it.id == folderId } ?: return
        originalFolder = folder
        _uiState.update { it.copy(editingFolder = folder, showFolderEditor = true) }
    }

    fun removeFolder(folderId: String) {
        _uiState.update { state ->
            state.copy(folders = state.folders.filter { it.id != folderId })
        }
    }

    fun moveFolderUp(folderId: String) = moveFolder(folderId, -1)

    fun moveFolderDown(folderId: String) = moveFolder(folderId, 1)

    /** Moves the folder one place by [step] (by id, so a repeated press moves it again); no-op at an edge. */
    private fun moveFolder(folderId: String, step: Int) {
        _uiState.update { state ->
            val index = state.folders.indexOfFirst { it.id == folderId }
            val target = index + step
            if (index < 0 || target !in state.folders.indices) return@update state
            val folders = state.folders.toMutableList()
            folders.add(target, folders.removeAt(index))
            state.copy(folders = folders)
        }
    }

    fun updateFolderTitle(title: String) {
        _uiState.update { state ->
            state.copy(editingFolder = state.editingFolder?.copy(title = title))
        }
    }

    fun updateFolderCoverImage(url: String) {
        _uiState.update { state ->
            state.copy(editingFolder = state.editingFolder?.copy(
                coverImageUrl = url,
                coverEmoji = null
            ))
        }
    }

    fun updateFolderFocusGifUrl(url: String) {
        _uiState.update { state ->
            state.copy(editingFolder = state.editingFolder?.copy(focusGifUrl = url.ifBlank { null }))
        }
    }

    fun updateFolderFocusGifEnabled(enabled: Boolean) {
        _uiState.update { state ->
            state.copy(editingFolder = state.editingFolder?.copy(focusGifEnabled = enabled))
        }
    }

    fun updateFolderCoverEmoji(emoji: String) {
        _uiState.update { state ->
            state.copy(editingFolder = state.editingFolder?.copy(
                coverEmoji = emoji,
                coverImageUrl = null
            ))
        }
    }

    fun switchToImageMode() {
        _uiState.update { state ->
            state.copy(editingFolder = state.editingFolder?.copy(
                coverImageUrl = state.editingFolder.coverImageUrl ?: "",
                coverEmoji = null
            ))
        }
    }

    fun clearFolderCover() {
        _uiState.update { state ->
            state.copy(editingFolder = state.editingFolder?.copy(
                coverImageUrl = null,
                coverEmoji = null
            ))
        }
    }

    fun showCoverPanel() {
        _uiState.update { it.copy(showCoverPanel = true) }
    }

    fun hideCoverPanel() {
        _uiState.update { it.copy(showCoverPanel = false) }
    }

    fun updateFolderTileShape(shape: PosterShape) {
        _uiState.update { state ->
            state.copy(editingFolder = state.editingFolder?.copy(tileShape = shape))
        }
    }

    fun updateFolderHideTitle(hide: Boolean) {
        _uiState.update { state ->
            state.copy(editingFolder = state.editingFolder?.copy(hideTitle = hide))
        }
    }

    fun updateFolderHeroBackdropUrl(url: String) {
        _uiState.update { state ->
            state.copy(editingFolder = state.editingFolder?.copy(heroBackdropUrl = url.ifBlank { null }))
        }
    }

    fun updateFolderHeroVideoUrl(url: String) {
        _uiState.update { state ->
            state.copy(editingFolder = state.editingFolder?.copy(heroVideoUrl = url.ifBlank { null }))
        }
    }

    fun updateFolderTitleLogoUrl(url: String) {
        _uiState.update { state ->
            state.copy(editingFolder = state.editingFolder?.copy(titleLogoUrl = url.ifBlank { null }))
        }
    }

    /** Picking the mode already shown keeps the stored one (a Follow Home Layout collection shows Rows, Q1). */
    fun setViewMode(viewMode: FolderViewMode) {
        _uiState.update {
            if (it.viewMode.displayMode == viewMode.displayMode) it else it.copy(viewMode = viewMode)
        }
    }

    fun setShowAllTab(show: Boolean) {
        _uiState.update { it.copy(showAllTab = show) }
    }

    // Source actions address a source by its collectionSourceKey, resolved inside the update, so
    // a press acts on the source the panel shows even after a move (Spec 28 28l).

    fun removeCatalogSource(key: String) {
        _uiState.update { state ->
            val folder = state.editingFolder ?: return@update state
            val index = folder.sources.indexOfSourceKey(key)
            if (index < 0) return@update state
            state.copy(editingFolder = folder.copy(sources = folder.sources.toMutableList().also { it.removeAt(index) }))
        }
    }

    fun moveCatalogSourceUp(key: String) = moveCatalogSource(key, -1)

    fun moveCatalogSourceDown(key: String) = moveCatalogSource(key, 1)

    /** Moves the source one place by [step]; no-op at an edge. */
    private fun moveCatalogSource(key: String, step: Int) {
        _uiState.update { state ->
            val folder = state.editingFolder ?: return@update state
            val index = folder.sources.indexOfSourceKey(key)
            val target = index + step
            if (index < 0 || target !in folder.sources.indices) return@update state
            val sources = folder.sources.toMutableList()
            sources.add(target, sources.removeAt(index))
            state.copy(editingFolder = folder.copy(sources = sources))
        }
    }

    fun toggleCatalogSource(catalog: AvailableCatalog) {
        _uiState.update { state ->
            val folder = state.editingFolder ?: return@update state
            val existing = folder.sources.indexOfFirst { it is AddonCatalogCollectionSource && it.isFor(catalog) }
            val newSources = if (existing >= 0) {
                folder.sources.toMutableList().also { it.removeAt(existing) }
            } else {
                val defaultGenre = resolveGenreSelection(catalog, requestedGenre = null)
                folder.sources + AddonCatalogCollectionSource(
                    addonId = catalog.addonId,
                    type = catalog.type,
                    catalogId = catalog.catalogId,
                    genre = defaultGenre
                )
            }
            state.copy(editingFolder = folder.copy(sources = newSources))
        }
    }

    fun showCatalogPicker() {
        _uiState.update { it.copy(showCatalogPicker = true) }
    }

    fun hideCatalogPicker() {
        _uiState.update { it.copy(showCatalogPicker = false) }
    }

    fun showTmdbSourcePicker() {
        stopTmdbWork()
        _uiState.update {
            it.copy(
                showTmdbSourcePicker = true,
                editingTmdbSourceIndex = null,
                tmdbBuilderMode = TmdbBuilderMode.PRESETS,
                tmdbInput = "",
                tmdbTitleInput = "",
                tmdbMediaType = TmdbCollectionMediaType.MOVIE,
                tmdbMediaBoth = false,
                tmdbSortBy = TmdbCollectionSort.POPULAR_DESC.value,
                tmdbFilters = TmdbCollectionFilters(),
                tmdbCompanyResults = emptyList(),
                tmdbCollectionResults = emptyList(),
                tmdbSearchError = null
            )
        }
    }

    fun hideTmdbSourcePicker() {
        stopTmdbWork()
        _uiState.update { it.copy(showTmdbSourcePicker = false, editingTmdbSourceIndex = null, tmdbSearchError = null) }
    }

    fun editTmdbSource(key: String) {
        stopTmdbWork()
        _uiState.update { state ->
            val folder = state.editingFolder ?: return@update state
            val index = folder.sources.indexOfSourceKey(key)
            val source = folder.sources.getOrNull(index) as? TmdbCollectionSource ?: return@update state
            state.copy(
                showTmdbSourcePicker = true,
                editingTmdbSourceIndex = index,
                tmdbBuilderMode = source.sourceType.toBuilderMode(),
                tmdbInput = source.tmdbId?.toString().orEmpty(),
                tmdbTitleInput = source.title,
                tmdbMediaType = source.mediaType,
                tmdbMediaBoth = false,
                tmdbSortBy = source.sortBy,
                tmdbFilters = source.filters,
                tmdbCompanyResults = emptyList(),
                tmdbCollectionResults = emptyList(),
                tmdbSearchError = null
            )
        }
    }

    fun setTmdbBuilderMode(mode: TmdbBuilderMode) {
        stopTmdbWork()
        _uiState.update {
            val mediaType = when (mode) {
                TmdbBuilderMode.NETWORK -> TmdbCollectionMediaType.TV
                else -> TmdbCollectionMediaType.MOVIE
            }
            val sortBy = if (mode.usesOriginalOrder) {
                TmdbCollectionSort.ORIGINAL.value
            } else {
                TmdbCollectionSort.POPULAR_DESC.value
            }
            it.copy(
                tmdbBuilderMode = mode,
                tmdbInput = "",
                tmdbTitleInput = "",
                tmdbMediaType = mediaType,
                tmdbMediaBoth = false,
                tmdbSortBy = sortBy,
                tmdbCompanyResults = emptyList(),
                tmdbCollectionResults = emptyList(),
                tmdbSearchError = null
            )
        }
    }

    fun setTmdbInput(value: String) {
        _uiState.update { it.copy(tmdbInput = value) }
    }

    fun setTmdbTitleInput(value: String) {
        _uiState.update { it.copy(tmdbTitleInput = value) }
    }

    fun setTmdbMediaType(mediaType: TmdbCollectionMediaType) {
        _uiState.update { it.copy(tmdbMediaType = mediaType, tmdbMediaBoth = false) }
    }

    fun setTmdbMediaBoth(enabled: Boolean) {
        _uiState.update { it.copy(tmdbMediaBoth = enabled, tmdbMediaType = TmdbCollectionMediaType.MOVIE) }
    }

    fun setTmdbSortBy(sortBy: String) {
        _uiState.update { it.copy(tmdbSortBy = sortBy) }
    }

    fun setTmdbFilters(filters: TmdbCollectionFilters) {
        _uiState.update { it.copy(tmdbFilters = filters) }
    }

    fun searchTmdbCompanies() {
        val query = _uiState.value.tmdbInput.trim()
        if (query.isBlank()) return
        tmdbSearchJob?.cancel()
        tmdbSearchJob = viewModelScope.launch {
            val results = runCatching { tmdbCollectionSourceResolver.searchCompanies(query) }
                .onFailure { if (it is CancellationException) throw it }
            _uiState.update {
                it.copy(
                    tmdbCompanyResults = results.getOrDefault(emptyList()),
                    tmdbSearchError = results.exceptionOrNull()?.message
                )
            }
        }
    }

    fun searchTmdbCollections() {
        val query = _uiState.value.tmdbInput.trim()
        if (query.isBlank()) return
        tmdbSearchJob?.cancel()
        tmdbSearchJob = viewModelScope.launch {
            val results = runCatching { tmdbCollectionSourceResolver.searchCollections(query) }
                .onFailure { if (it is CancellationException) throw it }
            _uiState.update {
                it.copy(
                    tmdbCollectionResults = results.getOrDefault(emptyList()),
                    tmdbSearchError = results.exceptionOrNull()?.message
                )
            }
        }
    }

    fun addTmdbSource(source: TmdbCollectionSource) {
        if (source.tmdbId != null && source.sourceType in coverMetadataSourceTypes) {
            val launchFolderId = _uiState.value.editingFolder?.id ?: return
            launchTmdbAdd {
                val metadata = runCatching { importMetadataFor(source.sourceType, source.tmdbId) }
                    .onFailure { if (it is CancellationException) throw it }
                val resolved = metadata.getOrNull()
                addTmdbSourceToFolder(
                    source = if (source.title.isBlank()) source.copy(title = resolved?.title.orEmpty()) else source,
                    coverImageUrl = resolved?.coverImageUrl,
                    launchFolderId = launchFolderId
                )
            }
            return
        }
        addTmdbSourceToFolder(source)
    }

    fun addTmdbSources(sources: List<TmdbCollectionSource>) {
        val metadataSource = sources.firstOrNull { it.tmdbId != null && it.sourceType in coverMetadataSourceTypes }
        if (metadataSource != null) {
            val launchFolderId = _uiState.value.editingFolder?.id ?: return
            launchTmdbAdd {
                val metadata = runCatching { importMetadataFor(metadataSource.sourceType, metadataSource.tmdbId!!) }
                    .onFailure { if (it is CancellationException) throw it }
                val resolved = metadata.getOrNull()
                addTmdbSourcesToFolder(sources, resolved?.coverImageUrl, launchFolderId)
            }
            return
        }
        addTmdbSourcesToFolder(sources)
    }

    /** A Production search result: the company per the chosen Type ("<title> Movies" etc. for Both). */
    fun addTmdbCompany(id: Int, title: String) {
        val state = _uiState.value
        val mediaTypes = selectedMediaTypes(state, TmdbCollectionSourceType.COMPANY)
        addTmdbSources(
            mediaTypes.map { mediaType ->
                TmdbCollectionSource(
                    sourceType = TmdbCollectionSourceType.COMPANY,
                    title = titleForMedia(title, mediaType, addSuffix = mediaTypes.size > 1),
                    tmdbId = id,
                    mediaType = mediaType,
                    sortBy = state.tmdbSortBy,
                    filters = state.tmdbFilters
                )
            }
        )
    }

    /** A Collection search result. */
    fun addTmdbCollection(id: Int, title: String) {
        addTmdbSource(
            TmdbCollectionSource(
                sourceType = TmdbCollectionSourceType.COLLECTION,
                title = title,
                tmdbId = id,
                mediaType = TmdbCollectionMediaType.MOVIE,
                sortBy = _uiState.value.tmdbSortBy
            )
        )
    }

    private fun addTmdbSourceToFolder(
        source: TmdbCollectionSource,
        coverImageUrl: String? = null,
        launchFolderId: String? = null
    ) {
        addTmdbSourcesToFolder(listOf(source), coverImageUrl, launchFolderId)
    }

    /**
     * [launchFolderId] is set by the async TMDB-metadata paths: the folder being edited
     * when the fetch started. If a different folder (or none) is open on completion the
     * result is dropped instead of landing in the wrong folder (L4-87).
     */
    private fun addTmdbSourcesToFolder(
        sources: List<TmdbCollectionSource>,
        coverImageUrl: String? = null,
        launchFolderId: String? = null
    ) {
        // A direct add while a fetching one runs is ignored too (launchFolderId: the fetch itself).
        if (launchFolderId == null && tmdbAddJob?.isActive == true) return
        _uiState.update { state ->
            val folder = state.editingFolder ?: return@update state
            if (launchFolderId != null && folder.id != launchFolderId) return@update state
            val editingIndex = state.editingTmdbSourceIndex
            val newSources = sources.filterIndexed { sourceIndex, source ->
                folder.sources.noneIndexed { existingIndex, existing ->
                    existing == source && (editingIndex == null || existingIndex != editingIndex || sourceIndex > 0)
                }
            }
            if (newSources.isEmpty()) return@update state
            val shouldApplyCover = newSources.any { it.sourceType in coverMetadataSourceTypes } &&
                !coverImageUrl.isNullOrBlank() &&
                folder.coverImageUrl.isNullOrBlank()
            val updatedSources = if (
                editingIndex != null &&
                editingIndex in folder.sources.indices &&
                folder.sources[editingIndex] is TmdbCollectionSource
            ) {
                folder.sources.toMutableList().also {
                    it.removeAt(editingIndex)
                    it.addAll(editingIndex, newSources)
                }
            } else {
                folder.sources + newSources
            }
            val updatedFolder = if (shouldApplyCover) {
                folder.copy(
                    sources = updatedSources,
                    coverImageUrl = coverImageUrl,
                    coverEmoji = null
                )
            } else {
                folder.copy(sources = updatedSources)
            }
            state.copy(
                editingFolder = updatedFolder,
                showTmdbSourcePicker = false,
                editingTmdbSourceIndex = null,
                tmdbInput = "",
                tmdbTitleInput = "",
                tmdbSearchError = null
            )
        }
    }

    fun addTmdbSourceFromInput() {
        val state = _uiState.value
        val mode = state.tmdbBuilderMode
        val id = tmdbCollectionSourceResolver.parseTmdbId(state.tmdbInput)
        val title = state.tmdbTitleInput.ifBlank {
            when (mode) {
                TmdbBuilderMode.LIST -> "${string(R.string.collections_editor_tmdb_default_list)} ${id ?: ""}".trim()
                TmdbBuilderMode.NETWORK -> "${string(R.string.collections_editor_tmdb_default_network)} ${id ?: ""}".trim()
                TmdbBuilderMode.COLLECTION -> "${string(R.string.collections_editor_tmdb_collection)} ${id ?: ""}".trim()
                TmdbBuilderMode.PRODUCTION -> "${string(R.string.collections_editor_tmdb_default_production)} ${id ?: ""}".trim()
                TmdbBuilderMode.PERSON -> "${string(R.string.collections_editor_tmdb_person_credits)} ${id ?: ""}".trim()
                TmdbBuilderMode.DIRECTOR -> "${string(R.string.collections_editor_tmdb_director_credits)} ${id ?: ""}".trim()
                else -> string(R.string.collections_editor_tmdb_default_discover)
            }
        }
        val sourceType = when (mode) {
            TmdbBuilderMode.LIST -> TmdbCollectionSourceType.LIST
            TmdbBuilderMode.NETWORK -> TmdbCollectionSourceType.NETWORK
            TmdbBuilderMode.COLLECTION -> TmdbCollectionSourceType.COLLECTION
            TmdbBuilderMode.PRODUCTION -> TmdbCollectionSourceType.COMPANY
            TmdbBuilderMode.PERSON -> TmdbCollectionSourceType.PERSON
            TmdbBuilderMode.DIRECTOR -> TmdbCollectionSourceType.DIRECTOR
            else -> TmdbCollectionSourceType.DISCOVER
        }
        if (sourceType != TmdbCollectionSourceType.DISCOVER && id == null) {
            _uiState.update { it.copy(tmdbSearchError = string(R.string.collections_editor_error_valid_tmdb_id_or_url)) }
            return
        }
        val mediaType = when (sourceType) {
            TmdbCollectionSourceType.NETWORK -> TmdbCollectionMediaType.TV
            TmdbCollectionSourceType.COLLECTION,
            TmdbCollectionSourceType.LIST -> TmdbCollectionMediaType.MOVIE
            TmdbCollectionSourceType.PERSON,
            TmdbCollectionSourceType.DIRECTOR,
            TmdbCollectionSourceType.COMPANY,
            TmdbCollectionSourceType.DISCOVER -> state.tmdbMediaType
        }
        val mediaTypes = selectedMediaTypes(state, sourceType)
        if (sourceType == TmdbCollectionSourceType.LIST || sourceType == TmdbCollectionSourceType.COLLECTION) {
            val launchFolderId = state.editingFolder?.id ?: return
            launchTmdbAdd {
                val metadata = runCatching {
                    if (sourceType == TmdbCollectionSourceType.LIST) {
                        tmdbCollectionSourceResolver.listImportMetadata(id!!)
                    } else {
                        tmdbCollectionSourceResolver.collectionImportMetadata(id!!)
                    }
                }.onFailure { if (it is CancellationException) throw it }
                val resolved = metadata.getOrNull()
                if (metadata.isFailure) {
                    _uiState.update {
                        it.copy(
                            tmdbSearchError = metadata.exceptionOrNull()?.message
                                ?: string(R.string.collections_editor_error_load_tmdb_source)
                        )
                    }
                    return@launchTmdbAdd
                }
                addTmdbSourceToFolder(
                    source = TmdbCollectionSource(
                        sourceType = sourceType,
                        title = state.tmdbTitleInput.ifBlank { resolved?.title ?: title },
                        tmdbId = id,
                        mediaType = mediaType,
                        sortBy = state.tmdbSortBy,
                        filters = state.tmdbFilters
                    ),
                    coverImageUrl = resolved?.coverImageUrl,
                    launchFolderId = launchFolderId
                )
            }
            return
        }
        addTmdbSources(
            mediaTypes.map { type ->
                TmdbCollectionSource(
                    sourceType = sourceType,
                    title = titleForMedia(title, type, addSuffix = mediaTypes.size > 1),
                    tmdbId = id,
                    mediaType = type,
                    sortBy = state.tmdbSortBy,
                    filters = state.tmdbFilters
                )
            }
        )
    }

    fun addDiscoverSource() {
        val state = _uiState.value
        val baseTitle = state.tmdbTitleInput.ifBlank { string(R.string.collections_editor_tmdb_default_discover) }
        val mediaTypes = selectedMediaTypes(state, TmdbCollectionSourceType.DISCOVER)
        addTmdbSourcesToFolder(
            mediaTypes.map { mediaType ->
                TmdbCollectionSource(
                    sourceType = TmdbCollectionSourceType.DISCOVER,
                    title = titleForMedia(baseTitle, mediaType, addSuffix = mediaTypes.size > 1),
                    mediaType = mediaType,
                    sortBy = state.tmdbSortBy,
                    filters = state.tmdbFilters
                )
            }
        )
    }

    private fun selectedMediaTypes(
        state: CollectionEditorUiState,
        sourceType: TmdbCollectionSourceType
    ): List<TmdbCollectionMediaType> {
        return when (sourceType) {
            TmdbCollectionSourceType.COMPANY,
            TmdbCollectionSourceType.PERSON,
            TmdbCollectionSourceType.DIRECTOR,
            TmdbCollectionSourceType.DISCOVER -> if (state.tmdbMediaBoth) {
                listOf(TmdbCollectionMediaType.MOVIE, TmdbCollectionMediaType.TV)
            } else {
                listOf(state.tmdbMediaType)
            }
            TmdbCollectionSourceType.NETWORK -> listOf(TmdbCollectionMediaType.TV)
            TmdbCollectionSourceType.COLLECTION,
            TmdbCollectionSourceType.LIST -> listOf(TmdbCollectionMediaType.MOVIE)
        }
    }

    private fun titleForMedia(title: String, mediaType: TmdbCollectionMediaType, addSuffix: Boolean): String {
        if (!addSuffix) return title
        val suffix = when (mediaType) {
            TmdbCollectionMediaType.MOVIE -> string(R.string.type_movies)
            TmdbCollectionMediaType.TV -> string(R.string.type_series_plural)
        }
        return "$title $suffix"
    }

    private fun TmdbCollectionSourceType.toBuilderMode(): TmdbBuilderMode {
        return when (this) {
            TmdbCollectionSourceType.LIST -> TmdbBuilderMode.LIST
            TmdbCollectionSourceType.COLLECTION -> TmdbBuilderMode.COLLECTION
            TmdbCollectionSourceType.COMPANY -> TmdbBuilderMode.PRODUCTION
            TmdbCollectionSourceType.NETWORK -> TmdbBuilderMode.NETWORK
            TmdbCollectionSourceType.PERSON -> TmdbBuilderMode.PERSON
            TmdbCollectionSourceType.DIRECTOR -> TmdbBuilderMode.DIRECTOR
            TmdbCollectionSourceType.DISCOVER -> TmdbBuilderMode.DISCOVER
        }
    }

    private inline fun List<CollectionSource>.noneIndexed(predicate: (Int, CollectionSource) -> Boolean): Boolean {
        forEachIndexed { index, source ->
            if (predicate(index, source)) return false
        }
        return true
    }

    private suspend fun importMetadataFor(sourceType: TmdbCollectionSourceType, id: Int) = when (sourceType) {
        TmdbCollectionSourceType.COLLECTION -> tmdbCollectionSourceResolver.collectionImportMetadata(id)
        TmdbCollectionSourceType.COMPANY -> tmdbCollectionSourceResolver.companyImportMetadata(id)
        TmdbCollectionSourceType.NETWORK -> tmdbCollectionSourceResolver.networkImportMetadata(id)
        TmdbCollectionSourceType.PERSON,
        TmdbCollectionSourceType.DIRECTOR -> tmdbCollectionSourceResolver.personImportMetadata(id)
        TmdbCollectionSourceType.LIST -> tmdbCollectionSourceResolver.listImportMetadata(id)
        TmdbCollectionSourceType.DISCOVER -> null
    }

    private val coverMetadataSourceTypes = setOf(
        TmdbCollectionSourceType.COLLECTION,
        TmdbCollectionSourceType.COMPANY,
        TmdbCollectionSourceType.NETWORK,
        TmdbCollectionSourceType.PERSON,
        TmdbCollectionSourceType.DIRECTOR
    )

    fun tmdbPresets(): List<TmdbPresetSource> = listOf(
        TmdbPresetSource("Marvel Studios", TmdbCollectionSource(TmdbCollectionSourceType.COMPANY, "Marvel Studios", 420, TmdbCollectionMediaType.MOVIE)),
        TmdbPresetSource("Walt Disney Pictures", TmdbCollectionSource(TmdbCollectionSourceType.COMPANY, "Walt Disney Pictures", 2, TmdbCollectionMediaType.MOVIE)),
        TmdbPresetSource("Pixar", TmdbCollectionSource(TmdbCollectionSourceType.COMPANY, "Pixar", 3, TmdbCollectionMediaType.MOVIE)),
        TmdbPresetSource("Lucasfilm", TmdbCollectionSource(TmdbCollectionSourceType.COMPANY, "Lucasfilm", 1, TmdbCollectionMediaType.MOVIE)),
        TmdbPresetSource("Warner Bros.", TmdbCollectionSource(TmdbCollectionSourceType.COMPANY, "Warner Bros.", 174, TmdbCollectionMediaType.MOVIE)),
        TmdbPresetSource("Netflix", TmdbCollectionSource(TmdbCollectionSourceType.NETWORK, "Netflix", 213, TmdbCollectionMediaType.TV)),
        TmdbPresetSource("HBO", TmdbCollectionSource(TmdbCollectionSourceType.NETWORK, "HBO", 49, TmdbCollectionMediaType.TV)),
        TmdbPresetSource("Disney+", TmdbCollectionSource(TmdbCollectionSourceType.NETWORK, "Disney+", 2739, TmdbCollectionMediaType.TV)),
        TmdbPresetSource("Prime Video", TmdbCollectionSource(TmdbCollectionSourceType.NETWORK, "Prime Video", 1024, TmdbCollectionMediaType.TV)),
        TmdbPresetSource("Hulu", TmdbCollectionSource(TmdbCollectionSourceType.NETWORK, "Hulu", 453, TmdbCollectionMediaType.TV)),
        TmdbPresetSource("Apple TV+", TmdbCollectionSource(TmdbCollectionSourceType.NETWORK, "Apple TV+", 2552, TmdbCollectionMediaType.TV))
    )

    /**
     * Sets the genre of the addon source with [key]. Returns the source's key afterwards (the
     * genre is part of it), or null when no installed addon source has [key].
     */
    fun updateCatalogSourceGenre(key: String, genre: String?): String? {
        var updatedKey: String? = null
        _uiState.update { state ->
            // update may run this again on contention: reset, so only the applied attempt's key is returned.
            updatedKey = null
            val folder = state.editingFolder ?: return@update state
            val index = folder.sources.indexOfSourceKey(key)
            val source = folder.sources.getOrNull(index) as? AddonCatalogCollectionSource ?: return@update state
            val catalog = state.catalogFor(source) ?: return@update state
            val updated = source.copy(genre = resolveGenreSelection(catalog, genre))
            updatedKey = collectionSourceKey(updated)
            val updatedSources = folder.sources.toMutableList()
            updatedSources[index] = updated
            state.copy(editingFolder = folder.copy(sources = updatedSources))
        }
        return updatedKey
    }

    fun saveFolderEdit() {
        val rawFolder = _uiState.value.editingFolder ?: return
        if (rawFolder.sources.isEmpty()) return
        stopTmdbWork()
        val editingFolder = rawFolder.withBlankUrlsCleared().copy(
            title = rawFolder.title.ifBlank { string(R.string.collection_editor_untitled_folder) }
        )
        originalFolder = null
        _uiState.update { state ->
            val existingIndex = state.folders.indexOfFirst { it.id == editingFolder.id }
            val newFolders = if (existingIndex >= 0) {
                state.folders.toMutableList().also { it[existingIndex] = editingFolder }
            } else {
                state.folders + editingFolder
            }
            state.copy(
                folders = newFolders,
                showFolderEditor = false,
                editingFolder = null,
                showCatalogPicker = false,
                showTmdbSourcePicker = false,
                showCoverPanel = false
            )
        }
    }

    fun cancelFolderEdit() {
        stopTmdbWork()
        originalFolder = null
        _uiState.update {
            it.copy(
                showFolderEditor = false,
                editingFolder = null,
                showCatalogPicker = false,
                showTmdbSourcePicker = false,
                showCoverPanel = false
            )
        }
    }

    // Set once, never cleared: a save ends the screen (onComplete pops it), so a second
    // activation must neither persist/push again nor pop the back stack twice (L4-24/86).
    private var saveStarted = false

    /** True once Save started: the save pops the screen itself, so Back must not pop too. */
    val isSaving: Boolean get() = saveStarted

    fun save(onComplete: () -> Unit) {
        val state = _uiState.value
        if (state.folders.isEmpty() || saveStarted) return
        saveStarted = true
        viewModelScope.launch {
            val collection = state.draftCollection().copy(
                title = state.title.ifBlank { string(R.string.collection_editor_untitled_collection) }
            )

            if (state.isNew) {
                collectionsDataStore.addCollection(collection)
            } else {
                collectionsDataStore.updateCollection(collection)
            }
            collectionSyncService.triggerPush()
            onComplete()
        }
    }

    /**
     * True when the collection form differs from the collection as opened (Spec 28 Q4: Back
     * asks before dropping it). A saved folder counts; an open folder draft does not (its own
     * form asks).
     */
    fun hasUnsavedCollectionChanges(): Boolean {
        val original = originalCollection ?: return false
        return _uiState.value.draftCollection().normalisedForCompare() != original.normalisedForCompare()
    }

    /** True when the open folder draft differs from the folder as opened (or as added, if new). */
    fun hasUnsavedFolderChanges(): Boolean {
        val original = originalFolder ?: return false
        val draft = _uiState.value.editingFolder ?: return false
        return draft.withBlankUrlsCleared() != original.withBlankUrlsCleared()
    }

    private fun resolveGenreSelection(catalog: AvailableCatalog, requestedGenre: String?): String? {
        return when {
            catalog.genreOptions.isEmpty() -> null
            requestedGenre != null && catalog.genreOptions.contains(requestedGenre) -> requestedGenre
            catalog.genreRequired -> catalog.genreOptions.firstOrNull()
            else -> null
        }
    }

}

/** The index of the source whose [collectionSourceKey] is [key], else -1. */
internal fun List<CollectionSource>.indexOfSourceKey(key: String) = indexOfFirst { collectionSourceKey(it) == key }

/** True when this addon source points at [catalog]. */
internal fun AddonCatalogCollectionSource.isFor(catalog: AvailableCatalog) =
    addonId == catalog.addonId && type == catalog.type && catalogId == catalog.catalogId

/** The installed catalog an addon [source] points at, if its addon is still installed. */
internal fun CollectionEditorUiState.catalogFor(source: AddonCatalogCollectionSource) =
    availableCatalogs.find { source.isFor(it) }

/** The form as a [Collection], title as typed (Save applies the untitled fallback); also the
 *  editor's Home preview. */
internal fun CollectionEditorUiState.draftCollection() = Collection(
    id = collectionId,
    title = title,
    backdropImageUrl = backdropImageUrl.ifBlank { null },
    pinToTop = pinToTop,
    focusGlowEnabled = focusGlowEnabled,
    viewMode = viewMode,
    showAllTab = showAllTab,
    folders = folders
)

/** Forms write "" where storage holds null; Save stores null. */
private fun CollectionFolder.withBlankUrlsCleared() = copy(
    coverImageUrl = coverImageUrl?.ifBlank { null },
    focusGifUrl = focusGifUrl?.ifBlank { null },
    heroBackdropUrl = heroBackdropUrl?.ifBlank { null },
    heroVideoUrl = heroVideoUrl?.ifBlank { null },
    titleLogoUrl = titleLogoUrl?.ifBlank { null }
)

/** Stored FOLLOW_LAYOUT shows as Rows (Q1), so it is no edit; folders compare as Save stores them. */
private fun Collection.normalisedForCompare() = copy(
    backdropImageUrl = backdropImageUrl?.ifBlank { null },
    viewMode = viewMode.displayMode,
    folders = folders.map { it.withBlankUrlsCleared() }
)
