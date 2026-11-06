package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The Advanced pane's General switches, read from the store (never a placeholder). */
data class AdvancedSettingsUiState(
    val fastHorizontalNavigationEnabled: Boolean,
    val smoothBringIntoViewEnabled: Boolean,
    val addonHealthEnabled: Boolean
)

sealed class AdvancedSettingsEvent {
    data class SetFastHorizontalNavigationEnabled(val enabled: Boolean) : AdvancedSettingsEvent()
    data class SetSmoothBringIntoViewEnabled(val enabled: Boolean) : AdvancedSettingsEvent()
    data class SetAddonHealthEnabled(val enabled: Boolean) : AdvancedSettingsEvent()
}

@HiltViewModel
class AdvancedSettingsViewModel @Inject constructor(
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore
) : ViewModel() {

    /** Null until every preference has emitted: the pane renders rows only after (no dropped first toggle). */
    val uiState: StateFlow<AdvancedSettingsUiState?> = combine(
        layoutPreferenceDataStore.fastHorizontalNavigationEnabled,
        layoutPreferenceDataStore.smoothBringIntoViewEnabled,
        layoutPreferenceDataStore.addonHealthEnabled,
        ::AdvancedSettingsUiState
    ).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun onEvent(event: AdvancedSettingsEvent) {
        viewModelScope.launch {
            when (event) {
                is AdvancedSettingsEvent.SetFastHorizontalNavigationEnabled ->
                    layoutPreferenceDataStore.setFastHorizontalNavigationEnabled(event.enabled)
                is AdvancedSettingsEvent.SetSmoothBringIntoViewEnabled ->
                    layoutPreferenceDataStore.setSmoothBringIntoViewEnabled(event.enabled)
                is AdvancedSettingsEvent.SetAddonHealthEnabled ->
                    layoutPreferenceDataStore.setAddonHealthEnabled(event.enabled)
            }
        }
    }
}
