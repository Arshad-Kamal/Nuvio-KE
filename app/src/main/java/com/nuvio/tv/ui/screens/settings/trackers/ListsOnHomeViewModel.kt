package com.nuvio.tv.ui.screens.settings.trackers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.mdblist.MdbListHomeIndex
import com.nuvio.tv.data.mdblist.MdbListHomeList
import com.nuvio.tv.data.mdblist.MdbListHomeRows
import com.nuvio.tv.ui.screens.addon.AddonManagementAccess
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** The Lists on Home row's "6 of 9 lists shown": every MDBList Home row, the watchlist included (D33). */
data class ListsOnHomeUi(val shown: Int, val total: Int)

/** [index]'s rows (the watchlist, then every list) counted against the hidden [disabledKeys]. */
internal fun listsOnHomeUi(index: MdbListHomeIndex, disabledKeys: Collection<String>): ListsOnHomeUi {
    val keys = listOf(MdbListHomeList.WATCHLIST_KEY) + index.lists.map { it.key }
    val hidden = disabledKeys.toSet()
    return ListsOnHomeUi(shown = keys.count { it !in hidden }, total = keys.size)
}

/**
 * The "Lists on Home" row of MDBList's account panel (Spec 105 S5b2, A2/E3), which opens Home catalog order: null (no
 * row) while the profile has no MDBList Home rows (signed out, rating-capped, index not read yet) or edits the
 * primary's Home order (read-only, as Content's Home catalog order row). Reads the index MdbListHomeRows already
 * holds: no request.
 */
@HiltViewModel
class ListsOnHomeViewModel @Inject constructor(
    homeRows: MdbListHomeRows,
    layout: LayoutPreferenceDataStore,
    profileManager: ProfileManager
) : ViewModel() {

    val uiState: StateFlow<ListsOnHomeUi?> = combine(
        homeRows.index(),
        layout.disabledHomeCatalogKeys,
        profileManager.profiles,
        profileManager.activeProfileId
    ) { index, disabled, profiles, activeId ->
        if (index == null || AddonManagementAccess.isReadOnly(profiles.find { it.id == activeId })) null
        else listsOnHomeUi(index, disabled)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
