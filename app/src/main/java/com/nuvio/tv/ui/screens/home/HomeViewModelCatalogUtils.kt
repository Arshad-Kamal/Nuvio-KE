package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.MetaPreview
import kotlinx.coroutines.Job
import com.nuvio.tv.domain.model.hasUnsatisfiableRequiredExtra
import com.nuvio.tv.domain.model.shouldShowOnHome

/** The persisted home catalog key (order, disabled and hero selections, custom titles). */
internal fun catalogKey(addonId: String, type: String, catalogId: String): String {
    return "${addonId}_${type}_${catalogId}"
}

internal fun HomeViewModel.buildHomeCatalogLoadSignature(addons: List<Addon>): String =
    homeCatalogLoadSignature(addons, disabledHomeCatalogKeys)

/**
 * What a Home catalog load depends on: per addon catalog the transport/manifest fields and the
 * catalog's extras, order-insensitive, plus the disabled keys. Addon display-only fields
 * (displayName, logo, description) are outside it, so an addon rename does not reload; the rows
 * are relabelled in place instead ([relabelCatalogAddonNames], Spec 21 C4-R2).
 */
internal fun homeCatalogLoadSignature(addons: List<Addon>, disabledKeys: Set<String>): String {
    val addonCatalogSignature = addons
        .flatMap { addon ->
            addon.catalogs.map { catalog ->
                val extraSignature = catalog.extra.joinToString(";") { extra ->
                    listOf(
                        extra.name,
                        extra.isRequired.toString(),
                        extra.options.orEmpty().joinToString("|"),
                        extra.defaultValue.orEmpty(),
                        extra.optionsLimit?.toString().orEmpty()
                    ).joinToString(":")
                }
                listOf(
                    addon.id,
                    addon.baseUrl,
                    addon.version,
                    addon.configVersion?.toString().orEmpty(),
                    addon.manifestLanguage.orEmpty(),
                    addon.rawTypes.joinToString("|"),
                    addon.resources.joinToString("|") { resource ->
                        listOf(
                            resource.name,
                            resource.types.joinToString("/"),
                            resource.idPrefixes.orEmpty().joinToString("/")
                        ).joinToString(":")
                    },
                    addon.idPrefixes.joinToString("|"),
                    catalog.apiType,
                    catalog.id,
                    catalog.name,
                    catalog.showInHome.toString(),
                    catalog.hasExplicitShowInHome.toString(),
                    catalog.pageSize?.toString().orEmpty(),
                    catalog.extraSupported.joinToString("|"),
                    catalog.extraRequired.joinToString("|"),
                    extraSignature
                ).joinToString("|")
            }
        }
        .sorted()
        .joinToString(separator = ",")
    val disabledSignature = disabledKeys
        .asSequence()
        .sorted()
        .joinToString(separator = ",")
    return "$addonCatalogSignature::$disabledSignature"
}

/**
 * The load gate: a same-signature request is skipped while loads are active (a duplicate
 * emission must not restart a first load) or rows exist; with neither it reloads (the retry after
 * every catalog failed). [hasRows] is read only when the other clauses do not decide.
 */
internal inline fun shouldSkipCatalogLoad(
    forceReload: Boolean,
    signature: String,
    activeSignature: String?,
    hasActiveLoads: Boolean,
    hasRows: () -> Boolean
): Boolean = !forceReload && signature == activeSignature && (hasActiveLoads || hasRows())

/**
 * Eager/lazy split of the Home catalogs to load. Eager follows DISPLAY order (stable when the
 * user reorders rows); `collection_*` keys are not network catalogs and are skipped; display keys
 * without a candidate are dropped; candidates outside the display order follow in their own
 * (manifest) order. The first [eagerCount] are eager, the rest lazy.
 */
internal fun <T> splitEagerLazy(
    candidates: List<Pair<String, T>>,
    displayOrderKeys: List<String>,
    eagerCount: Int
): Pair<List<T>, List<T>> {
    val byKey = candidates.toMap()
    val orderKeys = displayOrderKeys.filterNot { it.startsWith("collection_") }
    val orderKeySet = orderKeys.toSet()
    val ordered = orderKeys.mapNotNull { byKey[it] } +
        candidates.filterNot { (key, _) -> key in orderKeySet }.map { it.second }
    return ordered.take(eagerCount) to ordered.drop(eagerCount)
}

/**
 * Catalog-row publish debounce: 150 ms for the first render (batches near-simultaneous arrivals
 * into one heavy pass), then by catalog loads still pending (placeholders are already visible, so
 * bulk loading batches harder), 80 ms when idle.
 */
