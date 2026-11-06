package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.profile.DpadDirection
import com.nuvio.tv.core.profile.PinLockouts
import com.nuvio.tv.core.profile.PinVerifyOutcome
import com.nuvio.tv.core.sync.ProfileSyncService
import com.nuvio.tv.data.local.ProfileLockStateDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Loaded snapshot of the facts the setup screen's PIN-gate decision needs. */
data class DpadGateState(
    val comboSet: Boolean,
    val primaryHasPin: Boolean
)

/**
 * Drives the D-pad unlock capture flow (docs/specs/006-profile-guard-features.md, T2); the
 * Profiles pane's rows read and toggle through ProfileSettingsViewModel.
 * Storage is device-local: the raw sequence reaches this VM only in memory;
 * the store persists a salted hash. Change/remove are PIN-gated against the
 * primary profile's PIN when one is set (the setup screen opens only from the
 * Profiles pane, which stays primary-only).
 */
@HiltViewModel
class DpadUnlockSettingsViewModel @Inject constructor(
    private val profileLockStateDataStore: ProfileLockStateDataStore,
    private val profileSyncService: ProfileSyncService,
    private val pinLockouts: PinLockouts
) : ViewModel() {

    /**
     * The gate decision must not be made from stateIn placeholders: null until
     * the store has actually emitted. [DpadComboSetupScreen] holds blank until
     * non-null, so the change/remove PIN gate cannot latch open on a cold load.
     */
    val gateState: StateFlow<DpadGateState?> = combine(
        profileLockStateDataStore.dpadUnlockState,
        profileLockStateDataStore.pinEnabled
    ) { state, pinEnabled ->
        DpadGateState(
            comboSet = state.isComboSet,
            primaryHasPin = pinEnabled[PRIMARY_PROFILE_ID] == true
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The primary profile's PIN lockout expiry (elapsedRealtime ms), shared with the picker. */
    val primaryPinLockedUntil: StateFlow<Long?> = pinLockouts.lockedUntil
        .map { it[PRIMARY_PROFILE_ID] }
        .stateIn(viewModelScope, SharingStarted.Eagerly, pinLockouts.lockedUntil.value[PRIMARY_PROFILE_ID])

    private var verifyInProgress = false

    /**
     * Persists the confirmed combination (salted hash only) and arms the toggle.
     * NonCancellable: the screen pops right after save, and the write must
     * survive the ViewModel teardown that follows.
     */
    fun saveCombo(sequence: List<DpadDirection>) {
        viewModelScope.launch {
            withContext(NonCancellable) {
                profileLockStateDataStore.setDpadCombo(sequence)
                profileLockStateDataStore.setDpadUnlockEnabled(true)
            }
        }
    }

    fun removeCombo() {
        viewModelScope.launch {
            withContext(NonCancellable) {
                profileLockStateDataStore.clearDpadCombo()
            }
        }
    }

    /**
     * Verifies [pin] against the primary profile's PIN; anything but
     * [PinVerifyOutcome.Unlocked] keeps the gate closed. A lockout is recorded in
     * [PinLockouts], shared with the profile picker.
     */
    fun verifyPrimaryPin(pin: String, onResult: (PinVerifyOutcome) -> Unit) {
        if (verifyInProgress) return
        verifyInProgress = true
        viewModelScope.launch {
            val result = profileSyncService.verifyProfilePin(PRIMARY_PROFILE_ID, pin)
            verifyInProgress = false
            onResult(pinLockouts.record(PRIMARY_PROFILE_ID, result))
        }
    }

    private companion object {
        const val PRIMARY_PROFILE_ID = 1
    }
}
