package com.nuvio.tv.core.sync

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.model.requiresSearchExtra

/**
 * Single home for the home-catalog ordering engine (backlog G4-5).
 *
 * Serves the TV Catalog Order screen (CatalogOrderViewModel) and the QR
 * config-server page state (AddonManagerViewModel). The web client's
 * `toggleFollowAddonsOrder` and the follow-mode branch of `moveCatalog` in
 * AddonWebPage.kt mirror this algorithm in JavaScript — change both sides
 * together; cross-surface parity is the point of this file.
 */
object HomeCatalogOrdering {

    const val COLLECTION_KEY_PREFIX = "collection_"

    /** One orderable row: an addon catalog or a user collection. */
    data class CatalogEntry(
        val key: String,
        val disableKey: String,
        val legacyDisableKey: String? = null,
        val catalogName: String,
        val addonName: String,
        val typeLabel: String
    )

    fun isCollectionKey(key: String): Boolean = key.startsWith(COLLECTION_KEY_PREFIX)

    fun isDisabled(entry: CatalogEntry, disabledKeys: Set<String>): Boolean {
        return entry.disableKey in disabledKeys ||
            (entry.legacyDisableKey != null && entry.legacyDisableKey in disabledKeys)
    }

    /**
     * Addon catalog entries in manifest order (enabled addons only, search-only
     * catalogs filtered, duplicate keys deduped).
     */
    fun buildAddonCatalogEntries(addons: List<Addon>): List<CatalogEntry> {
        val entries = mutableListOf<CatalogEntry>()
        val seenKeys = mutableSetOf<String>()

        addons.enabledAddons().forEach { addon ->
            addon.catalogs
                .filterNot { it.requiresSearchExtra() }
                .forEach { catalog ->
                    val key = homeCatalogKey(
                        addonId = addon.id,
                        type = catalog.apiType,
                        catalogId = catalog.id
                    )
                    if (seenKeys.add(key)) {
                        entries.add(
                            CatalogEntry(
                                key = key,
                                disableKey = key,
                                legacyDisableKey = homeLegacyDisabledCatalogKey(
                                    addonBaseUrl = addon.baseUrl,
                                    type = catalog.apiType,
                                    catalogId = catalog.id,
                                    catalogName = catalog.name
                                ),
                                catalogName = catalog.name,
                                addonName = addon.displayName,
                                typeLabel = catalog.apiType
                            )
                        )
                    }
                }
        }

        return entries
    }

    fun buildCollectionEntries(collections: List<Collection>): List<CatalogEntry> {
        return collections.map { collection ->
            val key = homeCollectionKey(collection.id)
            CatalogEntry(
                key = key,
                disableKey = key,
                catalogName = collection.title,
                addonName = "${collection.folders.size} folder" +
                    if (collection.folders.size != 1) "s" else "",
                typeLabel = "collection"
            )
        }
    }

    /**
     * Resolves the effective key order.
     *
     * Non-follow mode: saved order first (stale keys dropped, duplicates
     * merged), unseen entries appended in manifest order.
     *
     * Follow mode: addon catalogs snap to manifest order; collections keep
     * their saved position relative to addon blocks, normalized so no
     * collection sits mid-block. Without a saved order: manifest addons, then
     * collections.
     */
    fun effectiveOrder(
        addonEntries: List<CatalogEntry>,
        collectionEntries: List<CatalogEntry>,
        savedOrderKeys: List<String>,
        followAddonsOrder: Boolean
    ): List<String> {
        val availableMap = (addonEntries + collectionEntries).associateBy { it.key }
        val savedValid = savedOrderKeys.filter { it in availableMap }.distinct()

        if (!followAddonsOrder) {
            val savedKeySet = savedValid.toSet()
            val missing = (addonEntries + collectionEntries).map { it.key }.filterNot { it in savedKeySet }
            return savedValid + missing
        }

        val addonKeys = addonEntries.map { it.key }
        val collectionKeys = collectionEntries.map { it.key }.toSet()

        if (savedValid.isEmpty()) {
            return addonKeys + collectionKeys.toList()
        }

        // Walk the saved order; addon keys flush in manifest order up to each
        // saved position, collections hold their saved slot.
        val result = mutableListOf<String>()
        var addonPointer = 0
        for (savedKey in savedValid) {
            if (savedKey in collectionKeys) {
                result.add(savedKey)
            } else {
                val targetManifestIdx = addonKeys.indexOf(savedKey)
                if (targetManifestIdx >= 0) {
                    while (addonPointer <= targetManifestIdx) {
                        val ak = addonKeys[addonPointer]
                        if (ak !in result) {
                            result.add(ak)
                        }
                        addonPointer++
                    }
                }
            }
        }
        while (addonPointer < addonKeys.size) {
            val ak = addonKeys[addonPointer]
            if (ak !in result) {
                result.add(ak)
            }
            addonPointer++
        }
        for (ck in collectionKeys) {
            if (ck !in result) {
                result.add(ck)
            }
        }
        return normalizeCollectionPositions(result) { key -> availableMap[key]?.addonName }
    }

