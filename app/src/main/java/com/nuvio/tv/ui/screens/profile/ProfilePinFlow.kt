package com.nuvio.tv.ui.screens.profile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.UserProfile

/** What a PIN screen is for; [Set] is a new PIN (create + confirm), the rest check the current one. */
internal sealed interface ProfilePinOverlayState {
    val profile: UserProfile

    data class Unlock(override val profile: UserProfile) : ProfilePinOverlayState
    /** Startup picker, hold on a locked profile: its PIN before the options panel (no D-pad combination). */
    data class UnlockForOptions(override val profile: UserProfile) : ProfilePinOverlayState
    /** [currentPin]: already verified (Change PIN), sent with the new one. */
    data class Set(override val profile: UserProfile, val currentPin: String? = null) : ProfilePinOverlayState
    data class VerifyCurrentForChange(override val profile: UserProfile) : ProfilePinOverlayState
    data class VerifyCurrentForRemove(override val profile: UserProfile) : ProfilePinOverlayState
    data class VerifyCurrentForDelete(override val profile: UserProfile) : ProfilePinOverlayState
}

/** Step of a new-PIN entry: type it, then type it again. */
internal enum class ProfilePinEntryStage {
    Create,
    Confirm
}

/** What one edit of the entered PIN led to. */
internal sealed interface PinEntryOutcome {
    /** Still typing (or the edit was ignored). */
    data object Typing : PinEntryOutcome
    /** New-PIN entry: the first [ProfilePinFlow.length] digits were taken; type them again. */
    data object AdvancedToConfirm : PinEntryOutcome
    /** New-PIN entry: the second entry differed; back at [ProfilePinEntryStage.Create], cleared. */
    data object Mismatch : PinEntryOutcome
    /** A full PIN to send: the entered one, or the confirmed new one. The entry is cleared. */
    data class Submit(val pin: String) : PinEntryOutcome
}

/**
 * Entry state of one PIN overlay (u10 stages 3, 4, 9): digits, backspace and clear, auto-submit
 * at [length] digits, and for a new PIN ([confirmNew]) create -> confirm -> mismatch. Server
 * results and lockouts are not its concern (`PinLockouts`); the screen ignores input while a
 * request runs or a lockout holds. Fields are Compose snapshot state, read in composition; one instance per overlay
 * state, so a new state starts clean. The Settings combination gate uses it single-entry too.
 */
internal class ProfilePinFlow(
    private val confirmNew: Boolean,
    val length: Int = ProfileManager.PIN_LENGTH
) {
    var pin by mutableStateOf("")
        private set

    var stage by mutableStateOf(ProfilePinEntryStage.Create)
        private set

    /**
     * First entry of a new PIN, in memory only. Kept after a confirm submit, so a save that fails
     * (the screen then only [clear]s) is retried by typing the confirm again.
     */
    private var draft: String? = null

    fun type(digit: Char): PinEntryOutcome {
        if (!digit.isDigit() || pin.length >= length) return PinEntryOutcome.Typing
        return setText(pin + digit)
    }

    fun backspace(): PinEntryOutcome {
        pin = pin.dropLast(1)
        return PinEntryOutcome.Typing
    }

    fun clear() {
        pin = ""
    }

    /** Replaces the entry (non-digits dropped, cut at [length]) and auto-submits when full; [type] goes through it. */
    fun setText(value: String): PinEntryOutcome {
        pin = value.filter(Char::isDigit).take(length)
        if (pin.length < length) return PinEntryOutcome.Typing
        val entered = pin
        pin = ""
        if (!confirmNew) return PinEntryOutcome.Submit(entered)
        return when {
            stage == ProfilePinEntryStage.Create -> {
                draft = entered
                stage = ProfilePinEntryStage.Confirm
                PinEntryOutcome.AdvancedToConfirm
            }
            entered == draft -> PinEntryOutcome.Submit(entered)
            else -> {
                draft = null
                stage = ProfilePinEntryStage.Create
                PinEntryOutcome.Mismatch
            }
        }
    }
}
