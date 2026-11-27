package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.core.util.StartupLatencyTrace

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.sync.HomeCatalogOrdering
import com.nuvio.tv.data.mdblist.MDBLIST_HOME_ADDON_ID
import com.nuvio.tv.data.mdblist.MdbListHomeRowsState
import com.nuvio.tv.data.mdblist.isMdbListHomeRow
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.catalogRowStableKey
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.model.legacyKey
import com.nuvio.tv.domain.model.mergeCatalogPage
import com.nuvio.tv.domain.model.nextCatalogSkip
import com.nuvio.tv.domain.model.skipStep
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.domain.model.supportsExtra
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import com.nuvio.tv.domain.model.MetaPreview
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import com.nuvio.tv.core.util.filterReleasedItems
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException
import com.nuvio.tv.domain.model.requiredExtraDefaults

/**
 * Spec 117 P5b: Home redraws when its content filter may have changed (the active or previewed
 * profile's settings, 122/V2 N3) and, while it filters, when looked-up tags land (`changes`).
 */
internal fun HomeViewModel.observeContentFilterRedrawsPipeline() {
    viewModelScope.launch {
        // The first value is the state the first draw reads itself.
        activeContentFilter.settingsChanges().drop(1).collect { scheduleUpdateCatalogRows() }
    }
    viewModelScope.launch {
        titleTagLookup.changes.collect { if (homeDrawFilter != null) scheduleUpdateCatalogRows() }
    }
}

@OptIn(FlowPreview::class)
internal fun HomeViewModel.observeCollectionsPipeline() {
    viewModelScope.launch {
        collectionsDataStore.collections
            .distinctUntilChanged()
            .debounce(300)
            .collectLatest { collections ->
                // Deduplicate by collection ID (keep last occurrence) to prevent
                // duplicate LazyColumn keys when users import overlapping collections.
                collectionsCache = collections.associateBy { it.id }.values.toList()
                collectionsLoadedOnce = true
                rebuildCatalogOrder(addonsCache)
                scheduleUpdateCatalogRows()
            }
    }
}

internal fun HomeViewModel.loadHomeCatalogOrderPreferencePipeline() {
    viewModelScope.launch {
        layoutPreferenceDataStore.homeCatalogOrderKeys.collectLatest { keys ->
            homeCatalogOrderKeys = keys
            rebuildCatalogOrder(addonsCache)
            scheduleUpdateCatalogRows()
        }
    }
}

internal fun HomeViewModel.loadFollowAddonsOrderPipeline() {
    viewModelScope.launch {
        layoutPreferenceDataStore.followAddonsOrder.collectLatest { enabled ->
            followAddonsOrderEnabled = enabled
            rebuildCatalogOrder(addonsCache)
            scheduleUpdateCatalogRows()
        }
    }
}

internal fun HomeViewModel.loadDisabledHomeCatalogPreferencePipeline() {
    viewModelScope.launch {
        layoutPreferenceDataStore.disabledHomeCatalogKeys.collectLatest { keys ->
            val newKeys = keys.toSet()
            if (newKeys == disabledHomeCatalogKeys) return@collectLatest
            // Spec 105 S5b1: hiding or showing an MDBList row loads no addon catalog (outside the load signature).
            val catalogsChanged = newKeys.filterNot(HomeCatalogOrdering::isMdbListKey).toSet() !=
                disabledHomeCatalogKeys.filterNot(HomeCatalogOrdering::isMdbListKey).toSet()
            disabledHomeCatalogKeys = newKeys
            rebuildCatalogOrder(addonsCache)
            if (catalogsChanged && addonsCache.isNotEmpty()) {
                loadAllCatalogsPipeline(addonsCache)
            } else {
                scheduleUpdateCatalogRows()
            }
        }
    }
}

internal fun HomeViewModel.loadCustomCatalogTitlesPipeline() {
    viewModelScope.launch {
        layoutPreferenceDataStore.customCatalogTitles.collectLatest { titles ->
            customCatalogTitles = titles
            scheduleUpdateCatalogRows()
        }
    }
}

