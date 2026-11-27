package com.nuvio.tv.ui.screens.addon

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.sync.HomeCatalogOrdering
import com.nuvio.tv.core.sync.HomeCatalogSettingsSyncService
import com.nuvio.tv.core.sync.homeCollectionKey
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.mdblist.MdbListHomeIndex
import com.nuvio.tv.data.mdblist.MdbListHomeList
import com.nuvio.tv.data.mdblist.MdbListHomeListKind
import com.nuvio.tv.data.mdblist.MDBLIST_BRAND
import com.nuvio.tv.data.mdblist.MdbListHomeRows
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.repository.AddonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CatalogOrderViewModel @Inject constructor(
    private val addonRepository: AddonRepository,
    private val collectionsDataStore: CollectionsDataStore,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val homeCatalogSettingsSyncService: HomeCatalogSettingsSyncService,
    private val mdbListHomeRows: MdbListHomeRows
) : ViewModel() {

    private val _uiState = MutableStateFlow(CatalogOrderUiState())
    val uiState: StateFlow<CatalogOrderUiState> = _uiState.asStateFlow()

    /** Entries behind the rendered [uiState]; set with it, on the main thread. */
    private var orderInputs: OrderInputs? = null

    init {
        observeCatalogs()
    }

    fun moveUp(key: String) {
        if (_uiState.value.followAddonsOrder) {
            moveFreeRowBetweenAddons(key, -1)
        } else {
            moveCatalog(key, -1)
        }
    }

    fun moveDown(key: String) {
        if (_uiState.value.followAddonsOrder) {
            moveFreeRowBetweenAddons(key, 1)
        } else {
            moveCatalog(key, 1)
        }
    }

    fun toggleFollowAddonsOrder(enabled: Boolean) {
        viewModelScope.launch {
            layoutPreferenceDataStore.setFollowAddonsOrder(enabled)
        }
    }

    // L5-10/29/51/73: moves and toggles re-derive from the STORED keys inside one DataStore
    // edit, so a press made before the previous write re-emits applies on top of it instead
    // of overwriting it from a stale snapshot. Rendered entries exclude catalogs of disabled
    // addons and of addons whose manifest is still loading; their stored keys are kept
    // as-is, never rebuilt away by a press (and pushed to synced TVs).

    fun toggleCatalogEnabled(disableKey: String) {
        val inputs = orderInputs ?: return
        val renderedEntries = inputs.entriesIn(_uiState.value.items.map { it.key })
        val renderedDisableKeys = renderedEntries
            .flatMap { listOfNotNull(it.disableKey, it.legacyDisableKey) }
            .toSet()
        viewModelScope.launch {
            var synced = false
            val written = layoutPreferenceDataStore.updateDisabledHomeCatalogKeys { storedDisabled ->
                val storedSet = storedDisabled.toSet()
                val renderedDisabled = renderedEntries
                    .filter { HomeCatalogOrdering.isDisabled(it, storedSet) }
                    .mapTo(LinkedHashSet<String>()) { it.disableKey }
                    .apply { if (disableKey in this) remove(disableKey) else add(disableKey) }
                (storedDisabled.filterNot { it in renderedDisableKeys } + renderedDisabled)
                    .also { synced = syncedKeysChanged(storedDisabled, it) }
            }
            if (written && synced) homeCatalogSettingsSyncService.triggerPush()
        }
    }

    /**
     * Rename panel's Save on an addon catalog or an MDBList row (Home applies custom titles to
     * those, never to collections): [title] blank or equal to the row's own name restores it.
     * Pushes when written, except for an MDBList row (its title is never synced).
     */
    fun renameCatalog(key: String, title: String) {
        if (HomeCatalogOrdering.isCollectionKey(key)) return
        val originalName = orderInputs?.entry(key)?.catalogName ?: return
        viewModelScope.launch {
            val written = layoutPreferenceDataStore.setCustomCatalogTitle(key, title, originalName)
            // The synced payload never carries MDBList rows (Spec 105 S5a: per TV profile).
            if (written && !HomeCatalogOrdering.isMdbListKey(key)) homeCatalogSettingsSyncService.triggerPush()
        }
    }

    /**
     * Whether a write changed what the synced payload is built from: [before] and [after] without MDBList keys (never
     * synced), in order (an order-only change of the hidden list may push once more than needed: harmless). A press on
     * an MDBList row pushes only when it also rewrote synced keys (a follow-mode snap, a synced key moved past an
     * unrendered one, a legacy disable key migrated).
     */
    private fun syncedKeysChanged(before: List<String>, after: List<String>): Boolean =
        before.filterNot(HomeCatalogOrdering::isMdbListKey) != after.filterNot(HomeCatalogOrdering::isMdbListKey)

    private fun moveCatalog(key: String, direction: Int) {
        moveStored(key) { _, currentIndex -> currentIndex + direction }
    }

    /**
     * When followAddonsOrder is enabled, free rows (collections, MDBList rows) jump between addon boundaries.
     * Moving up means jumping above the previous addon block (all catalogs from one addon).
     * Moving down means jumping below the next addon block.
     */
    private fun moveFreeRowBetweenAddons(key: String, direction: Int) {
        if (!HomeCatalogOrdering.isFreeRowKey(key)) return
        val addonNameOf = orderInputs?.let { it::addonNameOf } ?: return
        moveStored(key) { currentKeys, currentIndex ->
            HomeCatalogOrdering.collectionBlockJumpIndex(
                order = currentKeys,
                currentIndex = currentIndex,
                direction = direction,
                addonNameOf = addonNameOf
            )
        }
    }

    /**
     * Moves [key] within the effective order to [targetIndex] (null = no move) and writes
     * the result back into the stored order's rendered slots ([spliceRendered]).
     */
    private fun moveStored(
        key: String,
        targetIndex: (currentKeys: List<String>, currentIndex: Int) -> Int?
    ) {
        val inputs = orderInputs ?: return
        viewModelScope.launch {
            var synced = false
            val written = layoutPreferenceDataStore.updateHomeCatalogOrderKeys { savedOrder ->
                val currentKeys = inputs.effectiveOrder(savedOrder)
                val currentIndex = currentKeys.indexOf(key)
                val newIndex = if (currentIndex == -1) null else targetIndex(currentKeys, currentIndex)
                if (newIndex == null || newIndex == currentIndex || newIndex !in currentKeys.indices) {
                    null
                } else {
                    spliceRendered(
                        savedOrder = savedOrder,
                        reordered = currentKeys.toMutableList().apply { add(newIndex, removeAt(currentIndex)) }
                    ).also { synced = syncedKeysChanged(savedOrder, it) }
                }
            }
            if (written && synced) homeCatalogSettingsSyncService.triggerPush()
        }
    }

    /**
     * [reordered] (every rendered key, in the new order) fills the stored order's rendered
     * slots in turn; unrendered stored keys keep their slots; rendered keys the stored order
     * lacked follow at the end. Without unrendered keys the result is [reordered] itself.
     */
    private fun spliceRendered(savedOrder: List<String>, reordered: List<String>): List<String> {
        val rendered = reordered.toSet()
        var next = 0
        val spliced = savedOrder.map { if (it in rendered) reordered[next++] else it }
        return spliced + reordered.drop(next)
    }

    private fun observeCatalogs() {
        viewModelScope.launch {
            combine(
                addonRepository.getInstalledAddons(),
                collectionsDataStore.collections,
                layoutPreferenceDataStore.homeCatalogOrderKeys,
                layoutPreferenceDataStore.disabledHomeCatalogKeys,
                layoutPreferenceDataStore.customCatalogTitles,
                layoutPreferenceDataStore.followAddonsOrder,
                mdbListHomeRows.index()
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                val addons = values[0] as List<Addon>
                @Suppress("UNCHECKED_CAST")
                val collections = values[1] as List<Collection>
                @Suppress("UNCHECKED_CAST")
                val savedOrderKeys = values[2] as List<String>
                @Suppress("UNCHECKED_CAST")
                val disabledKeys = (values[3] as List<String>).toSet()
                @Suppress("UNCHECKED_CAST")
                val customTitles = values[4] as Map<String, String>
                val followAddons = values[5] as Boolean
                val mdbListIndex = values[6] as MdbListHomeIndex?

                val folderCounts = collections.associate { homeCollectionKey(it.id) to it.folders.size }
                val inputs = OrderInputs(
                    addonEntries = HomeCatalogOrdering.buildAddonCatalogEntries(addons),
                    collectionEntries = HomeCatalogOrdering.buildCollectionEntries(collections),
                    mdbListEntries = mdbListIndex?.let(::mdbListOrderEntries).orEmpty(),
                    mdbListRows = mdbListIndex?.let(::mdbListRowFacts).orEmpty(),
                    followAddonsOrder = followAddons
                )
                val items = buildOrderedCatalogItems(
                    inputs = inputs,
                    savedOrderKeys = savedOrderKeys,
                    disabledKeys = disabledKeys,
                    customTitles = customTitles,
                    folderCounts = folderCounts
                )
                Pair(inputs, items)
            }.collectLatest { (inputs, orderedItems) ->
                orderInputs = inputs
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        items = orderedItems,
                        followAddonsOrder = inputs.followAddonsOrder
                    )
                }
            }
        }
    }

    private fun buildOrderedCatalogItems(
        inputs: OrderInputs,
        savedOrderKeys: List<String>,
        disabledKeys: Set<String>,
        customTitles: Map<String, String>,
        folderCounts: Map<String, Int>
    ): List<CatalogOrderItem> {
        val followAddonsOrder = inputs.followAddonsOrder
        val effectiveOrder = inputs.effectiveOrder(savedOrderKeys)

        return effectiveOrder.mapIndexedNotNull { index, key ->
            val entry = inputs.entry(key) ?: return@mapIndexedNotNull null
            val isCollection = HomeCatalogOrdering.isCollectionKey(key)
            // Home shows custom titles on addon catalogs and MDBList rows only, so a synced collection title is ignored here too.
            val displayName = if (isCollection) {
                entry.catalogName
            } else {
                customTitles[key]?.takeIf { it.isNotBlank() } ?: entry.catalogName
            }
            // In follow mode only free rows can move (they jump between addon
            // blocks); addon catalogs are pinned to manifest order.
            val pinned = followAddonsOrder && !HomeCatalogOrdering.isFreeRowKey(key)

            CatalogOrderItem(
                key = entry.key,
                disableKey = entry.disableKey,
                catalogName = displayName,
                addonName = entry.addonName,
                typeLabel = entry.typeLabel,
                isDisabled = HomeCatalogOrdering.isDisabled(entry, disabledKeys),
                canMoveUp = !pinned && index > 0,
                canMoveDown = !pinned && index < effectiveOrder.lastIndex,
                originalName = entry.catalogName,
                folderCount = if (isCollection) folderCounts[key] ?: 0 else null,
                pinnedByFollow = pinned,
                mdbList = inputs.mdbListRows[key]
            )
        }
    }

    /** Catalog entries of one emission; immutable, so press transforms may read it off-main. */
    private class OrderInputs(
        val addonEntries: List<HomeCatalogOrdering.CatalogEntry>,
        val collectionEntries: List<HomeCatalogOrdering.CatalogEntry>,
        val mdbListEntries: List<HomeCatalogOrdering.CatalogEntry>,
        val mdbListRows: Map<String, MdbListRowFacts>,
        val followAddonsOrder: Boolean
    ) {
        private val entryByKey = (addonEntries + collectionEntries + mdbListEntries).associateBy { it.key }

        fun entry(key: String): HomeCatalogOrdering.CatalogEntry? = entryByKey[key]

        fun addonNameOf(key: String): String? = entryByKey[key]?.addonName

        fun entriesIn(order: List<String>): List<HomeCatalogOrdering.CatalogEntry> =
            order.mapNotNull { entryByKey[it] }

        fun effectiveOrder(savedOrderKeys: List<String>): List<String> =
            HomeCatalogOrdering.effectiveOrder(
                addonEntries = addonEntries,
                collectionEntries = collectionEntries,
                savedOrderKeys = savedOrderKeys,
                followAddonsOrder = followAddonsOrder,
                mdbListEntries = mdbListEntries
            )
    }
}