internal fun catalogUpdateDebounceMs(firstRender: Boolean, pending: Int): Long = when {
    firstRender -> 150L
    pending > 8 -> 300L
    pending > 3 -> 250L
    pending > 0 -> 200L
    else -> 80L
}

internal fun HomeViewModel.registerCatalogLoadJob(job: Job) {
    synchronized(activeCatalogLoadJobs) {
        activeCatalogLoadJobs.add(job)
    }
    job.invokeOnCompletion {
        synchronized(activeCatalogLoadJobs) {
            activeCatalogLoadJobs.remove(job)
        }
    }
}

internal fun HomeViewModel.cancelInFlightCatalogLoads() {
    val jobsToCancel = synchronized(activeCatalogLoadJobs) {
        activeCatalogLoadJobs.toList().also { activeCatalogLoadJobs.clear() }
    }
    jobsToCancel.forEach { it.cancel() }
}

private fun HomeViewModel.reindexCatalogRow(
    key: String,
    previousRow: CatalogRow?,
    updatedRow: CatalogRow?
) {
    previousRow?.items?.forEach { item ->
        val keys = catalogItemKeyIndex[item.id] ?: return@forEach
        keys.remove(key)
        if (keys.isEmpty()) {
            catalogItemKeyIndex.remove(item.id)
        }
    }

    updatedRow?.items?.forEach { item ->
        catalogItemKeyIndex.getOrPut(item.id) { LinkedHashSet() }.add(key)
    }
}

/** Each addon's current display name, keyed the way rows and placeholders carry it (id + base URL). */
internal fun addonDisplayNames(addons: List<Addon>): Map<Pair<String, String>, String> =
    addons.associate { (it.id to it.baseUrl) to it.displayName }

/** This row with its addon's name from [names]; the row itself when its addon is not there or unchanged. */
internal fun CatalogRow.withAddonNameFrom(names: Map<Pair<String, String>, String>): CatalogRow {
    val name = names[addonId to addonBaseUrl] ?: return this
    return if (name == addonName) this else copy(addonName = name)
}

/** The placeholder twin of [CatalogRow.withAddonNameFrom]. */
internal fun HomeViewModel.PlaceholderDescriptor.withAddonNameFrom(
    names: Map<Pair<String, String>, String>
): HomeViewModel.PlaceholderDescriptor {
    val name = names[addonId to addonBaseUrl] ?: return this
    return if (name == addonName) this else copy(addonName = name)
}

/** Rewrites [rows] and [placeholders] in place with the names in [names]; true when anything changed. */
internal fun relabelAddonNames(
    rows: MutableMap<String, CatalogRow>,
    placeholders: MutableList<HomeViewModel.PlaceholderDescriptor>,
    names: Map<Pair<String, String>, String>
): Boolean {
    var changed = false
    rows.entries.forEach { entry ->
        val relabelled = entry.value.withAddonNameFrom(names)
        if (relabelled !== entry.value) {
            entry.setValue(relabelled)
            changed = true
        }
    }
    placeholders.replaceAll { descriptor ->
        descriptor.withAddonNameFrom(names).also { if (it !== descriptor) changed = true }
    }
    return changed
}

/**
 * Spec 21 C4-R2: an addon rename leaves the load signature unchanged, so nothing reloads, and a
 * reload keeps the rows it already has; relabel the loaded rows and the placeholders in place on
 * every load call. Rows that arrive later take the current name where they land
 * (loadCatalogPipeline). Returns true when anything changed.
 */
internal fun HomeViewModel.relabelCatalogAddonNames(addons: List<Addon>): Boolean =
    synchronized(catalogStateLock) {
        relabelAddonNames(catalogsMap, placeholderDescriptors, addonDisplayNames(addons))
    }

internal fun HomeViewModel.hasAnyCatalogRows(): Boolean = synchronized(catalogStateLock) {
    catalogsMap.isNotEmpty()
}

internal fun HomeViewModel.isCatalogOrderEmpty(): Boolean = synchronized(catalogStateLock) {
    catalogOrder.isEmpty()
}

internal fun HomeViewModel.hasCatalogOrderEntries(): Boolean = synchronized(catalogStateLock) {
    catalogOrder.isNotEmpty()
}

internal fun HomeViewModel.readCatalogRow(key: String): CatalogRow? = synchronized(catalogStateLock) {
    catalogsMap[key]
}

internal fun HomeViewModel.replaceCatalogRow(key: String, row: CatalogRow) {
    synchronized(catalogStateLock) {
        val previousRow = catalogsMap.put(key, row)
        reindexCatalogRow(key, previousRow, row)
    }
}

