package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.tmdb.TmdbApiKeyProvider
import com.nuvio.tv.core.tmdb.TmdbKeyCheck
import com.nuvio.tv.core.tmdb.TmdbKeyEntry
import com.nuvio.tv.core.tmdb.TmdbKeySession
import com.nuvio.tv.core.tmdb.TmdbKeyState
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.trailer.TrailerService
import com.nuvio.tv.domain.model.TmdbSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The TMDB key panel (Spec 52 T1); no panel is null. */
enum class TmdbKeyPanelState { OPEN, CHECKING, REJECTED, UNREACHABLE }

@HiltViewModel
class TmdbSettingsViewModel @Inject constructor(
    private val dataStore: TmdbSettingsDataStore,
    private val trailerService: TrailerService,
    private val keyProvider: TmdbApiKeyProvider,
    private val keyEntry: TmdbKeyEntry,
    phoneStarter: TmdbKeyServerStarter
) : ViewModel() {

    /** Null until the active profile's stored values load: the pane renders rows only after. */
    private val _uiState = MutableStateFlow<TmdbSettings?>(null)
    val uiState: StateFlow<TmdbSettings?> = _uiState.asStateFlow()

    /** The device-wide key (null until its store's first read): the key row and the dimmed rows. */
    val keyState: StateFlow<TmdbKeyState?> = keyProvider.state

    private val _keyPanel = MutableStateFlow<TmdbKeyPanelState?>(null)
    val keyPanel: StateFlow<TmdbKeyPanelState?> = _keyPanel.asStateFlow()

    /** True from a save until the pane has shown its toast ([TmdbSettingsEvent.KeySavedNoticeShown]). */
    private val _keySavedNotice = MutableStateFlow(false)
    val keySavedNotice: StateFlow<Boolean> = _keySavedNotice.asStateFlow()

    // The open panel's session (null with no panel); each open or close starts a new one, and a check's
    // answer applies only to its own. Shared with the phone page, whose sends arrive on server threads.
    private var keySession: TmdbKeySession? = null
    private var keyJob: Job? = null

    /** The panel's phone page; its [TmdbKeyPhonePage.url] is the QR while it runs. */
    private val phonePage = TmdbKeyPhonePage(
        scope = viewModelScope,
        start = phoneStarter::start,
        checkAndSave = keyEntry::checkAndSave,
        onSaved = ::keySaved
    )
    val phoneUrl: StateFlow<String?> = phonePage.url

    init {
        viewModelScope.launch {
            dataStore.storedSettings.collectLatest { settings -> _uiState.value = settings }
        }
    }

    fun onEvent(event: TmdbSettingsEvent) {
        when (event) {
            is TmdbSettingsEvent.ToggleEnabled -> update { dataStore.setEnabled(event.enabled) }
            is TmdbSettingsEvent.SetLanguage -> update {
                val newLanguage = event.language.ifBlank { "en" }
                val currentLanguage = _uiState.value?.language.orEmpty().ifBlank { "en" }
                dataStore.setLanguage(newLanguage)
                if (!newLanguage.equals(currentLanguage, ignoreCase = true)) {
                    trailerService.clearCache()
                }
            }
            is TmdbSettingsEvent.ToggleTrailers -> update { dataStore.setUseTrailers(event.enabled) }
            is TmdbSettingsEvent.ToggleMoreLikeThis -> update { dataStore.setUseMoreLikeThis(event.enabled) }
            is TmdbSettingsEvent.ToggleCollections -> update { dataStore.setUseCollections(event.enabled) }
            TmdbSettingsEvent.OpenKeyPanel -> newPanelSession(TmdbKeyPanelState.OPEN)
            // Closing cancels a running check: nothing is saved unless TMDB had already said yes.
            TmdbSettingsEvent.CloseKeyPanel -> newPanelSession(null)
            is TmdbSettingsEvent.SubmitKey -> submitKey(event.key)
            TmdbSettingsEvent.RemoveKey -> removeKey()
            TmdbSettingsEvent.KeySavedNoticeShown -> _keySavedNotice.value = false
        }
    }

    /**
     * Ends the current panel session (its check or removal, keyJob, is cancelled; its phone page closes),
     * then shows [panel], with a new session and phone page when it is open. Called from inside keyJob
     * too (a finished check or removal), where it cancels that job itself: nothing may suspend after it
     * there.
     */
    private fun newPanelSession(panel: TmdbKeyPanelState?) {
        keySession?.close()
        keyJob?.cancel()
        keyJob = null
        phonePage.close()
        val session = panel?.let { TmdbKeySession() }
        keySession = session
        _keyPanel.value = panel
        session?.let(phonePage::open)
    }

    /** TMDB accepted a key for [session] (TV field or phone, main thread): its panel closes with the toast. */
    private fun keySaved(session: TmdbKeySession) {
        if (session !== keySession) return
        _keySavedNotice.value = true
        newPanelSession(null)
    }

    // A check or removal still running: one at a time, so neither ends the other's session.
    private fun keyActionBlocked(): Boolean = keyJob?.isActive == true

    /**
     * Blank input, a closed panel or a check or removal already running: nothing happens. A check skipped
     * because the phone saved a key first leaves the closing to the phone's [keySaved].
     */
    private fun submitKey(key: String) {
        val session = keySession
        if (session == null || keyActionBlocked() || key.isBlank()) return
        _keyPanel.value = TmdbKeyPanelState.CHECKING
        keyJob = viewModelScope.launch {
            val result = keyEntry.checkAndSave(key, session) ?: return@launch
            if (session !== keySession) return@launch
            when (result) {
                TmdbKeyCheck.ACCEPTED -> keySaved(session)
                TmdbKeyCheck.REJECTED -> _keyPanel.value = TmdbKeyPanelState.REJECTED
                TmdbKeyCheck.UNREACHABLE -> _keyPanel.value = TmdbKeyPanelState.UNREACHABLE
            }
        }
    }

    /**
     * Removes the key the panel shows and closes the panel; a failed write, or a key saved meanwhile,
     * leaves it open (the key stays).
     */
    private fun removeKey() {
        val session = keySession
        val shownKey = keyProvider.state.value?.userKey
        if (session == null || shownKey.isNullOrEmpty() || keyActionBlocked()) return
        keyJob = viewModelScope.launch {
            val removed = keyEntry.remove(session, shownKey)
            if (removed && session === keySession) newPanelSession(null)
        }
    }

    private fun update(action: suspend () -> Unit) {
        viewModelScope.launch { action() }
    }

    // A send from the phone still being checked keeps its answer (TmdbKeyServer.close).
    override fun onCleared() {
        keySession?.close()
        phonePage.close()
        super.onCleared()
    }
}

sealed class TmdbSettingsEvent {
    data class ToggleEnabled(val enabled: Boolean) : TmdbSettingsEvent()
    data class SetLanguage(val language: String) : TmdbSettingsEvent()
    data class ToggleTrailers(val enabled: Boolean) : TmdbSettingsEvent()
    data class ToggleMoreLikeThis(val enabled: Boolean) : TmdbSettingsEvent()
    data class ToggleCollections(val enabled: Boolean) : TmdbSettingsEvent()
    data object OpenKeyPanel : TmdbSettingsEvent()
    data object CloseKeyPanel : TmdbSettingsEvent()
    data class SubmitKey(val key: String) : TmdbSettingsEvent() {
        /** Never the key itself: this may reach a log. */
        override fun toString(): String = "SubmitKey"
    }
    data object RemoveKey : TmdbSettingsEvent()
    data object KeySavedNoticeShown : TmdbSettingsEvent()
}
