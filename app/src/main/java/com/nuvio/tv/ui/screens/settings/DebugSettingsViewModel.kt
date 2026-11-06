package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DebugSettingsViewModel @Inject constructor(
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val playerSettingsDataStore: PlayerSettingsDataStore
) : ViewModel() {

    /** Null until both stores have emitted: the pane renders rows only after (no dropped first toggle). */
    val uiState: StateFlow<DebugSettingsUiState?> = combine(
        layoutPreferenceDataStore.composeHighlighterEnabled,
        playerSettingsDataStore.playerSettings.map { it.enableBufferLogs }
    ) { highlighter, bufferLogs ->
        DebugSettingsUiState(composeHighlighterEnabled = highlighter, bufferLogsEnabled = bufferLogs)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun onEvent(event: DebugSettingsEvent) {
        when (event) {
            is DebugSettingsEvent.ToggleComposeHighlighter -> {
                viewModelScope.launch { layoutPreferenceDataStore.setComposeHighlighterEnabled(event.enabled) }
            }
            is DebugSettingsEvent.ToggleBufferLogs -> {
                viewModelScope.launch { playerSettingsDataStore.setEnableBufferLogs(event.enabled) }
            }
        }
    }
}

data class DebugSettingsUiState(
    val composeHighlighterEnabled: Boolean,
    val bufferLogsEnabled: Boolean
)

sealed class DebugSettingsEvent {
    data class ToggleComposeHighlighter(val enabled: Boolean) : DebugSettingsEvent()
    data class ToggleBufferLogs(val enabled: Boolean) : DebugSettingsEvent()
}