internal inline fun HomeViewModel.updateCatalogRow(
    key: String,
    transform: (CatalogRow) -> CatalogRow
): CatalogRow? {
    return synchronized(catalogStateLock) {
        val currentRow = catalogsMap[key] ?: return@synchronized null
        val updatedRow = transform(currentRow)
        if (updatedRow != currentRow) {
            catalogsMap[key] = updatedRow
            reindexCatalogRow(key, currentRow, updatedRow)
        }
        updatedRow
    }
}

internal fun HomeViewModel.clearCatalogData() {
    synchronized(catalogStateLock) {
        catalogsMap.clear()
        catalogItemKeyIndex.clear()
        pendingLazyCatalogs.clear()
        placeholderDescriptors.clear()
    }
    lazyLoadRequestedKeys.clear()
}

/** Drops loaded rows whose key is not in [HomeViewModel.catalogOrder]. */
internal fun HomeViewModel.pruneCatalogRows() {
    synchronized(catalogStateLock) {
        val orderedKeys = catalogOrder.toHashSet()
        catalogsMap.keys
            .filter { it !in orderedKeys }
            .forEach { key -> reindexCatalogRow(key, catalogsMap.remove(key), null) }
    }
}

internal fun HomeViewModel.snapshotCatalogState(): Pair<List<String>, Map<String, CatalogRow>> = synchronized(catalogStateLock) {
    catalogOrder.toList() to catalogsMap.toMap()
}

internal fun HomeViewModel.findCatalogItemById(itemId: String): MetaPreview? = synchronized(catalogStateLock) {
    val rowKeys = catalogItemKeyIndex[itemId]?.toList().orEmpty()
    rowKeys.firstNotNullOfOrNull { key ->
        catalogsMap[key]?.items?.firstOrNull { it.id == itemId }
    }
}

internal inline fun HomeViewModel.updateIndexedCatalogItem(
    itemId: String,
    transform: (MetaPreview) -> MetaPreview
): Boolean {
    return synchronized(catalogStateLock) {
        val rowKeys = catalogItemKeyIndex[itemId]?.toList().orEmpty()
        var changed = false

        rowKeys.forEach { key ->
            val row = catalogsMap[key] ?: return@forEach
            val itemIndex = row.items.indexOfFirst { it.id == itemId }
            if (itemIndex < 0) return@forEach

            val updatedItem = transform(row.items[itemIndex])
            if (updatedItem == row.items[itemIndex]) return@forEach

            val mutableItems = row.items.toMutableList()
            mutableItems[itemIndex] = updatedItem
            catalogsMap[key] = row.copy(items = mutableItems)
            changed = true
        }

        changed
    }
}


internal fun HomeViewModel.rebuildCatalogOrder(addons: List<Addon>) {
    val defaultOrder = buildDefaultCatalogOrder(addons)
    val collectionKeys = collectionsCache.map { "collection_${it.id}" }
    val allAvailable = (defaultOrder + collectionKeys).toSet()

    if (followAddonsOrderEnabled) {
        // In follow addons order mode, addon catalogs always stay in manifest order.
        // Collections are positioned based on their relative position in saved order.
        val savedValid = homeCatalogOrderKeys
            .asSequence()
            .filter { it in allAvailable }
            .distinct()
            .toList()

        val collectionKeysSet = collectionKeys.toSet()

        if (savedValid.isNotEmpty()) {
            val result = mutableListOf<String>()
            var addonPointer = 0

            for (savedKey in savedValid) {
                if (savedKey in collectionKeysSet) {
                    result.add(savedKey)
                } else {
                    // Addon catalog - advance manifest pointer to include all up to this one
                    val targetIdx = defaultOrder.indexOf(savedKey)
                    if (targetIdx >= 0) {
                        while (addonPointer <= targetIdx) {
                            val ak = defaultOrder[addonPointer]
                            if (ak !in result) {
                                result.add(ak)
                            }
                            addonPointer++
                        }
                    }
                }
            }
            // Append remaining addon keys
            while (addonPointer < defaultOrder.size) {
                val ak = defaultOrder[addonPointer]
                if (ak !in result) {
                    result.add(ak)
                }
                addonPointer++
            }
            // Append any collections not in saved order
            for (ck in collectionKeys) {
                if (ck !in result) {
                    result.add(ck)
                }
            }

            // Normalize: push collections that ended up mid-addon-block to the block boundary
            val addonKeyToOwner = buildAddonKeyOwnerMap(addons)
            val normalized = normalizeCollectionBoundaries(result, addonKeyToOwner)

            synchronized(catalogStateLock) {
                catalogOrder.clear()
                catalogOrder.addAll(normalized)
            }
        } else {
            // No saved order - manifest order + collections at end
            synchronized(catalogStateLock) {
                catalogOrder.clear()
                catalogOrder.addAll(defaultOrder + collectionKeys)
            }
        }
    } else {
        val savedValid = homeCatalogOrderKeys
            .asSequence()
            .filter { it in allAvailable }
            .distinct()
            .toList()

        val savedSet = savedValid.toSet()
        val unsavedCatalogs = defaultOrder.filterNot { it in savedSet }
        val unsavedCollections = collectionKeys.filterNot { it in savedSet }
        val mergedOrder = savedValid + unsavedCatalogs + unsavedCollections

        synchronized(catalogStateLock) {
            catalogOrder.clear()
            catalogOrder.addAll(mergedOrder)
        }
    }
}