@OptIn(FlowPreview::class)
internal fun HomeViewModel.observeInstalledAddonsPipeline() {
    viewModelScope.launch {
        addonRepository.getInstalledAddons()
            .distinctUntilChanged()
            .collectLatest { installedAddons ->
                val addons = installedAddons.enabledAddons()
                addonsCache = addons
                loadAllCatalogsPipeline(addons)
            }
    }
}

internal suspend fun HomeViewModel.loadAllCatalogsPipeline(
    addons: List<Addon>,
    forceReload: Boolean = false
) {
    // The only suspension point, kept first so everything below runs without
    // interleaving (re-entrant calls see each other's generation/signature) and
    // reads applied layout prefs (e.g. the type suffix in placeholder titles).
    // Waiting for layout preferences predates the single-layout consolidation
    // (the eager/lazy split once depended on the layout); kept to preserve startup ordering.
    _uiState.first { it.layoutPreferencesReady }

    val signature = buildHomeCatalogLoadSignature(addons)
    // Display fields (e.g. an addon rename) are outside the signature; a skip and a reload both
    // keep loaded rows, so relabel them first (C4-R2).
    val relabelled = relabelCatalogAddonNames(addons)
    val hasActiveLoads = synchronized(activeCatalogLoadJobs) { activeCatalogLoadJobs.any { it.isActive } }
    if (shouldSkipCatalogLoad(
            forceReload = forceReload,
            signature = signature,
            activeSignature = activeCatalogLoadSignature,
            hasActiveLoads = hasActiveLoads,
            hasRows = { hasAnyCatalogRows() }
        )
    ) {
        if (relabelled) scheduleUpdateCatalogRows()
        return
    }

    activeCatalogLoadSignature = signature
    StartupLatencyTrace.mark("catalogs_load_start")
    catalogsLoadInProgress = true
    catalogLoadGeneration += 1
    pendingCatalogLoads.reset()
    val generation = catalogLoadGeneration
    cancelInFlightCatalogLoads()

    // On reload (not first load), keep existing UI data visible while new
    // catalogs load in the background to avoid a flash of empty content.
    val isReload = _uiState.value.catalogRows.isNotEmpty() || _uiState.value.homeRows.isNotEmpty()
    val catalogsToLoad = addons.flatMap { addon ->
        addon.catalogs
            .filter { isHomeCatalog(addon, it) }
            .map { catalog -> addon to catalog }
    }
    // Spec 145: see HomeUiState.homeCatalogsExpected.
    val homeCatalogsExpected = catalogsToLoad.isNotEmpty()
    if (!isReload) {
        _uiState.update {
            it.copy(
                isLoading = true,
                error = null,
                installedAddonsCount = addons.size,
                homeCatalogsExpected = homeCatalogsExpected
            )
        }
        synchronized(catalogStateLock) {
            catalogOrder.clear()
        }
        clearCatalogData()
    } else {
        _uiState.update {
            it.copy(error = null, installedAddonsCount = addons.size, homeCatalogsExpected = homeCatalogsExpected)
        }
    }
    resetCatalogGenerationState()

    try {
        if (addons.isEmpty()) {
            catalogsLoadInProgress = false
            _uiState.update { it.copy(isLoading = false, error = appContext.getString(R.string.home_error_no_addons)) }
            return
        }

        rebuildCatalogOrder(addons)

        // The hero picks from the loaded home catalogs; with none loaded it has no data and
        // won't render. A reload keeps the previous rows visible, so drop the ones that no
        // longer belong (removed addons, newly disabled catalogs); the hero reads every
        // loaded row and would otherwise keep showing them until restart.
        if (isReload) pruneCatalogRows()

        if (isCatalogOrderEmpty()) {
            catalogsLoadInProgress = false
            _uiState.update { it.copy(isLoading = false, error = appContext.getString(R.string.home_error_no_catalog_addons)) }
            return
        }

        if (catalogsToLoad.isEmpty()) {
            // No home catalogs to load, but collections may still exist to render.
            catalogsLoadInProgress = false
            if (hasCatalogOrderEntries()) {
                scheduleUpdateCatalogRows()
            } else {
                _uiState.update { it.copy(isLoading = false, error = appContext.getString(R.string.home_error_no_catalog_addons)) }
            }
            return
        }

        // ── Lazy loading: split into eager and deferred ──
        // Build display title helper (respects custom titles)
        val titlesSnapshot = customCatalogTitles
        val showTypeSuffix = _uiState.value.catalogTypeSuffixEnabled
        val strTypeMovie = appContext.getString(R.string.type_movie)
        val strTypeSeries = appContext.getString(R.string.type_series)
        fun displayTitle(addon: Addon, catalog: CatalogDescriptor): String {
            val key = catalogKey(addonId = addon.id, type = catalog.apiType, catalogId = catalog.id)
            val custom = titlesSnapshot[key]
            return catalogRowTitle(
                catalogName = if (!custom.isNullOrBlank()) custom else catalog.name,
                apiType = catalog.apiType,
                showCatalogTypeSuffix = showTypeSuffix,
                strTypeMovie = strTypeMovie,
                strTypeSeries = strTypeSeries
            )
        }

        // Eager vs lazy, in display order (catalogOrder, rebuilt by rebuildCatalogOrder(addons)
        // above) so the eagerly loaded rows are the ones at the top of Home; see splitEagerLazy.
        val (eagerHomeCatalogs, lazyHomeCatalogs) = splitEagerLazy(
            candidates = catalogsToLoad.map { pair ->
                val (addon, catalog) = pair
                catalogKey(addonId = addon.id, type = catalog.apiType, catalogId = catalog.id) to pair
            },
            displayOrderKeys = snapshotCatalogState().first,
            eagerCount = eagerCatalogLoadCount
        )

        // Build placeholder descriptors for lazy catalogs
        synchronized(catalogStateLock) {
            pendingLazyCatalogs.clear()
            placeholderDescriptors.clear()
        }
        lazyLoadRequestedKeys.clear()

        (eagerHomeCatalogs + lazyHomeCatalogs).forEach { (addon, catalog) ->
            val key = catalogKey(addonId = addon.id, type = catalog.apiType, catalogId = catalog.id)
            synchronized(catalogStateLock) {
                placeholderDescriptors.add(
                    HomeViewModel.PlaceholderDescriptor(
                        catalogKey = key,
                        addonId = addon.id,
                        addonName = addon.displayName,
                        addonBaseUrl = addon.baseUrl,
                        catalogId = catalog.id,
                        catalogName = catalog.name,
                        apiType = catalog.apiType,
                        displayTitle = displayTitle(addon, catalog)
                    )
                )
            }
        }

        lazyHomeCatalogs.forEach { (addon, catalog) ->
            val key = catalogKey(addonId = addon.id, type = catalog.apiType, catalogId = catalog.id)
            synchronized(catalogStateLock) {
                pendingLazyCatalogs[key] = addon to catalog
            }
        }

        Log.d(HomeViewModel.TAG,
            "Lazy loading: eager=${eagerHomeCatalogs.size} lazy=${lazyHomeCatalogs.size}"
        )

        pendingCatalogLoads.reset(eagerHomeCatalogs.size)
        eagerHomeCatalogs.forEach { (addon, catalog) ->
            loadCatalogPipeline(addon, catalog, generation)
        }

        // nt2: kick the reserved-headroom background sweep so the remaining
        // lazy catalogues fill in on their own, without waiting for scroll.
        startReservedHeadroomSweep(generation)

        // Immediately schedule an update so placeholder rows appear in the UI
        // while catalogs are still loading.
        scheduleUpdateCatalogRows()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        catalogsLoadInProgress = false
        _uiState.update { it.copy(isLoading = false, error = e.message) }
    }
}