/**
 * Spec 105 S5b2 (D2, mockup v3 D2): the watchlist and every list of the index (hidden ones too) as order entries. Free
 * rows: follow mode never reads their [HomeCatalogOrdering.CatalogEntry.addonName] for addon blocks.
 */
internal fun mdbListOrderEntries(index: MdbListHomeIndex): List<HomeCatalogOrdering.CatalogEntry> =
    listOf(mdbListOrderEntry(MdbListHomeList.WATCHLIST_KEY, index.watchlistTitle)) +
        index.lists.map { mdbListOrderEntry(it.key, it.name) }

/** What each MDBList row's source line names (D30-D32): its kind and title count. */
internal fun mdbListRowFacts(index: MdbListHomeIndex): Map<String, MdbListRowFacts> =
    mapOf(MdbListHomeList.WATCHLIST_KEY to MdbListRowFacts(MdbListRowKind.WATCHLIST, itemCount = null)) +
        index.lists.associate { list ->
            val kind = when (list.kind) {
                MdbListHomeListKind.OWN -> if (list.dynamic) MdbListRowKind.DYNAMIC else MdbListRowKind.OWN
                MdbListHomeListKind.LIKED -> MdbListRowKind.LIKED
                MdbListHomeListKind.EXTERNAL -> MdbListRowKind.IMPORTED
            }
            list.key to MdbListRowFacts(kind, list.itemCount)
        }

