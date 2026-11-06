package com.nuvio.tv.ui.screens.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nuvio.tv.core.profile.DpadCombo
import com.nuvio.tv.core.profile.DpadDirection

/** Screen steps of the combination setup capture flow (create -> confirm -> saved). */
internal enum class DpadSetupStep {
    Create,
    Confirm,
    Saved
}

/** Result of pressing OK / reaching the auto-submit boundary. */
internal enum class DpadSetupOutcome {
    /** Not enough presses yet — the screen ignores this. */
    NotReady,
    /** Create step accepted the sequence; now re-enter it (confirm step). */
    AdvancedToConfirm,
    /** Confirm step matched the created sequence; the screen persists it. */
    Saved,
    /** Confirm step mismatched; flow is back at the create step, cleared. */
    Mismatch
}

/**
 * Pure state machine for the D-pad combination setup flow
 * (docs/specs/006-profile-guard-features.md, T2). Screen-agnostic so the
 * bounds, auto-submit and create/confirm/mismatch rules stay unit-testable.
 *
 * Capture rules: create accepts [DpadCombo.MIN_LENGTH]..[DpadCombo.MAX_LENGTH]
 * presses via OK (auto-submits at the max); confirm expects exactly the created
 * length and auto-submits when reached; a mismatch resets to a cleared create
 * step. The raw sequence lives in memory only until [DpadSetupOutcome.Saved],
 * at which point the screen persists it via the salted-hash store.
 *
 * Fields are Compose snapshot state: the screen reads them in composition, so
 * every mutation (including the OK-path step change) must invalidate reads.
 */
internal class DpadSetupFlow(
    private val minLength: Int = DpadCombo.MIN_LENGTH,
    private val maxLength: Int = DpadCombo.MAX_LENGTH
) {
    var step by mutableStateOf(DpadSetupStep.Create)
        private set

    /** Presses recorded in the current step. */
    var entered by mutableStateOf<List<DpadDirection>>(emptyList())
        private set

    /** Sequence accepted at the create step, held in memory for the confirm compare. */
    var draft by mutableStateOf<List<DpadDirection>>(emptyList())
        private set

    /** Length the confirm step auto-submits at (the created sequence's length). */
    val confirmLength: Int?
        get() = if (step == DpadSetupStep.Confirm) draft.size else null

    /**
     * Slots the screen draws (Spec 43 s-create / s-ready): create shows [minLength] from the start and
     * one more past each press beyond it (the next slot), up to [maxLength]; confirm and saved show
     * the created length.
     */
    val slotCount: Int
        get() = when (step) {
            DpadSetupStep.Create -> (entered.size + 1).coerceIn(minLength, maxLength)
            DpadSetupStep.Confirm, DpadSetupStep.Saved -> draft.size
        }

    val isCaptureFull: Boolean
        get() = entered.size >= maxLength

    val canSubmit: Boolean
        get() = when (step) {
            DpadSetupStep.Create -> entered.size in minLength..maxLength
            DpadSetupStep.Confirm -> entered.size == draft.size && draft.isNotEmpty()
            DpadSetupStep.Saved -> false
        }

    fun press(direction: DpadDirection) {
        if (step == DpadSetupStep.Create || step == DpadSetupStep.Confirm) {
            if (entered.size < maxLength) {
                entered = entered + direction
            }
        }
    }

    fun submit(): DpadSetupOutcome {
        if (!canSubmit) return DpadSetupOutcome.NotReady
        return when (step) {
            DpadSetupStep.Create -> {
                draft = entered
                entered = emptyList()
                step = DpadSetupStep.Confirm
                DpadSetupOutcome.AdvancedToConfirm
            }
            DpadSetupStep.Confirm -> {
                if (entered == draft) {
                    step = DpadSetupStep.Saved
                    DpadSetupOutcome.Saved
                } else {
                    reset()
                    DpadSetupOutcome.Mismatch
                }
            }
            DpadSetupStep.Saved -> DpadSetupOutcome.NotReady
        }
    }

    /** Clears everything, including the in-memory draft (cancel or after a mismatch). */
    fun reset() {
        step = DpadSetupStep.Create
        entered = emptyList()
        draft = emptyList()
    }
}
