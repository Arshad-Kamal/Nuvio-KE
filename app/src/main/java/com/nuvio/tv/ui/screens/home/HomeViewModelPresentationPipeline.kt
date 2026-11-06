package com.nuvio.tv.ui.screens.home

import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.job
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(FlowPreview::class)
internal fun HomeViewModel.observeLayoutPreferencesPipeline() {
    viewModelScope.launch {
        layoutPreferenceDataStore.layoutUiPrefsFlow()
            .distinctUntilChanged()
            .debounce(300)
            .collectLatest { prefs ->
                val previousState = _uiState.value
                val shouldRefreshCatalogPresentation =
                    previousState.hideUnreleasedContent != prefs.hideUnreleasedContent ||
                        previousState.homeImdbRatingsVisibility != prefs.homeImdbRatingsVisibility
                _uiState.update {
                    it.withLayoutPrefs(prefs).copy(layoutPreferencesReady = true)
                }
                if (shouldRefreshCatalogPresentation) scheduleUpdateCatalogRows()
            }
    }
}

@OptIn(FlowPreview::class)
internal fun HomeViewModel.observeModernHomePresentationPipeline() {
    viewModelScope.launch {
        combine(uiState, _currentLocaleTag) { state, localeTag ->
                ModernHomePresentationInput(
                    homeRows = state.homeRows,
                    catalogRows = state.catalogRows,
                    continueWatchingItems = if (state.continueWatchingEnabled) state.continueWatchingItems else emptyList(),
                    upcomingItems = if (state.continueWatchingEnabled) state.upcomingItems else emptyList(),
                    useLandscapePosters = state.modernLandscapePostersEnabled,
                    showCatalogTypeSuffix = state.catalogTypeSuffixEnabled,
                    showAddonName = state.catalogAddonNameEnabled,
                    showFullReleaseDate = state.showFullReleaseDate,
                    localeTag = localeTag,
                    heroItems = if (state.heroSectionEnabled) state.heroItems else emptyList()
                )
            }
            // Compare by row structure only (keys + item counts), not by
            // item content.  Meta enrichment changes item fields but
            // not the row structure — the hero section reads enriched data
            // via enrichedPreviews instead.
            .distinctUntilChanged { old, new ->
                old.homeRows === new.homeRows
                    && old.continueWatchingItems == new.continueWatchingItems
                    && old.upcomingItems == new.upcomingItems
                    && old.useLandscapePosters == new.useLandscapePosters
                    && old.showCatalogTypeSuffix == new.showCatalogTypeSuffix
                    && old.showAddonName == new.showAddonName
                    && old.showFullReleaseDate == new.showFullReleaseDate
                    && old.localeTag == new.localeTag
                    && old.catalogRows.size == new.catalogRows.size
                    && old.heroItems == new.heroItems
            }
            .debounce {
                // Use a longer debounce while catalogs are still loading to
                // avoid repeated expensive presentation builds during the
                // initial burst of catalog arrivals.
                if (catalogsLoadInProgress) 300L else 80L
            }
            .collectLatest { input ->
                val shouldWarmStart = _modernHomePresentation.value.rows.list.isEmpty()
                val visibleCatalogRowCount = input.catalogRows.count { it.items.isNotEmpty() }
                val warmStartCatalogRowCount = if (input.continueWatchingItems.isNotEmpty()) 2 else 3

                if (shouldWarmStart && visibleCatalogRowCount > warmStartCatalogRowCount) {
                    val warmStartPresentation = withContext(Dispatchers.Default) {
                        buildModernHomePresentation(
                            input = input,
                            cache = modernCarouselRowBuildCache,
                            context = appContext,
                            maxCatalogRows = warmStartCatalogRowCount
                        )
                    }
                    if (_modernHomePresentation.value != warmStartPresentation) {
                        _modernHomePresentation.value = warmStartPresentation
                    }
                }

                val presentation = withContext(Dispatchers.Default) {
                    buildModernHomePresentation(
                        input = input,
                        cache = modernCarouselRowBuildCache,
                        context = appContext
                    )
                }
                if (_modernHomePresentation.value != presentation) {
                    _modernHomePresentation.value = presentation
                }
            }
    }
}

