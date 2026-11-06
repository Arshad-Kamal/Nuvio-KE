package com.nuvio.tv.ui.screens.addon

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.sync.HomeCatalogOrdering
import com.nuvio.tv.core.sync.HomeCatalogSettingsSyncService
import com.nuvio.tv.core.sync.homeCollectionKey
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
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
    private val homeCatalogSettingsSyncService: HomeCatalogSettingsSyncService
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
            moveCollectionBetweenAddons(key, -1)
        } else {
            moveCatalog(key, -1)
        }
    }

    fun moveDown(key: String) {
        if (_uiState.value.followAddonsOrder) {
            moveCollectionBetweenAddons(key, 1)
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
            val written = layoutPreferenceDataStore.updateDisabledHomeCatalogKeys { storedDisabled ->
                val storedSet = storedDisabled.toSet()
                val renderedDisabled = renderedEntries
                    .filter { HomeCatalogOrdering.isDisabled(it, storedSet) }
                    .mapTo(LinkedHashSet<String>()) { it.disableKey }
                    .apply { if (disableKey in this) remove(disableKey) else add(disableKey) }
                storedDisabled.filterNot { it in renderedDisableKeys } + renderedDisabled
            }
            if (written) homeCatalogSettingsSyncService.triggerPush()
        }
    }

    /**
     * Rename panel's Save on an addon catalog (Home applies custom titles to addon catalogs
     * only): [title] blank or equal to the catalog's own name restores it. Pushes when written.
     */
    fun renameCatalog(key: String, title: String) {
        if (HomeCatalogOrdering.isCollectionKey(key)) return
        val originalName = orderInputs?.entry(key)?.catalogName ?: return
        viewModelScope.launch {
            if (layoutPreferenceDataStore.setCustomCatalogTitle(key, title, originalName)) {
                homeCatalogSettingsSyncService.triggerPush()
            }
        }
    }

    private fun moveCatalog(key: String, direction: Int) {
        moveStored(key) { _, currentIndex -> currentIndex + direction }
    }

    /**
     * When followAddonsOrder is enabled, collections jump between addon boundaries.
     * Moving up means jumping above the previous addon block (all catalogs from one addon).
     * Moving down means jumping below the next addon block.
     */
    private fun moveCollectionBetweenAddons(key: String, direction: Int) {
        if (!HomeCatalogOrdering.isCollectionKey(key)) return
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
                    )
                }
            }
            if (written) homeCatalogSettingsSyncService.triggerPush()
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
                layoutPreferenceDataStore.followAddonsOrder
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

                val folderCounts = collections.associate { homeCollectionKey(it.id) to it.folders.size }
                val inputs = OrderInputs(
                    addonEntries = HomeCatalogOrdering.buildAddonCatalogEntries(addons),
                    collectionEntries = HomeCatalogOrdering.buildCollectionEntries(collections),
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
            // Home shows custom titles on addon catalogs only, so a synced collection title is ignored here too.
            val displayName = if (isCollection) {
                entry.catalogName
            } else {
                customTitles[key]?.takeIf { it.isNotBlank() } ?: entry.catalogName
            }
            // In follow mode only collections can move (they jump between addon
            // blocks); addon catalogs are pinned to manifest order.
            val pinned = followAddonsOrder && !isCollection

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
                pinnedByFollow = pinned
            )
        }
    }

    /** Catalog entries of one emission; immutable, so press transforms may read it off-main. */
    private class OrderInputs(
        val addonEntries: List<HomeCatalogOrdering.CatalogEntry>,
        val collectionEntries: List<HomeCatalogOrdering.CatalogEntry>,
        val followAddonsOrder: Boolean
    ) {
        private val entryByKey = (addonEntries + collectionEntries).associateBy { it.key }

        fun entry(key: String): HomeCatalogOrdering.CatalogEntry? = entryByKey[key]

        fun addonNameOf(key: String): String? = entryByKey[key]?.addonName

        fun entriesIn(order: List<String>): List<HomeCatalogOrdering.CatalogEntry> =
            order.mapNotNull { entryByKey[it] }

        fun effectiveOrder(savedOrderKeys: List<String>): List<String> =
            HomeCatalogOrdering.effectiveOrder(
                addonEntries = addonEntries,
                collectionEntries = collectionEntries,
                savedOrderKeys = savedOrderKeys,
                followAddonsOrder = followAddonsOrder
            )
    }
}

data class CatalogOrderUiState(
    val isLoading: Boolean = true,
    val items: List<CatalogOrderItem> = emptyList(),
    val followAddonsOrder: Boolean = false
)

/**
 * One Home row. [catalogName] is the shown title (the custom one if set), [originalName] the
 * catalog's own; [folderCount] is set on collection rows only; [pinnedByFollow] marks an addon
 * catalog whose place follows its addon (follow mode), so its moves are dimmed with that reason.
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
    val pinnedByFollow: Boolean
) {
    val isCollection: Boolean get() = HomeCatalogOrdering.isCollectionKey(key)
    val isRenamed: Boolean get() = catalogName != originalName
}