/**
 * State owned by one catalog generation, cleared when a load starts (first load and reload);
 * a new per-generation field joins here. Order matters: the trailer job is cancelled before
 * its maps are cleared, so a lookup past its debounce cannot write into them.
 */
private fun HomeViewModel.resetCatalogGenerationState() {
    posterStatusReconcileJob?.cancel()
    reconcilePosterStatusObserversPipeline(emptyList())
    hasRenderedFirstCatalog = false
    trailerPreviewJob?.cancel()
    trailerPreviewPendingItemId = null
    trailerPreviewNegativeCache.clear()
    trailerPreviewUrlsState.clear()
    trailerPreviewAudioUrlsState.clear()
    activeTrailerPreviewItemId = null
    prefetchedExternalMetaIds.clear()
    externalMetaPrefetchInFlightIds.clear()
    focusEnrichJob?.cancel()
    pendingFocusEnrichItemId = null
}

internal fun HomeViewModel.loadCatalogPipeline(
    addon: Addon,
    catalog: CatalogDescriptor,
    generation: Long
): Job {
    val loadJob = viewModelScope.launch {
        // Success can arrive twice (disk row, then fresh), so a job settles its pending load once.
        var hasCountedCompletion = false
        fun settleOnce() {
            if (!hasCountedCompletion) {
                pendingCatalogLoads.settle()
                hasCountedCompletion = true
            }
        }
        // Publish every arrival; the debounce in scheduleUpdateCatalogRows coalesces nearby
        // ones. Waiting for pending == 0 stranded ready rows (scroll-requested and sweep loads
        // share the counter) behind a slow addon.
        fun publishArrival() {
            if (pendingCatalogLoads.count == 0) {
                catalogsLoadInProgress = false
                StartupLatencyTrace.mark("catalogs_load_end")
            }
            scheduleUpdateCatalogRows()
        }
        catalogLoadSemaphore.withPermit {
            if (generation != catalogLoadGeneration) return@withPermit
            val supportsSkip = catalog.supportsExtra("skip")
            val skipStep = catalog.skipStep()
            Log.d(
                HomeViewModel.TAG,
                "Loading home catalog addonId=${addon.id} addonName=${addon.name} type=${catalog.apiType} catalogId=${catalog.id} catalogName=${catalog.name} supportsSkip=$supportsSkip skipStep=$skipStep"
            )
            catalogRepository.getCatalog(
                addonBaseUrl = addon.baseUrl,
                addonId = addon.id,
                addonName = addon.displayName,
                catalogId = catalog.id,
                catalogName = catalog.name,
                type = catalog.apiType,
                skip = 0,
                skipStep = skipStep,
                extraArgs = catalog.requiredExtraDefaults(),
                supportsSkip = supportsSkip,
                packGate = false // Spec 117 P5b: Home applies the packs when it draws
            ).collect { result ->
                if (generation != catalogLoadGeneration) return@collect
                when (result) {
                    is NetworkResult.Success -> {
                        val key = catalogKey(
                            addonId = addon.id,
                            type = catalog.apiType,
                            catalogId = catalog.id
                        )
                        // The load captured its Addon (lazy loads long before they run): take a
                        // rename made since then from the current list (C4-R2).
                        replaceCatalogRow(key, result.data.withAddonNameFrom(addonDisplayNames(addonsCache)))
                        // Remove placeholder descriptor now that real data is available
                        synchronized(catalogStateLock) {
                            placeholderDescriptors.removeAll { it.catalogKey == key }
                        }
                        settleOnce()
                        Log.d(
                            HomeViewModel.TAG,
                            "Home catalog loaded addonId=${addon.id} type=${catalog.apiType} catalogId=${catalog.id} items=${result.data.items.size} pending=${pendingCatalogLoads.count}"
                        )
                        publishArrival()
                    }
                    is NetworkResult.Error -> {
                        val errorKey = catalogKey(
                            addonId = addon.id,
                            type = catalog.apiType,
                            catalogId = catalog.id
                        )
                        // Remove placeholder on error so it doesn't show forever
                        synchronized(catalogStateLock) {
                            placeholderDescriptors.removeAll { it.catalogKey == errorKey }
                        }
                        settleOnce()
                        Log.w(
                            HomeViewModel.TAG,
                            "Home catalog failed addonId=${addon.id} type=${catalog.apiType} catalogId=${catalog.id} code=${result.code} message=${result.message}"
                        )
                        // Same as success: the failed row's placeholder goes now.
                        publishArrival()
                    }
                    NetworkResult.Loading -> {
                        /* Handled by individual row */
                    }
                }
            }
        }
    }
    registerCatalogLoadJob(loadJob)
    return loadJob
}

