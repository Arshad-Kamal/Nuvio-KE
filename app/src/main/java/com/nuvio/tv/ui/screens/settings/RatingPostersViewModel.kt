package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.posters.RatingPosterPhonePage
import com.nuvio.tv.core.posters.RatingPosterSaveCheck
import com.nuvio.tv.core.posters.RatingPosterServerStarter
import com.nuvio.tv.core.posters.RatingPosterSettings
import com.nuvio.tv.core.posters.RatingPosterState
import com.nuvio.tv.core.posters.RatingPosters
import com.nuvio.tv.core.profile.ProfileManager
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Rating posters panel (Spec 114): the panel, or its Turn off confirm; no panel is null. */
enum class RatingPostersPanelState { OPEN, CONFIRM_OFF }

/**
 * Spec 114 S3, Settings › Integrations › Rating posters (sandbox `s114-rating-posters` stages 1-5, 8). The
 * row and the panel read the active profile's setting from [RatingPosters.state]; a phone send saves through
 * [RatingPosterSaveCheck] and shows here when that state changes. The panel is bound to the profile active
 * when it opened, so a send or Turn off never writes another profile's store.
 */
@HiltViewModel
class RatingPostersViewModel @Inject constructor(
    private val settings: RatingPosterSettings,
    private val profileManager: ProfileManager,
    saveCheck: RatingPosterSaveCheck,
    starter: RatingPosterServerStarter,
) : ViewModel() {

    val state: StateFlow<RatingPosterState> = RatingPosters.state

    private val _panel = MutableStateFlow<RatingPostersPanelState?>(null)
    val panel: StateFlow<RatingPostersPanelState?> = _panel.asStateFlow()

    private val phonePage = RatingPosterPhonePage(
        scope = viewModelScope,
        start = starter::start,
        checkAndSave = saveCheck::checkAndSave,
    )

    /** The QR address while the panel's phone page runs. */
    val phoneUrl: StateFlow<String?> = phonePage.url

    /** The profile the open panel belongs to. */
    private var panelProfileId: Int? = null

    fun open() {
        if (_panel.value != null) return
        // Null only before the profiles load: the panel opens without a phone page then.
        val profileId = profileManager.storedActiveProfileId.value
        panelProfileId = profileId
        _panel.value = RatingPostersPanelState.OPEN
        profileId?.let(phonePage::open)
    }

    /** Back, Done, Home (ON_STOP) or leaving the pane: a send already being checked still gets its answer. */
    fun close() {
        phonePage.close()
        panelProfileId = null
        _panel.value = null
    }

    fun askTurnOff() {
        if (_panel.value == RatingPostersPanelState.OPEN) _panel.value = RatingPostersPanelState.CONFIRM_OFF
    }

    fun cancelTurnOff() {
        if (_panel.value == RatingPostersPanelState.CONFIRM_OFF) _panel.value = RatingPostersPanelState.OPEN
    }

    /**
     * Deletes the saved address from the panel's profile; the panel goes back to stage 2 with a new phone page
     * (new token): a send still being checked on the old one answers Closed instead of saving it back.
     */
    fun confirmTurnOff() {
        val profileId = panelProfileId ?: return
        if (_panel.value != RatingPostersPanelState.CONFIRM_OFF) return
        _panel.value = RatingPostersPanelState.OPEN
        phonePage.open(profileId)
        viewModelScope.launch {
            // Leaving Settings right after the confirm must not cut the delete; a failed write leaves the
            // address saved, and the panel shows it as saved still.
            withContext(NonCancellable) {
                try {
                    settings.clear(profileId)
                } catch (_: IOException) {
                }
            }
        }
    }

    override fun onCleared() {
        phonePage.close()
        super.onCleared()
    }
}