internal fun HomeViewModel.observeExternalMetaPrefetchPreferencePipeline() {
    viewModelScope.launch {
        layoutPreferenceDataStore.preferExternalMetaAddonDetail
            .collectLatest { enabled ->
                externalMetaPrefetchEnabled = enabled
                // External meta is the only hero-enrichment source since the TMDB
                // enrichment cut (formerly published from the TMDB settings observer).
                _uiState.update { it.copy(heroEnrichmentEnabled = enabled) }
                if (!enabled) {
                    externalMetaPrefetchInFlightIds.clear()
                }
            }
    }
}

/**
 * T7 (ysosrs 1e319ab2a): the per-item trailer-preview caches (resolved URL/audio
 * maps, negative cache) are keyed by itemId only and were cleared solely on
 * catalog reload, so a hero trailer resolved under IMDb kept playing after the
 * user switched the source back to YouTube (and a YouTube miss stayed negative
 * after switching to IMDb). TrailerService's own cache is already source-keyed;
 * this clears the layer above it and re-requests the currently focused item.
 *
 * Fork hardening beyond upstream: the old preview job is JOINED before the
 * clears, so none of its writes can land in the cleared maps.
 */
internal fun HomeViewModel.observeTrailerSourceChangesPipeline() {
    viewModelScope.launch {
        trailerSettingsDataStore.settings
            .map { it.source }
            .distinctUntilChanged()
            .drop(1) // initial value: nothing cached yet
            .collectLatest {
                trailerPreviewJob?.cancelAndJoin()
                trailerPreviewPendingItemId = null
                trailerPreviewNegativeCache.clear()
                trailerPreviewUrlsState.clear()
                trailerPreviewAudioUrlsState.clear()
                val refocusId = activeTrailerPreviewItemId
                activeTrailerPreviewItemId = null
                refocusId?.let { id -> findCatalogItemById(id)?.let { requestTrailerPreviewPipeline(it) } }
            }
    }
}

internal fun HomeViewModel.requestTrailerPreviewPipeline(item: MetaPreview) {
    requestTrailerPreviewPipeline(
        itemId = item.id,
        title = item.name,
        releaseInfo = item.releaseInfo,
        apiType = item.apiType,
        fallbackYtId = item.trailerYtIds.firstOrNull()
    )
}