/**
 * Spec 105 S5b1: MdbListHomeRows' rows into [HomeViewModel.catalogsMap] under their order keys (stale ones out). A
 * title already stored keeps its stored (possibly enriched) copy, as a catalog's next page keeps loaded titles. The order
 * is rebuilt when the keys change. Main-only, like the order's other writers.
 */
internal fun HomeViewModel.applyMdbListRowsPipeline(state: MdbListHomeRowsState) {
    synchronized(catalogStateLock) {
        catalogsMap.keys
            .filter { HomeCatalogOrdering.isMdbListKey(it) && it !in state.rows }
            .forEach { removeCatalogRow(it) }
        // A row equal to the one last applied (its titles the same list) is skipped: no remerge of up to 5,000 titles.
        mdbListAppliedRows.keys.retainAll(state.rows.keys)
        state.rows.forEach { (key, row) ->
            if (mdbListAppliedRows[key] == row) return@forEach
            mdbListAppliedRows[key] = row
            val stored = catalogsMap[key]?.items?.associateBy { it.apiType to it.id }.orEmpty()
            replaceCatalogRow(key, row.copy(items = row.items.map { stored[it.apiType to it.id] ?: it }))
        }
    }
    if (state.keys != mdbListKeys) {
        mdbListKeys = state.keys
        rebuildCatalogOrder(addonsCache)
    }
    scheduleUpdateCatalogRows()
}

