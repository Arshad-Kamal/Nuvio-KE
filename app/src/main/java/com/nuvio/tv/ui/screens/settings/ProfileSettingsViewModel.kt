package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.DpadUnlockState
import com.nuvio.tv.data.local.ProfileDataStore
import com.nuvio.tv.data.local.ProfileLockStateDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the Profiles pane shows, read from the stores (never a placeholder). */
data class ProfilesPaneState(
    val profileCount: Int,
    val rememberLastProfile: Boolean,
    /** T5: the primary-owned grant letting other profiles into Settings. */
    val settingsAccessGranted: Boolean,
    val dpadUnlock: DpadUnlockState
)

/**
 * The Settings shell's primary-profile gate and the Profiles pane's rows. Live profile management
 * (create/update/delete) lives on the profile management screen.
 */
@HiltViewModel
class ProfileSettingsViewModel @Inject constructor(
    profileManager: ProfileManager,
    private val profileDataStore: ProfileDataStore,
    private val profileLockStateDataStore: ProfileLockStateDataStore
) : ViewModel() {

    val isPrimaryProfileActive: StateFlow<Boolean> = profileManager.isPrimaryProfileActive

    /** Null until every store has emitted: the pane renders rows only after (no dropped first toggle). */
    val paneState: StateFlow<ProfilesPaneState?> = combine(
        profileDataStore.profilesList,
        profileDataStore.rememberLastProfileEnabled,
        profileLockStateDataStore.settingsAccessGranted,
        profileLockStateDataStore.dpadUnlockState
    ) { profiles, rememberLast, accessGranted, dpad ->
        ProfilesPaneState(profiles.size, rememberLast, accessGranted, dpad)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun setRememberLastProfile(enabled: Boolean) {
        viewModelScope.launch { profileDataStore.setRememberLastProfileEnabled(enabled) }
    }

    fun setSettingsAccessGranted(granted: Boolean) {
        viewModelScope.launch { profileLockStateDataStore.setSettingsAccessGranted(granted) }
    }

    fun setDpadUnlockEnabled(enabled: Boolean) {
        viewModelScope.launch { profileLockStateDataStore.setDpadUnlockEnabled(enabled) }
    }
}