internal fun HomeViewModel.requestTrailerPreviewPipeline(
    itemId: String,
    title: String,
    releaseInfo: String?,
    apiType: String,
    fallbackYtId: String? = null
) {
    if (startupGracePeriodActive) return

    // Resolve fallbackYtId from catalog item if not provided
    val resolvedFallbackYtId = fallbackYtId ?: findCatalogItemById(itemId)?.trailerYtIds?.firstOrNull()

    activeTrailerPreviewItemId = itemId
    // P43 (upstream 99c948c80; the FolderDetailViewModel gate): a repeat request for the title
    // being looked up keeps its debounce. Any other request supersedes it, including A after
    // A→B, whose cancelled job no longer gates A out.
    if (trailerPreviewPendingItemId == itemId && trailerPreviewJob?.isActive == true) return
    // Cancellation is the only supersede mechanism: the debounce delay, the lookups, and the
    // job's Main write block (entered through withContext) all stop a cancelled job.
    trailerPreviewJob?.cancel()

    if (trailerPreviewNegativeCache.contains(itemId)) return
    if (trailerPreviewUrlsState.containsKey(itemId)) return

    trailerPreviewPendingItemId = itemId
    trailerPreviewJob = viewModelScope.launch(Dispatchers.IO) {
        // Debounce: wait for focus to settle before hitting network. 350ms starts prewarming
        // ~250ms sooner than 600 on a deliberate pause, still past a scroll-flick (~<200ms).
        delay(350)

        // A superseded lookup stops inside (cancellation propagates through the id lookup).
        val trailerSource = trailerService.getTrailerPlaybackSourceForItem(
            itemId = itemId,
            title = title,
            releaseInfo = releaseInfo,
            apiType = apiType
        )

        withContext(Dispatchers.Main) {
            if (trailerSource?.videoUrl.isNullOrBlank()) {
                val fallbackSource = resolvedFallbackYtId?.let { ytId ->
                    trailerService.getTrailerPlaybackSourceFromYouTubeUrl(
                        youtubeUrl = "https://www.youtube.com/watch?v=$ytId"
                    )
                }
                if (fallbackSource?.videoUrl != null) {
                    if (trailerPreviewUrlsState[itemId] != fallbackSource.videoUrl) {
                        trailerPreviewUrlsState[itemId] = fallbackSource.videoUrl
                    }
                    val fallbackAudio = fallbackSource.audioUrl
                    if (fallbackAudio.isNullOrBlank()) {
                        trailerPreviewAudioUrlsState.remove(itemId)
                    } else if (trailerPreviewAudioUrlsState[itemId] != fallbackAudio) {
                        trailerPreviewAudioUrlsState[itemId] = fallbackAudio
                    }
                } else {
                    trailerPreviewNegativeCache.add(itemId)
                    trailerPreviewUrlsState.remove(itemId)
                    trailerPreviewAudioUrlsState.remove(itemId)
                }
            } else {
                val videoUrl = trailerSource.videoUrl
                if (trailerPreviewUrlsState[itemId] != videoUrl) {
                    trailerPreviewUrlsState[itemId] = videoUrl
                }
                val audioUrl = trailerSource.audioUrl
                if (audioUrl.isNullOrBlank()) {
                    trailerPreviewAudioUrlsState.remove(itemId)
                } else if (trailerPreviewAudioUrlsState[itemId] != audioUrl) {
                    trailerPreviewAudioUrlsState[itemId] = audioUrl
                }
            }
        }
    }
}

internal fun HomeViewModel.onItemFocusPipeline(item: MetaPreview) {
    if (startupGracePeriodActive) {
        deferredEnrichItem = item
        return
    }
    if (item.id in prefetchedExternalMetaIds) {
        // Ensure enrichedPreviews contains this item so the UI can display
        // hero data immediately (e.g. when adjacent prefetch resolved it
        // before the user focused on it).
        if (item.id !in _enrichedPreviews.value) {
            val enriched = findCatalogItemById(item.id) ?: item
            addEnrichedPreview(item.id, enriched)
        }
        if (_enrichingItemId.value == item.id) setEnrichingItemId(null)
        // Still prefetch full meta in background for instant detail screen.
        prefetchFullMeta(item)
        return
    }
    if (pendingFocusEnrichItemId == item.id) return

    // Clear enriching for previous item immediately when focus moves away
    if (_enrichingItemId.value != null && _enrichingItemId.value != item.id) {
        setEnrichingItemId(null)
    }

    val willEnrich = externalMetaPrefetchEnabled

    pendingFocusEnrichItemId = item.id
    focusEnrichJob?.cancel()
    focusEnrichJob = viewModelScope.launch(Dispatchers.IO) {
        val self = coroutineContext.job
        try {
            delay(HomeViewModel.EXTERNAL_META_PREFETCH_FOCUS_DEBOUNCE_MS)
            if (pendingFocusEnrichItemId != item.id) {
                if (_enrichingItemId.value == item.id) setEnrichingItemId(null)
                return@launch
            }
            if (willEnrich) setEnrichingItemId(item.id)
            if (item.id in prefetchedExternalMetaIds) {
                if (_enrichingItemId.value == item.id) setEnrichingItemId(null)
                // Still prefetch full meta in background for instant detail screen.
                prefetchFullMeta(item)
                return@launch
            }

            fetchAndApplyExternalMeta(item)

            // Always prefetch full meta in background for instant detail screen loading.
            prefetchFullMeta(item)

            // Warm up watch progress pipeline so detail screen reads are fast.
            viewModelScope.launch {
                watchProgressRepository.getAllEpisodeProgress(item.id.substringBefore(":")).first()
            }

        } finally {
            // T28 P30 (NM `b9b16ddf4`): release the claim on every exit (a kept claim blocked
            // retrying this item's focus enrichment and its adjacent preload), but only while
            // this job is still the current one: a cancelled older job for the same item can
            // finish after a newer focus re-claimed it. On Main, where the claim is written.
            withContext(NonCancellable + Dispatchers.Main) {
                if (focusEnrichJob === self && pendingFocusEnrichItemId == item.id) {
                    pendingFocusEnrichItemId = null
                }
            }
            if (_enrichingItemId.value == item.id) {
                setEnrichingItemId(null)
                // If enrichment completed but no enriched data exists for this item,
                // mark it as failed so the UI can show addon data immediately.
                if (item.id !in _enrichedPreviews.value &&
                    item.id !in prefetchedExternalMetaIds) {
                    _failedEnrichmentIds.update { it + item.id }
                }
            }
        }
    }
}