internal fun HomeViewModel.loadMoreCatalogItemsPipeline(catalogId: String, addonId: String, type: String) {
    // MDBList rows page through their own reader (cursor pages), stored under their order key.
    if (addonId == MDBLIST_HOME_ADDON_ID && readCatalogRow(catalogId)?.isMdbListHomeRow() == true) {
        mdbListHomeRows.loadMore(catalogId)
        return
    }
    val key = catalogKey(addonId = addonId, type = type, catalogId = catalogId)
    val currentRow = readCatalogRow(key)

    if (currentRow == null) {
        return
    }

    // Re-entry guard: this runs on Main, as do the arms below that clear isLoading.
    if (currentRow.isLoading || !currentRow.hasMore) {
        return
    }
    // Addon gone since the row loaded: nothing to page. Checked before the guard is set,
    // which used to leave the row loading for good (L4-b).
    val addon = addonsCache.find { it.id == addonId } ?: return

    updateCatalogRow(key) { it.copy(isLoading = true) }

    viewModelScope.launch {
        val nextSkip = currentRow.nextCatalogSkip()
        catalogRepository.getCatalog(
            addonBaseUrl = addon.baseUrl,
            addonId = addon.id,
            addonName = addon.displayName,
            catalogId = catalogId,
            catalogName = currentRow.catalogName,
            type = currentRow.apiType,
            skip = nextSkip,
            skipStep = currentRow.skipStep,
            extraArgs = currentRow.extraArgs,
            supportsSkip = currentRow.supportsSkip,
            packGate = false // Spec 117 P5b: Home applies the packs when it draws
        ).collect { result ->
            when (result) {
                is NetworkResult.Success -> {
                    updateCatalogRow(key) { latestRow ->
                        val mergedRow = latestRow.mergeCatalogPage(result.data)
                        mergedRow
                    }
                    scheduleUpdateCatalogRows()
                }
                is NetworkResult.Error -> {
                    updateCatalogRow(key) { it.copy(isLoading = false) }
                    scheduleUpdateCatalogRows()
                }
                NetworkResult.Loading -> { }
            }
        }
    }
}

