package com.nuvio.tv.ui.screens.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.profile.DpadDirection
import com.nuvio.tv.domain.model.UserProfile
import com.nuvio.tv.ui.components.DpadCaptureOutcome
import com.nuvio.tv.ui.components.DpadRing
import com.nuvio.tv.ui.components.DpadSlots
import com.nuvio.tv.ui.components.parseProfileColor
import com.nuvio.tv.ui.components.rememberDpadFailShake
import com.nuvio.tv.ui.components.AvatarArt
import com.nuvio.tv.ui.components.ProfileAvatarCircle
import com.nuvio.tv.ui.screens.detail.DetailKeyHint
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.requestFocusAfterFrames
import kotlinx.coroutines.delay

// Interaction holds: the fail hold mirrors the setup flow (1100ms; the mockup's
// own demo uses 900ms); the success hold is a judgment call between the mockup's
// demo beat (~600ms) and the setup flow's 1400ms auto-close. Both values are
// owned by the ON-DEVICE timing pass.
private const val FAIL_RESET_MS = 1100L
private const val SUCCESS_CLOSE_MS = 1000L

/**
 * Pure capture state for the D-pad unlock overlay (T3,
 * docs/specs/006-profile-guard-features.md). Screen-agnostic so the
 * capture rules stay unit-testable: presses stop at the stored combination
 * length (so a sequence shorter than the stored one can never be submitted,
 * and a longer one cannot exist), and every state change is visible to
 * composition via snapshot state.
 */
internal class DpadUnlockCapture(
    val comboLength: Int
) {
    var entered by mutableStateOf<List<DpadDirection>>(emptyList())
        private set

    var outcome by mutableStateOf(DpadCaptureOutcome.Idle)
        private set

    /** True once the stored length is reached — the auto-submit boundary. */
    val isFull: Boolean
        get() = entered.size >= comboLength

    /** No-ops once the capture is full or an outcome is showing. */
    fun press(direction: DpadDirection) {
        if (outcome != DpadCaptureOutcome.Idle || isFull) return
        entered = entered + direction
    }

    /** Verdicts are set by the verify path (T4) and only from Idle. */
    fun markSuccess() {
        if (outcome == DpadCaptureOutcome.Idle) outcome = DpadCaptureOutcome.Success
    }

    fun markFail() {
        if (outcome == DpadCaptureOutcome.Idle) outcome = DpadCaptureOutcome.Fail
    }

    /** Clears the sequence and the outcome — the wrong-combo retry boundary. */
    fun reset() {
        entered = emptyList()
        outcome = DpadCaptureOutcome.Idle
    }
}

/**
 * D-pad combination unlock (Spec 43 stages d-idle / d-press / d-wrong / d-ok, sandbox
 * `u43-profiles-redesign` v4): the PIN screen's shell (wordmark, avatar, title) around count-only
 * [DpadSlots] (one per stored press), the support line and the [DpadRing], whose every press lights
 * the same way for every direction. Replaces the PIN pad on ProfileSelectionScreen when the D-pad
 * unlock is enabled and a combination is set.
 *
 * Nothing on screen takes focus: the root holds it, every D-pad direction is a combination press and
 * OK / Enter is "Use PIN instead" (the key hint at the bottom), run on the key-up of an OK pressed
 * here. The overlay opens from a card click, which fires on key up, so the OK that opened it never
 * reaches here.
 */