/**
 * Adjacent-item meta prefetch so both neighbouring cards' hero/backdrop data
 * is ready on navigation. FolderDetailViewModel.preloadAdjacentItem MIRRORS
 * this (same dedup sets and external-meta gate, plus its own tab-update tail;
 * its settle delay is deliberately slower — 600 ms there vs the 120 ms
 * EXTERNAL_META_PREFETCH_ADJACENT_DEBOUNCE_MS here; both hold their jobs in
 * AdjacentPrefetchJobs) — change together. A shared coordinator was considered and rejected
 * (G6, 2026-08-30): the two pipelines are tuned to their screens, and
 * parameterizing the divergence is more machinery than the mirror cost.
 */
internal fun HomeViewModel.preloadAdjacentItemPipeline(item: MetaPreview) {
    if (startupGracePeriodActive) return
    if (item.id in prefetchedExternalMetaIds) return
    if (pendingFocusEnrichItemId == item.id) return

    // Both neighbours of a settled card (T28 P29); a superseded one is evicted (cancelled).
    adjacentItemPrefetchJobs.launch(viewModelScope, item.id, Dispatchers.IO) {
        delay(HomeViewModel.EXTERNAL_META_PREFETCH_ADJACENT_DEBOUNCE_MS)
        if (item.id in prefetchedExternalMetaIds) return@launch

        fetchAndApplyExternalMeta(item)

        // Background prefetch for detail screen cache.
        prefetchFullMeta(item)
    }
}

/**
 * Fetches external meta for [item] when enabled and not already fetched or in flight
 * (one claim shared by the focus and adjacent pipelines), then publishes on Main: the
 * catalog/trailer state [updateCatalogItemWithMeta] mutates is Main-owned (plain sets,
 * pending id, trailer job), and both pipelines run on IO. The item is marked
 * prefetched only once published, so a job cancelled before publishing leaves it retryable.
 */
private suspend fun HomeViewModel.fetchAndApplyExternalMeta(item: MetaPreview) {
    val claimed = externalMetaPrefetchEnabled &&
        item.id !in prefetchedExternalMetaIds &&
        externalMetaPrefetchInFlightIds.add(item.id)
    val externalMeta = if (claimed) {
        try {
            val result = metaRepository.getMetaFromAllAddons(item.apiType, item.id, item.sourceAddonBaseUrl)
                .first { it is NetworkResult.Success || it is NetworkResult.Error }
            if (result is NetworkResult.Success) result.data else null
        } finally {
            externalMetaPrefetchInFlightIds.remove(item.id)
        }
    } else {
        null
    }
    withContext(Dispatchers.Main) {
        if (externalMeta != null) {
            updateCatalogItemWithMeta(item.id, externalMeta)
        } else {
            // Nothing produced: mark enrichment in previews so the UI
            // doesn't keep showing the spinner.
            addEnrichedPreview(item.id, item)
        }
        if (claimed) prefetchedExternalMetaIds.add(item.id)
    }
}

