package com.nuvio.tv.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.EpisodeOptionsStyle
import com.nuvio.tv.data.local.EpisodeOptionsStylePreference
import com.nuvio.tv.data.local.ThemeDataStore
import com.nuvio.tv.data.local.UiScalePreference
import com.nuvio.tv.domain.model.AppFont
import com.nuvio.tv.domain.model.AppTheme
import com.nuvio.tv.domain.model.resolveAppTheme
import com.nuvio.tv.domain.model.selectableAppThemes
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ThemeSettingsUiState(
    val selectedTheme: AppTheme,
    val selectedFont: AppFont,
    val amoledMode: Boolean,
    val amoledSurfacesMode: Boolean,
    val screensaverEnabled: Boolean,
    val screensaverTimeoutMinutes: Int,
    val screensaverDimPercent: Int,
    /** Device-wide (UiScalePreference): a step not yet written shows here at once. */
    val interfaceScale: Int,
    val episodeOptionsStyle: EpisodeOptionsStyle
) {
    val availableThemes: List<AppTheme> get() = selectableAppThemes
    val availableFonts: List<AppFont> get() = AppFont.entries
}

sealed class ThemeSettingsEvent {
    data class SelectTheme(val theme: AppTheme) : ThemeSettingsEvent()
    data class SelectFont(val font: AppFont) : ThemeSettingsEvent()
    data class ToggleAmoledMode(val enabled: Boolean) : ThemeSettingsEvent()
    data class ToggleAmoledSurfacesMode(val enabled: Boolean) : ThemeSettingsEvent()
    data class ToggleScreensaver(val enabled: Boolean) : ThemeSettingsEvent()
    data class SelectScreensaverTimeout(val minutes: Int) : ThemeSettingsEvent()
    data class SelectScreensaverDim(val percent: Int) : ThemeSettingsEvent()
    data class StepInterfaceScale(val percent: Int) : ThemeSettingsEvent()
    data class SelectEpisodeOptionsStyle(val style: EpisodeOptionsStyle) : ThemeSettingsEvent()
}

@HiltViewModel
class ThemeSettingsViewModel @Inject constructor(
    private val themeDataStore: ThemeDataStore,
    @param:ApplicationContext private val appContext: Context
) : ViewModel() {

    /** An interface-scale step shown but not yet written (see [stepInterfaceScale]). */
    private val pendingScale = MutableStateFlow<Int?>(null)
    private var scaleWrite: Job? = null

    /** Null until every stored value has loaded: the pane renders rows only after (no dropped first toggle). */
    val uiState: StateFlow<ThemeSettingsUiState?> = combine(
        combine(
            themeDataStore.selectedThemePreference.map(::resolveAppTheme),
            themeDataStore.selectedFont,
            themeDataStore.amoledMode,
            themeDataStore.amoledSurfacesMode,
            ::Appearance
        ),
        combine(
            themeDataStore.screensaverEnabled,
            themeDataStore.screensaverTimeoutMinutes,
            themeDataStore.screensaverDimPercent,
            ::IdleDim
        ),
        combine(
            UiScalePreference.flow(appContext),
            pendingScale,
            EpisodeOptionsStylePreference.flow(appContext)
        ) { stored, pending, episodeStyle -> DevicePrefs(pending ?: stored, episodeStyle) }
    ) { look, idle, device ->
        ThemeSettingsUiState(
            selectedTheme = look.theme,
            selectedFont = look.font,
            amoledMode = look.amoled,
            amoledSurfacesMode = look.amoledSurfaces,
            screensaverEnabled = idle.enabled,
            screensaverTimeoutMinutes = idle.timeoutMinutes,
            screensaverDimPercent = idle.dimPercent,
            interfaceScale = device.interfaceScale,
            episodeOptionsStyle = device.episodeOptionsStyle
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private data class Appearance(val theme: AppTheme, val font: AppFont, val amoled: Boolean, val amoledSurfaces: Boolean)
    private data class IdleDim(val enabled: Boolean, val timeoutMinutes: Int, val dimPercent: Int)
    private data class DevicePrefs(val interfaceScale: Int, val episodeOptionsStyle: EpisodeOptionsStyle)

    fun onEvent(event: ThemeSettingsEvent) {
        when (event) {
            is ThemeSettingsEvent.SelectTheme -> selectTheme(event.theme)
            is ThemeSettingsEvent.SelectFont -> selectFont(event.font)
            is ThemeSettingsEvent.ToggleAmoledMode -> setAmoledMode(event.enabled)
            is ThemeSettingsEvent.ToggleAmoledSurfacesMode -> setAmoledSurfacesMode(event.enabled)
            is ThemeSettingsEvent.ToggleScreensaver -> setScreensaverEnabled(event.enabled)
            is ThemeSettingsEvent.SelectScreensaverTimeout -> setScreensaverTimeout(event.minutes)
            is ThemeSettingsEvent.SelectScreensaverDim -> setScreensaverDim(event.percent)
            is ThemeSettingsEvent.StepInterfaceScale -> stepInterfaceScale(event.percent)
            is ThemeSettingsEvent.SelectEpisodeOptionsStyle -> viewModelScope.launch {
                EpisodeOptionsStylePreference.set(appContext, event.style)
            }
        }
    }

    private fun selectTheme(theme: AppTheme) {
        if (uiState.value?.selectedTheme == theme) return
        viewModelScope.launch {
            themeDataStore.setTheme(theme)
        }
    }

    private fun selectFont(font: AppFont) {
        if (uiState.value?.selectedFont == font) return
        viewModelScope.launch {
            themeDataStore.setFont(font)
        }
    }

    private fun setAmoledMode(enabled: Boolean) {
        if (uiState.value?.amoledMode == enabled) return
        viewModelScope.launch {
            themeDataStore.setAmoledMode(enabled)
        }
    }

    private fun setAmoledSurfacesMode(enabled: Boolean) {
        if (uiState.value?.amoledSurfacesMode == enabled) return
        viewModelScope.launch {
            themeDataStore.setAmoledSurfacesMode(enabled)
        }
    }

    private fun setScreensaverEnabled(enabled: Boolean) {
        if (uiState.value?.screensaverEnabled == enabled) return
        viewModelScope.launch {
            themeDataStore.setScreensaverEnabled(enabled)
        }
    }

    private fun setScreensaverTimeout(minutes: Int) {
        if (uiState.value?.screensaverTimeoutMinutes == minutes) return
        viewModelScope.launch {
            themeDataStore.setScreensaverTimeoutMinutes(minutes)
        }
    }

    private fun setScreensaverDim(percent: Int) {
        if (uiState.value?.screensaverDimPercent == percent) return
        viewModelScope.launch {
            themeDataStore.setScreensaverDimPercent(percent)
        }
    }

    /**
     * Each scale write rescales the whole app (MainActivity), so a step shows at once and is
     * written [UI_SCALE_WRITE_DELAY_MS] after the last one. The VM outlives category switches
     * and sub-routes; leaving Settings inside the window drops the step.
     */
    private fun stepInterfaceScale(percent: Int) {
        pendingScale.value = percent
        scaleWrite?.cancel()
        scaleWrite = viewModelScope.launch {
            delay(UI_SCALE_WRITE_DELAY_MS)
            UiScalePreference.set(appContext, percent)
            // A newer step during the write keeps its own pending value.
            pendingScale.compareAndSet(percent, null)
        }
    }

    private companion object {
        const val UI_SCALE_WRITE_DELAY_MS = 600L
    }
}