private fun mdbListOrderEntry(key: String, name: String) =
    HomeCatalogOrdering.CatalogEntry(key = key, disableKey = key, catalogName = name, addonName = MDBLIST_BRAND, typeLabel = "")

data class CatalogOrderUiState(
    val isLoading: Boolean = true,
    val items: List<CatalogOrderItem> = emptyList(),
    val followAddonsOrder: Boolean = false
)

/**
 * One Home row. [catalogName] is the shown title (the custom one if set), [originalName] the
 * catalog's own; [folderCount] is set on collection rows only; [pinnedByFollow] marks an addon
 * catalog whose place follows its addon (follow mode), so its moves are dimmed with that reason;
 * [mdbList] is set on MDBList rows (Spec 105 S5b2).
 */
data class CatalogOrderItem(
    val key: String,
    val disableKey: String,
    val catalogName: String,
    val addonName: String,
    val typeLabel: String,
    val isDisabled: Boolean,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
    val originalName: String,
    val folderCount: Int?,
    val pinnedByFollow: Boolean,
    val mdbList: MdbListRowFacts? = null
) {
    val isCollection: Boolean get() = HomeCatalogOrdering.isCollectionKey(key)
    val isMdbList: Boolean get() = mdbList != null
    val isRenamed: Boolean get() = catalogName != originalName
}

/** An MDBList row's kind as its source line names it (mockup v3 D2; D30 dynamic, D31 liked / imported). */
enum class MdbListRowKind { WATCHLIST, OWN, DYNAMIC, LIKED, IMPORTED }

/** [itemCount]: the list's title count from the index; null for the watchlist (its line names no count). */
data class MdbListRowFacts(val kind: MdbListRowKind, val itemCount: Int?)