/** Warms the full-meta cache once per item so the detail screen opens instantly. */
private fun HomeViewModel.prefetchFullMeta(item: MetaPreview) {
    if (!backgroundMetaPrefetchedIds.add(item.id)) return
    viewModelScope.launch {
        metaRepository.getMetaFromAllAddons(
            type = item.apiType,
            id = item.id
        ).first { it !is NetworkResult.Loading }
    }
}

/**
 * The background an enriched catalog item keeps (T31, P40; upstream 99c948c80): landscape art
 * only. `backdropUrl` falls back to a poster, which would be stored as the background and then
 * frozen as the landscape card art.
 */
internal fun enrichedBackground(meta: Meta, current: MetaPreview): String? =
    meta.landscapeBackdropUrl ?: current.background

/** Main-only: see [fetchAndApplyExternalMeta]. */
private fun HomeViewModel.updateCatalogItemWithMeta(itemId: String, meta: Meta) {
    val incomingTrailerYtIds = meta.trailerYtIds
    val seasonCount = meta.videos
        .asSequence()
        .mapNotNull { it.season }
        .filter { it > 0 }
        .distinct()
        .count()
        .takeIf { it > 0 }

    fun mergeItem(currentItem: MetaPreview): MetaPreview = currentItem.copy(
        background = enrichedBackground(meta, currentItem),
        logo = meta.logo ?: currentItem.logo,
        description = meta.description ?: currentItem.description,
        imdbRating = meta.imdbRating ?: currentItem.imdbRating,
        genres = if (meta.genres.isNotEmpty()) meta.genres else currentItem.genres,
        runtime = meta.runtime ?: currentItem.runtime,
        status = meta.status ?: currentItem.status,
        ageRating = meta.ageRating ?: currentItem.ageRating,
        language = meta.language ?: currentItem.language,
        country = meta.country ?: currentItem.country,
        seasonCount = seasonCount ?: currentItem.seasonCount,
        trailerYtIds = if (incomingTrailerYtIds.isNotEmpty()) incomingTrailerYtIds else currentItem.trailerYtIds
    )

    updateIndexedCatalogItem(itemId, ::mergeItem)

    _uiState.update { state ->
        var changed = false
        val updatedRows = state.catalogRows.map { row ->
            val itemIndex = row.items.indexOfFirst { it.id == itemId }
            if (itemIndex < 0) {
                row
            } else {
                val mergedItem = mergeItem(row.items[itemIndex])
                if (mergedItem == row.items[itemIndex]) {
                    row
                } else {
                    changed = true
                    val mutableItems = row.items.toMutableList()
                    mutableItems[itemIndex] = mergedItem
                    row.copy(items = mutableItems)
                }
            }
        }
        if (changed) state.copy(catalogRows = updatedRows) else state
    }
    findCatalogItemById(itemId)?.let { enriched ->
        addEnrichedPreview(itemId, enriched)
    }

    // If external meta brought new trailerYtIds and the item has no trailer resolved yet, retry.
    // Only retry if this item is currently focused — avoid prefetching trailers for adjacent items.
    if (incomingTrailerYtIds.isNotEmpty() && !trailerPreviewUrlsState.containsKey(itemId) && activeTrailerPreviewItemId == itemId) {
        trailerPreviewNegativeCache.remove(itemId)
        val currentItem = findCatalogItemById(itemId) ?: return
        // Not pending any more: the request cancels the running lookup (its old ids) and
        // restarts it with the new ones.
        trailerPreviewPendingItemId = null
        requestTrailerPreviewPipeline(currentItem)
    }
}