private fun HomeViewModel.buildDefaultCatalogOrder(addons: List<Addon>): List<String> {
    val orderedKeys = mutableListOf<String>()
    addons.forEach { addon ->
        addon.catalogs
            .filter { isHomeCatalog(addon, it) }
            .forEach { catalog ->
                val key = catalogKey(
                    addonId = addon.id,
                    type = catalog.apiType,
                    catalogId = catalog.id
                )
                if (key !in orderedKeys) {
                    orderedKeys.add(key)
                }
            }
    }
    return orderedKeys
}

/** A catalog that belongs on the home rows: shown on home, loadable, and not disabled by the user. */
internal fun HomeViewModel.isHomeCatalog(addon: Addon, catalog: CatalogDescriptor): Boolean {
    return catalog.shouldShowOnHome() &&
        !catalog.hasUnsatisfiableRequiredExtra() &&
        !isCatalogDisabled(
            addonBaseUrl = addon.baseUrl,
            addonId = addon.id,
            type = catalog.apiType,
            catalogId = catalog.id,
            catalogName = catalog.name
        )
}

internal fun HomeViewModel.isCatalogDisabled(
    addonBaseUrl: String,
    addonId: String,
    type: String,
    catalogId: String,
    catalogName: String
): Boolean {
    if (disableCatalogKey(addonBaseUrl, type, catalogId, catalogName) in disabledHomeCatalogKeys) {
        return true
    }
    // Backward compatibility with previously stored keys.
    return catalogKey(addonId, type, catalogId) in disabledHomeCatalogKeys
}

internal fun HomeViewModel.disableCatalogKey(
    addonBaseUrl: String,
    type: String,
    catalogId: String,
    catalogName: String
): String {
    return "${addonBaseUrl}_${type}_${catalogId}_${catalogName}"
}

internal fun MetaPreview.hasHeroArtwork(): Boolean {
    return !background.isNullOrBlank()
}

private fun buildAddonKeyOwnerMap(addons: List<Addon>): Map<String, String> {
    val map = mutableMapOf<String, String>()
    addons.forEach { addon ->
        addon.catalogs.forEach { catalog ->
            map[catalogKey(addonId = addon.id, type = catalog.apiType, catalogId = catalog.id)] = addon.id
        }
    }
    return map
}

private fun normalizeCollectionBoundaries(
    order: List<String>,
    addonKeyToOwner: Map<String, String>
): List<String> {
    val result = order.toMutableList()
    var changed = true
    while (changed) {
        changed = false
        var i = 0
        while (i < result.size) {
            val key = result[i]
            if (!key.startsWith("collection_")) {
                i++
                continue
            }
            val prevOwner = findOwnerBefore(result, i, addonKeyToOwner)
            val nextOwner = findOwnerAfter(result, i, addonKeyToOwner)
            if (prevOwner != null && nextOwner != null && prevOwner == nextOwner) {
                // Collection is mid-block, push to end of this addon block
                result.removeAt(i)
                var insertPos = i
                while (insertPos < result.size &&
                    !result[insertPos].startsWith("collection_") &&
                    addonKeyToOwner[result[insertPos]] == prevOwner
                ) {
                    insertPos++
                }
                result.add(insertPos, key)
                if (insertPos != i) changed = true
                i++
            } else {
                i++
            }
        }
    }
    return result
}

private fun findOwnerBefore(order: List<String>, index: Int, owners: Map<String, String>): String? {
    for (j in index - 1 downTo 0) {
        if (!order[j].startsWith("collection_")) return owners[order[j]]
    }
    return null
}

private fun findOwnerAfter(order: List<String>, index: Int, owners: Map<String, String>): String? {
    for (j in index + 1 until order.size) {
        if (!order[j].startsWith("collection_")) return owners[order[j]]
    }
    return null
}