internal suspend fun HomeViewModel.updateCatalogRowsPipeline() {
    // Read on every draw (117/P5b): a settings edit applies at the next draw. Read before the snapshot,
    // so the rows drawn are never older than this suspension.
    val drawFilter = HomeDrawFilter.of(activeContentFilter.current(), titleTagLookup)
    homeDrawFilter = drawFilter
    val (orderedKeys, catalogSnapshot) = snapshotCatalogState()
    val collectionsSnapshot = collectionsCache.associateBy { "collection_${it.id}" }
    // Spec 145: published with the rows built from this snapshot.
    val collectionsLoaded = collectionsLoadedOnce
    val hideUnreleased = _uiState.value.hideUnreleasedContent
    val titlesSnapshot = customCatalogTitles
    val (drawnKeys, drawn) = withContext(Dispatchers.Default) {
        val keyedRows = orderedKeys.mapNotNull { key ->
            val row = catalogSnapshot[key] ?: return@mapNotNull null
            val custom = titlesSnapshot[key]
            key to if (!custom.isNullOrBlank()) row.copy(catalogName = custom) else row
        }
        val rawRows = keyedRows.map { it.second }
        val releasedRows = if (hideUnreleased) {
            val today = LocalDate.now()
            rawRows.map { it.filterReleasedItems(today) }
        } else {
            rawRows
        }
        keyedRows.map { it.first } to drawCatalogRows(releasedRows, drawFilter)
    }
    val (displayRows, baseHeroItems, heldRowKeys) = drawn
    val strTypeMovie = appContext.getString(R.string.type_movie)
    val strTypeSeries = appContext.getString(R.string.type_series)

    val computedHomeRows = withContext(Dispatchers.Default) {
        buildList {
            // By order key: an MDBList row's (Spec 105 S5b1) is not its legacyKey; drawn rows keep the keyed rows' order.
            val displayRowsByKey = drawnKeys.zip(displayRows).toMap()
            // Build a lookup of placeholder descriptors by key for lazy catalogs
            val placeholdersByKey = synchronized(catalogStateLock) {
                placeholderDescriptors.associateBy { it.catalogKey }
            }
            val addedCollectionIds = mutableSetOf<String>()
            collectionsCache.forEach { collection ->
                val key = "collection_${collection.id}"
                if (collection.pinToTop && key !in disabledHomeCatalogKeys && addedCollectionIds.add(collection.id)) {
                    add(HomeRow.CollectionRow(collection))
                }
            }
            for (key in orderedKeys) {
                if (key in disabledHomeCatalogKeys) continue
                val collectionEntry = collectionsSnapshot[key]
                if (collectionEntry != null) {
                    if (!collectionEntry.pinToTop && addedCollectionIds.add(collectionEntry.id)) {
                        add(HomeRow.CollectionRow(collectionEntry))
                    }
                } else {
                    val catalogRow = displayRowsByKey[key]
                    catalogHomeRow(
                        catalogRow = catalogRow,
                        held = catalogRow != null && catalogRow.legacyKey() in heldRowKeys,
                        lazyPlaceholder = placeholdersByKey[key],
                        strTypeMovie = strTypeMovie,
                        strTypeSeries = strTypeSeries
                    )?.let(::add)
                }
            }
        }
    }

    // Clear any stale error when content is now available (e.g., hero
    // catalogs loaded after the initial startup race set an error).
    val hasContent = computedHomeRows.isNotEmpty() || baseHeroItems.isNotEmpty() || displayRows.isNotEmpty()

    _uiState.update { state ->
        state.copy(
            catalogRows = if (state.catalogRows == displayRows) state.catalogRows else displayRows,
            heroItems = if (state.heroItems == baseHeroItems) state.heroItems else baseHeroItems,
            homeRows = if (state.homeRows == computedHomeRows) state.homeRows else computedHomeRows,
            collectionsLoaded = state.collectionsLoaded || collectionsLoaded,
            isLoading = false,
            error = if (hasContent) null else state.error
        )
    }

    schedulePosterStatusReconcilePipeline(displayRows)
}

private fun stableHeroCandidates(row: CatalogRow, candidates: kotlin.collections.Collection<MetaPreview>): List<MetaPreview> {
    return candidates.sortedWith(
        compareBy<MetaPreview> { stableHeroSortKey(row, it) }
            .thenBy { it.id }
    )
}

// Deterministic 7-slot hero fill: the 7 slots are split across rows in order
// (7 / rows each, the first 7 % rows rows get one extra). Each row contributes its
// filtered items in stableHeroSortKey order, skipping any id already seen in an
// earlier row (every candidate of an earlier row counts, not only the picked ones).
// Short rows are not backfilled, so the result can hold fewer than 7 items. No state
// is carried between runs: the same catalog contents always yield the same picks.
internal fun pickHeroSlots(rows: List<CatalogRow>): List<MetaPreview> {
    val totalCatalogs = rows.size.coerceAtLeast(1)
    val baseSlot = 7 / totalCatalogs
    val remainder = 7 % totalCatalogs
    val seen = mutableSetOf<String>()
    val result = mutableListOf<MetaPreview>()
    rows.forEachIndexed { index, row ->
        val slot = baseSlot + if (index < remainder) 1 else 0
        val candidates = stableHeroCandidates(
            row = row,
            candidates = row.items.filter { it.hasHeroArtwork() }.associateBy { it.id }.values
        )
        // Filter out duplicates but keep taking until slot is filled
        val unique = candidates.filter { seen.add(it.id) }
        result += unique.take(slot)
    }
    return result
}