    /**
     * Ensures collections sit at addon block boundaries: a collection between
     * two catalogs of the same addon is pushed after that addon's block.
     */
    fun normalizeCollectionPositions(
        order: List<String>,
        addonNameOf: (String) -> String?
    ): List<String> {
        val result = order.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            var i = 0
            while (i < result.size) {
                val key = result[i]
                if (!isCollectionKey(key)) {
                    i++
                    continue
                }
                val prevAddon = findAddonNameBefore(result, i, addonNameOf)
                val nextAddon = findAddonNameAfter(result, i, addonNameOf)
                if (prevAddon != null && nextAddon != null && prevAddon == nextAddon) {
                    result.removeAt(i)
                    var insertPos = i
                    while (insertPos < result.size &&
                        !isCollectionKey(result[insertPos]) &&
                        addonNameOf(result[insertPos]) == prevAddon
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

    /**
     * Follow-mode move target for a collection: the index it should be
     * re-inserted at (after removal from [currentIndex]) to jump above the
     * previous / below the next addon block. Null = no move possible.
     * Mirrored by the follow-mode branch of `moveCatalog` in AddonWebPage.kt.
     */
    fun collectionBlockJumpIndex(
        order: List<String>,
        currentIndex: Int,
        direction: Int,
        addonNameOf: (String) -> String?
    ): Int? {
        if (direction < 0) {
            // Moving up: skip adjacent collections, land at the start of the
            // previous addon's block.
            var scanIdx = currentIndex - 1
            while (scanIdx >= 0 && isCollectionKey(order[scanIdx])) {
                scanIdx--
            }
            if (scanIdx < 0) return null // already at top

            val targetAddonName = addonNameOf(order[scanIdx])
            while (scanIdx > 0 && !isCollectionKey(order[scanIdx - 1]) &&
                addonNameOf(order[scanIdx - 1]) == targetAddonName) {
                scanIdx--
            }
            return scanIdx
        } else {
            // Moving down: skip adjacent collections, land at the end of the
            // next addon's block.
            var scanIdx = currentIndex + 1
            while (scanIdx < order.size && isCollectionKey(order[scanIdx])) {
                scanIdx++
            }
            if (scanIdx >= order.size) return null // already at bottom

            val targetAddonName = addonNameOf(order[scanIdx])
            while (scanIdx < order.lastIndex && !isCollectionKey(order[scanIdx + 1]) &&
                addonNameOf(order[scanIdx + 1]) == targetAddonName) {
                scanIdx++
            }
            return scanIdx
        }
    }

    private fun findAddonNameBefore(
        order: List<String>,
        index: Int,
        addonNameOf: (String) -> String?
    ): String? {
        for (j in index - 1 downTo 0) {
            if (!isCollectionKey(order[j])) {
                return addonNameOf(order[j])
            }
        }
        return null
    }

    private fun findAddonNameAfter(
        order: List<String>,
        index: Int,
        addonNameOf: (String) -> String?
    ): String? {
        for (j in index + 1 until order.size) {
            if (!isCollectionKey(order[j])) {
                return addonNameOf(order[j])
            }
        }
        return null
    }
}
