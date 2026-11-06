package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.HomeCatalogOrdering
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.ui.screens.addon.AddonManagementAccess
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** What the Content & Discovery pane shows: its counts and which rows apply. */
data class ContentPaneState(
    /** The Addon Manager's list: disabled addons and failed-manifest placeholders included. */
    val addonCount: Int,
    /** Rows the Home catalog order page lists: addon catalogs shown on Home plus collections. */
    val homeCatalogRows: Int,
    val collectionCount: Int,
    /** A profile using the primary's addons: the phone page edits collections only. */
    val readOnly: Boolean,
    /** As the Addon Manager's Reorder card: hidden for read-only profiles and without addon catalogs on Home. */
    val showCatalogOrder: Boolean
)

internal fun contentPaneState(addons: List<Addon>, collections: List<Collection>, readOnly: Boolean): ContentPaneState {
    // The catalog order page's own entries (enabled addons, search-only catalogs left out).
    val addonCatalogs = HomeCatalogOrdering.buildAddonCatalogEntries(addons).size
    return ContentPaneState(
        addonCount = addons.size,
        homeCatalogRows = addonCatalogs + collections.size,
        collectionCount = collections.size,
        readOnly = readOnly,
        showCatalogOrder = !readOnly && addonCatalogs > 0
    )
}

/** The Content & Discovery pane's counts. The phone QR session is the Addon Manager's ViewModel. */
@HiltViewModel
class ContentDiscoveryViewModel @Inject constructor(
    addonRepository: AddonRepository,
    collectionsDataStore: CollectionsDataStore,
    profileManager: ProfileManager
) : ViewModel() {

    /**
     * Null until every source has emitted once: the pane renders rows only after. The collections
     * read is the one that waits; the addon list and profiles are app-wide StateFlows, warm (loaded
     * at startup) by the time Settings opens.
     */
    val uiState: StateFlow<ContentPaneState?> = combine(
        addonRepository.getInstalledAddons(),
        collectionsDataStore.collections,
        profileManager.profiles,
        profileManager.activeProfileId
    ) { addons, collections, profiles, activeId ->
        contentPaneState(
            addons = addons,
            collections = collections,
            readOnly = AddonManagementAccess.isReadOnly(profiles.find { it.id == activeId })
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
}
