package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.core.sync.HomeCatalogOrdering
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
    // MDBList rows (Spec 105 S5b1) are not addon catalogs: hiding one loads nothing.
    val disabledSignature = disabledKeys
        .asSequence()
        .filterNot(HomeCatalogOrdering::isMdbListKey)
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

/** Any addon catalog row loaded (MDBList rows, kept across loads, do not count: Spec 105 S5b1). */
internal fun HomeViewModel.hasAnyCatalogRows(): Boolean = synchronized(catalogStateLock) {
    catalogsMap.keys.any { !HomeCatalogOrdering.isMdbListKey(it) }
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

internal fun HomeViewModel.removeCatalogRow(key: String) {
    synchronized(catalogStateLock) {
        reindexCatalogRow(key, catalogsMap.remove(key), null)
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

/** Clears the addon catalogs' data; MDBList rows stay (not loaded by catalog loads, see [pruneCatalogRows]). */
internal fun HomeViewModel.clearCatalogData() {
    synchronized(catalogStateLock) {
        val mdbListRows = catalogsMap.filterKeys(HomeCatalogOrdering::isMdbListKey)
        catalogsMap.clear()
        catalogItemKeyIndex.clear()
        pendingLazyCatalogs.clear()
        placeholderDescriptors.clear()
        mdbListRows.forEach { (key, row) ->
            catalogsMap[key] = row
            reindexCatalogRow(key, null, row)
        }
    }
    lazyLoadRequestedKeys.clear()
}

/**
 * Drops loaded rows whose key is not in [HomeViewModel.catalogOrder]. MDBList rows stay: their rows come and go with
 * MdbListHomeRows ([applyMdbListRowsPipeline]), and a hidden one must be there again when shown.
 */
internal fun HomeViewModel.pruneCatalogRows() {
    synchronized(catalogStateLock) {
        val orderedKeys = catalogOrder.toHashSet()
        catalogsMap.keys
            .filter { it !in orderedKeys && !HomeCatalogOrdering.isMdbListKey(it) }
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

/** Every stored copy of [itemId], one per row holding it (copies can differ by row: name, genres). */
internal fun HomeViewModel.findCatalogItemCopies(itemId: String): List<MetaPreview> = synchronized(catalogStateLock) {
    catalogItemKeyIndex[itemId]?.toList().orEmpty().mapNotNull { key ->
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
    val order = resolveHomeCatalogOrder(
        defaultOrder = buildDefaultCatalogOrder(addons),
        collectionKeys = collectionsCache.map { "collection_${it.id}" },
        mdbListKeys = mdbListKeys,
        savedOrderKeys = homeCatalogOrderKeys,
        disabledKeys = disabledHomeCatalogKeys,
        followAddonsOrder = followAddonsOrderEnabled,
        addonKeyToOwner = buildAddonKeyOwnerMap(addons)
    )
    synchronized(catalogStateLock) {
        catalogOrder.clear()
        catalogOrder.addAll(order)
    }
}

/**
 * Home's row keys in order: addon catalogs ([defaultOrder], manifest order, disabled ones already out) and free rows
 * (collections, then MDBList's shown keys; Spec 105 S5b1). MDBList keys not saved yet are admitted first, those past 10
 * at once hidden ([HomeCatalogOrdering.admitMdbListKeys]; the same rule MdbListHomeRows stores), so the order is right
 * before the stored admission lands and on a read-only profile, where it never does. Hidden MDBList keys are left out
 * like disabled catalogs; disabled collections stay and are skipped when drawn.
 *
 * Follow mode: addon catalogs keep manifest order, free rows their slot relative to the saved order, normalized so none
 * sits mid-block. Otherwise: the saved order, then unsaved catalogs, then unsaved free rows.
 */
internal fun resolveHomeCatalogOrder(
    defaultOrder: List<String>,
    collectionKeys: List<String>,
    mdbListKeys: List<String>,
    savedOrderKeys: List<String>,
    disabledKeys: Set<String>,
    followAddonsOrder: Boolean,
    addonKeyToOwner: Map<String, String>
): List<String> {
    val admission = HomeCatalogOrdering.admitMdbListKeys(savedOrderKeys, disabledKeys, mdbListKeys)
    val savedOrder = admission?.orderKeys ?: savedOrderKeys
    val hidden = admission?.disabledKeys ?: disabledKeys
    val freeKeys = collectionKeys + mdbListKeys.filterNot { it in hidden }
    val allAvailable = (defaultOrder + freeKeys).toSet()
    val savedValid = savedOrder
        .asSequence()
        .filter { it in allAvailable }
        .distinct()
        .toList()

    if (!followAddonsOrder) {
        val savedSet = savedValid.toSet()
        return savedValid + defaultOrder.filterNot { it in savedSet } + freeKeys.filterNot { it in savedSet }
    }
    // No saved order: manifest order, then free rows.
    if (savedValid.isEmpty()) return defaultOrder + freeKeys

    val freeKeySet = freeKeys.toSet()
    val result = mutableListOf<String>()
    var addonPointer = 0
    for (savedKey in savedValid) {
        if (savedKey in freeKeySet) {
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
    // Append any free rows not in saved order
    for (ck in freeKeys) {
        if (ck !in result) {
            result.add(ck)
        }
    }
    // Normalize: push free rows that ended up mid-addon-block to the block boundary
    return normalizeFreeRowBoundaries(result, addonKeyToOwner)
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

private fun normalizeFreeRowBoundaries(
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
            if (!HomeCatalogOrdering.isFreeRowKey(key)) {
                i++
                continue
            }
            val prevOwner = findOwnerBefore(result, i, addonKeyToOwner)
            val nextOwner = findOwnerAfter(result, i, addonKeyToOwner)
            if (prevOwner != null && nextOwner != null && prevOwner == nextOwner) {
                // Free row is mid-block, push to end of this addon block
                result.removeAt(i)
                var insertPos = i
                while (insertPos < result.size &&
                    !HomeCatalogOrdering.isFreeRowKey(result[insertPos]) &&
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
        if (!HomeCatalogOrdering.isFreeRowKey(order[j])) return owners[order[j]]
    }
    return null
}

private fun findOwnerAfter(order: List<String>, index: Int, owners: Map<String, String>): String? {
    for (j in index + 1 until order.size) {
        if (!HomeCatalogOrdering.isFreeRowKey(order[j])) return owners[order[j]]
    }
    return null
}
