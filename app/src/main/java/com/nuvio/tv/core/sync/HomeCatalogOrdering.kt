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
 *
 * MDBList's list rows (Spec 105 S5, D2/D4; keys [MDBLIST_KEY_PREFIX]) are free rows like collections: they keep
 * their slot in follow mode and never sit mid-block. They live only on this TV's profile: the phone page and the
 * Nuvio cloud payload never carry them, so their writers keep them through [keepLocalMdbListKeys] and
 * [keepLocalMdbListDisabled], and the JS mirror never meets one.
 */
object HomeCatalogOrdering {

    const val COLLECTION_KEY_PREFIX = "collection_"

    /** MDBList's Home rows: `mdblist_watchlist`, `mdblist_<list id>`, `mdblist_ext_<external list id>`. */
    const val MDBLIST_KEY_PREFIX = "mdblist_"

    /** Lists arriving at once past this many start hidden (D2; upstream bcd3efe60: ~150 lists filled the heap). */
    const val MDBLIST_SHOWN_ON_ARRIVAL = 10

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

    /** Exactly MDBList's three key forms: an addon whose id is `mdblist` makes `mdblist_<type>_<catalog>`, never these. */
    fun isMdbListKey(key: String): Boolean = MDBLIST_KEY.matches(key)

    private val MDBLIST_KEY = Regex("${MDBLIST_KEY_PREFIX}(watchlist|ext_[1-9][0-9]*|[1-9][0-9]*)")

    /** A row that is not an addon catalog: it holds its own slot (follow mode) and sits only between addon blocks. */
    fun isFreeRowKey(key: String): Boolean = isCollectionKey(key) || isMdbListKey(key)

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
     *
     * Both modes: MDBList entries ([mdbListEntries]) hold their saved slot like
     * collections; one not in the saved order yet goes first (D2: new lists
     * enter right after Continue Watching).
     */
    fun effectiveOrder(
        addonEntries: List<CatalogEntry>,
        collectionEntries: List<CatalogEntry>,
        savedOrderKeys: List<String>,
        followAddonsOrder: Boolean,
        mdbListEntries: List<CatalogEntry> = emptyList()
    ): List<String> {
        val availableMap = (addonEntries + collectionEntries + mdbListEntries).associateBy { it.key }
        val savedValid = savedOrderKeys.filter { it in availableMap }.distinct()
        val savedKeySet = savedValid.toSet()
        val newMdbList = mdbListEntries.map { it.key }.filterNot { it in savedKeySet }.distinct()

        if (!followAddonsOrder) {
            val missing = (addonEntries + collectionEntries).map { it.key }.filterNot { it in savedKeySet }
            return newMdbList + savedValid + missing
        }

        val addonKeys = addonEntries.map { it.key }
        // Free rows holding a saved slot: collections and MDBList rows.
        val freeRowKeys = (collectionEntries + mdbListEntries).map { it.key }.toSet()

        if (savedValid.isEmpty()) {
            return newMdbList + addonKeys + collectionEntries.map { it.key }.distinct()
        }

        // Walk the saved order; addon keys flush in manifest order up to each
        // saved position, free rows hold their saved slot.
        val result = mutableListOf<String>()
        var addonPointer = 0
        for (savedKey in savedValid) {
            if (savedKey in freeRowKeys) {
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
        for (ck in collectionEntries.map { it.key }) {
            if (ck !in result) {
                result.add(ck)
            }
        }
        return normalizeCollectionPositions(newMdbList + result) { key -> availableMap[key]?.addonName }
    }

    /** The saved order and hidden keys after MDBList keys first seen ([available] not in [savedOrder]) arrive. */
    data class MdbListAdmission(val orderKeys: List<String>, val disabledKeys: Set<String>)

    /**
     * D2: [available] MDBList keys not in [savedOrder] join it at the front, in [available]'s order; when more than
     * [MDBLIST_SHOWN_ON_ARRIVAL] arrive at once, those past it also join [disabledKeys] (hidden, never dropped). Null
     * when none is new. Writing them in makes them known: a later arrival never re-hides one the user showed.
     */
    fun admitMdbListKeys(savedOrder: List<String>, disabledKeys: Set<String>, available: List<String>): MdbListAdmission? {
        val saved = savedOrder.toSet()
        val arriving = available.filter { isMdbListKey(it) && it !in saved }.distinct()
        if (arriving.isEmpty()) return null
        val hidden = if (arriving.size > MDBLIST_SHOWN_ON_ARRIVAL) arriving.drop(MDBLIST_SHOWN_ON_ARRIVAL) else emptyList()
        return MdbListAdmission(arriving + savedOrder, disabledKeys + hidden)
    }

    /**
     * [incoming] (an order written by a surface that never carries MDBList keys: the phone page, a cloud pull) with
     * [local]'s MDBList keys it lacks put back, each right after the key it followed in [local] (the front when none).
     */
    fun keepLocalMdbListKeys(local: List<String>, incoming: List<String>): List<String> {
        val incomingSet = incoming.toSet()
        val missing = local.filter { isMdbListKey(it) && it !in incomingSet }.toSet()
        if (missing.isEmpty()) return incoming
        val result = incoming.toMutableList()
        var anchor: String? = null
        for (key in local) {
            if (key in missing) {
                result.add(anchor?.let { result.indexOf(it) + 1 } ?: 0, key)
                anchor = key
            } else if (key in incomingSet) {
                anchor = key
            }
        }
        return result.distinct()
    }

    /** [incoming] hidden keys plus [local]'s hidden MDBList keys that [incomingOrder] does not name. */
    fun keepLocalMdbListDisabled(local: Set<String>, incomingOrder: List<String>, incoming: Iterable<String>): Set<String> {
        val named = incomingOrder.toSet()
        return incoming.toSet() + local.filter { isMdbListKey(it) && it !in named }
    }

    /**
     * Ensures free rows (collections, MDBList rows) sit at addon block boundaries: a collection between
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
                if (!isFreeRowKey(key)) {
                    i++
                    continue
                }
                val prevAddon = findAddonNameBefore(result, i, addonNameOf)
                val nextAddon = findAddonNameAfter(result, i, addonNameOf)
                if (prevAddon != null && nextAddon != null && prevAddon == nextAddon) {
                    result.removeAt(i)
                    var insertPos = i
                    while (insertPos < result.size &&
                        !isFreeRowKey(result[insertPos]) &&
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
            while (scanIdx >= 0 && isFreeRowKey(order[scanIdx])) {
                scanIdx--
            }
            if (scanIdx < 0) return null // already at top

            val targetAddonName = addonNameOf(order[scanIdx])
            while (scanIdx > 0 && !isFreeRowKey(order[scanIdx - 1]) &&
                addonNameOf(order[scanIdx - 1]) == targetAddonName) {
                scanIdx--
            }
            return scanIdx
        } else {
            // Moving down: skip adjacent collections, land at the end of the
            // next addon's block.
            var scanIdx = currentIndex + 1
            while (scanIdx < order.size && isFreeRowKey(order[scanIdx])) {
                scanIdx++
            }
            if (scanIdx >= order.size) return null // already at bottom

            val targetAddonName = addonNameOf(order[scanIdx])
            while (scanIdx < order.lastIndex && !isFreeRowKey(order[scanIdx + 1]) &&
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
            if (!isFreeRowKey(order[j])) {
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
            if (!isFreeRowKey(order[j])) {
                return addonNameOf(order[j])
            }
        }
        return null
    }
}