private fun stableHeroSortKey(
    row: CatalogRow,
    item: MetaPreview
): Int {
    return "${row.addonId}|${row.apiType}|${row.catalogId}|${item.id}".hashCode()
}

internal fun HomeViewModel.schedulePosterStatusReconcilePipeline(rows: List<CatalogRow>) {
    posterStatusReconcileJob?.cancel()
    if (rows.isEmpty()) {
        reconcilePosterStatusObserversPipeline(rows)
        return
    }
    posterStatusReconcileJob = viewModelScope.launch {
        delay(500)
        reconcilePosterStatusObserversPipeline(rows)
    }
}

internal fun HomeViewModel.reconcilePosterStatusObserversPipeline(rows: List<CatalogRow>) {
    val allMovieItemsByKey = linkedMapOf<String, String>()
    rows.asSequence()
        .flatMap { row -> row.items.asSequence() }
        .filter { it.apiType.equals("movie", ignoreCase = true) }
        .forEach { item ->
            val key = homeItemStatusKey(item.id, item.apiType)
            if (key !in allMovieItemsByKey) {
                allMovieItemsByKey[key] = item.id
            }
        }
    val desiredMovieKeys = allMovieItemsByKey.keys

    val allSeriesItemsByKey = linkedMapOf<String, String>()
    rows.asSequence()
        .flatMap { row -> row.items.asSequence() }
        .filter { it.apiType.equals("series", ignoreCase = true) || it.apiType.equals("tv", ignoreCase = true) }
        .forEach { item ->
            val key = homeItemStatusKey(item.id, item.apiType)
            if (key !in allSeriesItemsByKey) {
                allSeriesItemsByKey[key] = item.id
            }
        }


    if (desiredMovieKeys != lastMovieWatchedItemKeys) {
        lastMovieWatchedItemKeys = desiredMovieKeys
        movieWatchedBatchJob?.cancel()

        if (desiredMovieKeys.isNotEmpty()) {
            movieWatchedBatchJob = viewModelScope.launch {
                watchProgressRepository.observeWatchedMovieIds()
                    .collectLatest { watchedIds ->
                        _uiState.update { state ->
                            val movieStatus = buildMap {
                                allMovieItemsByKey.forEach { (statusKey, contentId) ->
                                    put(statusKey, contentId in watchedIds)
                                }
                            }
                            // Merge with existing status to preserve series entries.
                            val merged = state.movieWatchedStatus
                                .filterKeys { it !in desiredMovieKeys } + movieStatus
                            if (state.movieWatchedStatus == merged) {
                                state
                            } else {
                                state.copy(movieWatchedStatus = merged)
                            }
                        }
                    }
            }
        }
    }

    // Update series watched status from CW pipeline's fully-watched resolution.
    // This piggybacks on the meta lookups CW already performs — no extra network calls.
    if (allSeriesItemsByKey.isNotEmpty()) {
        seriesWatchedObserverJob?.cancel()
        seriesWatchedObserverJob = viewModelScope.launch {
            combine(
                fullyWatchedSeriesIds.fullyWatchedSeriesIds,
                watchProgressRepository.watchedItems
            ) { fullyWatched, watchedItems ->
                fullyWatched to watchedItems
            }.collectLatest { (fullyWatched, watchedItems) ->
                val effectiveFullyWatched = reconcileFullyWatchedFromLocalItems(
                    fullyWatched = fullyWatched,
                    watchedItems = watchedItems,
                    seriesContentIds = allSeriesItemsByKey.values
                )
                val seriesStatus = buildMap {
                    allSeriesItemsByKey.forEach { (statusKey, contentId) ->
                        put(statusKey, contentId in effectiveFullyWatched)
                    }
                }
                _uiState.update { state ->
                    val merged = state.movieWatchedStatus
                        .filterKeys { it !in allSeriesItemsByKey.keys } + seriesStatus
                    if (state.movieWatchedStatus == merged) state
                    else state.copy(movieWatchedStatus = merged)
                }
            }
        }
    } else {
        seriesWatchedObserverJob?.cancel()
        seriesWatchedObserverJob = null
    }
}