@Composable
internal fun DpadUnlockOverlay(
    profile: UserProfile,
    avatarArt: AvatarArt?,
    capture: DpadUnlockCapture,
    onDismiss: () -> Unit,
    onUsePinInstead: () -> Unit,
    onComboEntered: (List<DpadDirection>) -> Unit,
    onUnlocked: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = NuvioTheme.marquee.profiles
    val rootFocusRequester = remember { FocusRequester() }
    val accent = remember(profile.avatarColorHex) { parseProfileColor(profile.avatarColorHex) }
    val shakeX = rememberDpadFailShake(capture.outcome)
    // Set by an OK key-down on this screen; its key-up switches to the PIN pad.
    var okArmed by remember { mutableStateOf(false) }

    // A wrong combination keeps the slots filled and red through the shake, then resets;
    // a success holds the green state before the unlock path runs.
    LaunchedEffect(capture.outcome) {
        when (capture.outcome) {
            DpadCaptureOutcome.Fail -> {
                delay(FAIL_RESET_MS)
                capture.reset()
            }
            DpadCaptureOutcome.Success -> {
                delay(SUCCESS_CLOSE_MS)
                onUnlocked()
            }
            DpadCaptureOutcome.Idle -> Unit
        }
    }

    LaunchedEffect(Unit) {
        // Frame-anchored, the ProfilePinOverlay pattern (delay guesses drift).
        rootFocusRequester.requestFocusAfterFrames()
    }

    BackHandler(onBack = onDismiss)

    Box(
        modifier = modifier
            .fillMaxSize()
            .focusRequester(rootFocusRequester)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                val action = dpadUnlockKeyAction(keyEvent.key)
                if (keyEvent.type == KeyEventType.KeyUp) {
                    // Use PIN instead runs on the release of an OK pressed here, so that release
                    // can never land on the PIN pad's focused key and type a digit.
                    if (action == DpadUnlockKeyAction.UsePin && okArmed) {
                        okArmed = false
                        // Not during the success hold: leaving would cancel the unlock about to run.
                        if (capture.outcome != DpadCaptureOutcome.Success) onUsePinInstead()
                    }
                    return@onPreviewKeyEvent action != null
                }
                if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // One physical press = one combination press: a held button's
                // auto-repeat must not fill the capture (setup-flow filter).
                if (keyEvent.nativeKeyEvent.repeatCount > 0) return@onPreviewKeyEvent true
                when (action) {
                    is DpadUnlockKeyAction.Press -> {
                        val wasFull = capture.isFull
                        capture.press(action.direction)
                        // Auto-submit exactly when this press reached the stored length.
                        if (!wasFull && capture.isFull) onComboEntered(capture.entered)
                        true
                    }
                    DpadUnlockKeyAction.UsePin -> {
                        okArmed = true
                        true
                    }
                    null -> false
                }
            }
    ) {
        ProfileWordmark()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = tokens.pinTop),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ProfileAvatarCircle(
                name = profile.name,
                colorHex = profile.avatarColorHex,
                size = tokens.pinAvatarSize,
                art = avatarArt
            )
            Spacer(modifier = Modifier.height(tokens.pinAvatarToTitle))
            Text(
                text = stringResource(R.string.dpad_unlock_title, profile.name),
                style = NuvioTheme.textStyles.pinTitle,
                color = NuvioPrimitives.white,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(tokens.pinTitleToBoxes))
            DpadSlots(
                filledCount = capture.entered.size,
                slotCount = capture.comboLength,
                outcome = capture.outcome,
                modifier = Modifier.graphicsLayer { translationX = shakeX.value }
            )
            Spacer(modifier = Modifier.height(tokens.pinBoxesToSupport))
            Text(
                text = stringResource(
                    when (capture.outcome) {
                        DpadCaptureOutcome.Idle -> R.string.dpad_unlock_support
                        DpadCaptureOutcome.Fail -> R.string.dpad_unlock_wrong
                        DpadCaptureOutcome.Success -> R.string.dpad_unlock_unlocked
                    }
                ),
                style = NuvioTheme.textStyles.pinSupport,
                color = when (capture.outcome) {
                    DpadCaptureOutcome.Idle -> NuvioTheme.marquee.onImage2
                    DpadCaptureOutcome.Fail -> tokens.pinErrorText
                    DpadCaptureOutcome.Success -> tokens.successText
                },
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(tokens.dpadSupportToRing))
            DpadRing(
                pressTick = capture.entered.size,
                outcome = capture.outcome,
                centreLabel = stringResource(R.string.dpad_ring_key_pin),
                accent = accent,
                modifier = Modifier.graphicsLayer { translationX = shakeX.value }
            )
            Spacer(modifier = Modifier.height(tokens.dpadRingToFooter))
            DetailKeyHint(
                key = stringResource(R.string.profile_hint_key_ok),
                label = stringResource(R.string.dpad_unlock_hint)
            )
        }
    }
}

/** What a key does on the unlock screen: an arrow is a combination press, OK / Enter switches to the PIN pad. */
internal sealed interface DpadUnlockKeyAction {
    data class Press(val direction: DpadDirection) : DpadUnlockKeyAction
    data object UsePin : DpadUnlockKeyAction
}

/** Spec 43 P2: routes [key] on the unlock screen; null = not ours (Back goes to the BackHandler). */
internal fun dpadUnlockKeyAction(key: Key): DpadUnlockKeyAction? = when (key) {
    Key.DirectionUp -> DpadUnlockKeyAction.Press(DpadDirection.UP)
    Key.DirectionDown -> DpadUnlockKeyAction.Press(DpadDirection.DOWN)
    Key.DirectionLeft -> DpadUnlockKeyAction.Press(DpadDirection.LEFT)
    Key.DirectionRight -> DpadUnlockKeyAction.Press(DpadDirection.RIGHT)
    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> DpadUnlockKeyAction.UsePin
    else -> null
}