private fun HomeViewModel.reconcileFullyWatchedFromLocalItems(
    fullyWatched: Set<String>,
    watchedItems: List<WatchedItem>,
    seriesContentIds: Iterable<String>
): Set<String> {
    val watchedEpisodesByContentId = watchedItems
        .filter { it.season != null && it.episode != null }
        .groupBy { it.contentId }
        .mapValues { (_, items) -> items.map { it.season!! to it.episode!! }.toSet() }
    val cacheResolvedIds = mutableSetOf<String>()
    val cacheResolvedFullyWatched = buildSet {
        seriesContentIds.forEach { contentId ->
            val requiredEpisodes = synchronized(cwBadgeEpisodeCache) {
                cwBadgeEpisodeCache["series:$contentId"] ?: cwBadgeEpisodeCache["tv:$contentId"]
            } ?: return@forEach
            cacheResolvedIds.add(contentId)
            val watchedEpisodes = watchedEpisodesByContentId[contentId].orEmpty()
            if (requiredEpisodes.isNotEmpty() && requiredEpisodes.all { it in watchedEpisodes }) {
                add(contentId)
            }
        }
    }
    if (cacheResolvedIds.isEmpty()) return fullyWatched
    val mergedHolderIds = (fullyWatched - cacheResolvedIds) + cacheResolvedFullyWatched
    if (mergedHolderIds != fullyWatchedSeriesIds.fullyWatchedSeriesIds.value) {
        // Re-applied to the holder's current IDs under its lock (L4-78): `fullyWatched` may be stale.
        fullyWatchedSeriesIds.transformWithValidation(validatedIds = cacheResolvedIds) { current ->
            (current - cacheResolvedIds) + cacheResolvedFullyWatched
        }
    }
    return mergedHolderIds
}

private const val CATALOG_SWEEP_CONCURRENCY = 2
private const val CATALOG_SWEEP_YIELD_POLL_MS = 150L

// nt2: reserved-headroom background sweep.
// Once the eager catalogue batch is dispatched, this drains the remaining
// pendingLazyCatalogs automatically, in display (insertion) order, so off-screen
// rows fill in on their own without the user scrolling to them. It yields to
// eager and on-demand (scrolled-to) loads: it only dispatches while no non-sweep
// catalogue load is pending and while fewer than CATALOG_SWEEP_CONCURRENCY sweep
// loads are already in flight, leaving at least three of the shared five
// catalogLoadSemaphore permits free so a scrolled-to row never queues behind
// background work. Aborts if the catalogue generation changes (refresh / layout
// switch).
internal fun HomeViewModel.startReservedHeadroomSweep(generation: Long) {
    catalogSweepJob?.cancel()
    catalogSweepJob = viewModelScope.launch {
        while (isActive && generation == catalogLoadGeneration) {
            val nonSweepPending = pendingCatalogLoads.count > catalogSweepInFlight.get()
            val atSweepCap = catalogSweepInFlight.get() >= CATALOG_SWEEP_CONCURRENCY
            if (nonSweepPending || atSweepCap) {
                delay(CATALOG_SWEEP_YIELD_POLL_MS)
                continue
            }
            val next = pullNextSweepCatalog() ?: break
            catalogSweepInFlight.incrementAndGet()
            pendingCatalogLoads.add()
            val job = loadCatalogPipeline(next.first, next.second, generation)
            job.invokeOnCompletion { catalogSweepInFlight.decrementAndGet() }
        }
    }
}

// nt2: atomically claim the next pending lazy catalogue for the sweep, in display
// (insertion) order, sharing the same catalogStateLock and lazyLoadRequestedKeys
// dedup as the on-demand requestLazyCatalogLoad path so a catalogue is never
// loaded twice. Returns null once the pending set is drained.
private fun HomeViewModel.pullNextSweepCatalog(): Pair<Addon, CatalogDescriptor>? {
    return synchronized(catalogStateLock) {
        val iterator = pendingLazyCatalogs.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val key = entry.key
            val value = entry.value
            iterator.remove()
            if (lazyLoadRequestedKeys.add(key)) {
                return@synchronized value
            }
        }
        null
    }
}
