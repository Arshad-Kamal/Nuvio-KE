package com.nuvio.tv.ui.screens.settings

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.profile.DpadDirection
import com.nuvio.tv.core.profile.PinLockouts
import com.nuvio.tv.core.profile.PinVerifyOutcome
import com.nuvio.tv.ui.components.DpadCaptureOutcome
import com.nuvio.tv.ui.components.DpadRing
import com.nuvio.tv.ui.components.DpadSlots
import com.nuvio.tv.ui.components.rememberDpadFailShake
import com.nuvio.tv.ui.screens.detail.DetailKeyHint
import com.nuvio.tv.ui.screens.home.MarqueeBreadcrumb
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.components.PinKeypad
import com.nuvio.tv.ui.screens.profile.PinEntryOutcome
import com.nuvio.tv.ui.screens.profile.ProfilePinFlow
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import com.nuvio.tv.ui.util.requestFocusAfterFrames

/** Route modes for the setup screen. */
internal object DpadComboSetupMode {
    const val SETUP = "setup"
    const val REMOVE = "remove"
}

// Interaction holds named by the original setup-flow mockup (2026-08-30), kept by Spec 43.
private const val MISMATCH_RESET_MS = 1100L
private const val SAVED_AUTO_CLOSE_MS = 1400L

/** Idle colour of the primary-PIN gate's entry dots (DpadPinGate). */
private val IdleDpadDot = Color(0xFF3A3A3A)

/**
 * D-pad combination setup (T2 flow, docs/specs/006-profile-guard-features.md; capture screen
 * restyled by Spec 43, sandbox `u43-profiles-redesign` v4). Phases: PIN gate
 * (only when changing/removing an existing combination and the primary profile
 * has a PIN) -> create -> confirm. Every D-pad press produces identical visual
 * feedback; nothing on screen encodes direction.
 */
@Composable
fun DpadComboSetupScreen(
    mode: String,
    onBackPress: () -> Unit,
    viewModel: DpadUnlockSettingsViewModel = hiltViewModel()
) {
    // The gate decision waits for real store values: gateState is null until the
    // DataStore has emitted, so the change/remove PIN gate cannot latch open
    // from placeholders on a fresh navigation.
    val gateStateRaw by viewModel.gateState.collectAsStateWithLifecycle()
    if (gateStateRaw == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(NuvioTheme.colors.Background)
        )
        return
    }
    val gateState = requireNotNull(gateStateRaw)

    // Snapshot taken at first (loaded) composition — a removal completing while
    // this screen is open must not re-open the gate it already passed.
    val comboExistsAtEntry = remember { gateState.comboSet }

    // The gate protects an existing combination; first-time create skips it.
    val needsGate = comboExistsAtEntry || mode == DpadComboSetupMode.REMOVE
    var gatePassed by remember { mutableStateOf(false) }

    if (needsGate && gateState.primaryHasPin && !gatePassed) {
        DpadPinGate(
            isRemoveFlow = mode == DpadComboSetupMode.REMOVE,
            onVerified = { gatePassed = true },
            onBackPress = onBackPress,
            viewModel = viewModel
        )
        return
    }

    if (mode == DpadComboSetupMode.REMOVE) {
        LaunchedEffect(Unit) {
            viewModel.removeCombo()
            onBackPress()
        }
        return
    }

    DpadCaptureContent(
        onSaved = { sequence -> viewModel.saveCombo(sequence) },
        onBackPress = onBackPress
    )
}

/**
 * Create / confirm capture (Spec 43 stages s-create, s-ready, s-mismatch, s-saved, sandbox
 * `u43-profiles-redesign` v4): breadcrumb, a D-pad glyph, title, count-only [DpadSlots] (six from
 * the start, one more per press up to twelve; the confirm step shows the first entry's count), the
 * support line, the [DpadRing] (centre key OK) and the "OK · Save combination" hint, dimmed until
 * OK would submit. Nothing takes focus but the root, which routes every key.
 */
@Composable
private fun DpadCaptureContent(
    onSaved: (List<DpadDirection>) -> Unit,
    onBackPress: () -> Unit
) {
    val tokens = NuvioTheme.marquee.profiles
    val flow = remember { DpadSetupFlow() }
    var pressTick by remember { mutableIntStateOf(0) }
    var outcome by remember { mutableStateOf(DpadCaptureOutcome.Idle) }
    var saved by remember { mutableStateOf(false) }
    // Entered-count snapshot for the fail state: submit() resets the flow synchronously, but the
    // slots stay filled and red through the shake and only clear at the 1100ms boundary.
    var failDotCount by remember { mutableIntStateOf(0) }
    val focusRequester = remember { FocusRequester() }
    val shakeX = rememberDpadFailShake(outcome)

    fun submitNow() {
        val countAtSubmit = flow.entered.size
        when (flow.submit()) {
            DpadSetupOutcome.AdvancedToConfirm -> Unit
            DpadSetupOutcome.Saved -> {
                outcome = DpadCaptureOutcome.Success
                saved = true
                onSaved(flow.draft)
            }
            DpadSetupOutcome.Mismatch -> {
                failDotCount = countAtSubmit
                outcome = DpadCaptureOutcome.Fail
            }
            DpadSetupOutcome.NotReady -> Unit
        }
    }

    fun press(direction: DpadDirection) {
        if (saved) return
        if (outcome == DpadCaptureOutcome.Fail) return
        flow.press(direction)
        pressTick++
        val autoSubmit = flow.isCaptureFull ||
            (flow.step == DpadSetupStep.Confirm && flow.confirmLength == flow.entered.size)
        if (autoSubmit) {
            submitNow()
        }
    }

    fun confirmOk() {
        if (outcome == DpadCaptureOutcome.Fail || saved) return
        if (flow.canSubmit) submitNow()
    }

    // A mismatch resets to a cleared create step after the fail state lands.
    LaunchedEffect(outcome) {
        if (outcome == DpadCaptureOutcome.Fail) {
            delay(MISMATCH_RESET_MS)
            outcome = DpadCaptureOutcome.Idle
        }
    }

    // Auto-close shortly after the slots and ring turn green.
    LaunchedEffect(saved) {
        if (saved) {
            delay(SAVED_AUTO_CLOSE_MS)
            onBackPress()
        }
    }

    LaunchedEffect(Unit) {
        // Frame-anchored, the ProfilePinOverlay pattern (delay guesses drift).
        focusRequester.requestFocusAfterFrames()
    }

    BackHandler(onBack = onBackPress)

    val failed = outcome == DpadCaptureOutcome.Fail
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        NuvioTheme.colors.BackgroundCard.copy(alpha = 0.35f),
                        NuvioTheme.colors.Background,
                        Color(0xFF070707)
                    ),
                    radius = 900f
                )
            )
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // One physical press = one combination press: a held button's
                // auto-repeat must not fill the capture (DpadFastScrollModifier
                // filters the same way).
                if (keyEvent.nativeKeyEvent.repeatCount > 0) return@onPreviewKeyEvent true
                val direction = when (keyEvent.key) {
                    Key.DirectionUp -> DpadDirection.UP
                    Key.DirectionDown -> DpadDirection.DOWN
                    Key.DirectionLeft -> DpadDirection.LEFT
                    Key.DirectionRight -> DpadDirection.RIGHT
                    else -> null
                }
                when {
                    direction != null -> {
                        press(direction)
                        true
                    }
                    (keyEvent.key == Key.DirectionCenter || keyEvent.key == Key.Enter) -> {
                        confirmOk()
                        true
                    }
                    else -> false
                }
            }
    ) {
        MarqueeBreadcrumb(
            listOf(
                stringResource(R.string.nav_settings),
                stringResource(R.string.settings_profiles),
                stringResource(R.string.settings_dpad_combo_row)
            )
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = tokens.pinTop),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(tokens.pinAvatarSize)
                    .background(tokens.dpadRingCentreFill, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Gamepad,
                    contentDescription = null,
                    tint = NuvioTheme.marquee.onImage2,
                    modifier = Modifier.size(tokens.dpadGlyphIconSize)
                )
            }
            Spacer(modifier = Modifier.height(tokens.pinAvatarToTitle))
            Text(
                text = stringResource(
                    if (saved || flow.step == DpadSetupStep.Confirm) R.string.dpad_setup_confirm_title
                    else R.string.dpad_setup_create_title
                ),
                style = NuvioTheme.textStyles.pinTitle,
                color = NuvioPrimitives.white,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(tokens.pinTitleToBoxes))
            DpadSlots(
                filledCount = if (failed) failDotCount else flow.entered.size,
                slotCount = if (failed) failDotCount else flow.slotCount,
                outcome = outcome,
                modifier = Modifier.graphicsLayer { translationX = shakeX.value }
            )
            Spacer(modifier = Modifier.height(tokens.pinBoxesToSupport))
            Text(
                text = stringResource(
                    when {
                        saved -> R.string.dpad_setup_matched
                        failed -> R.string.dpad_setup_no_match
                        flow.step == DpadSetupStep.Confirm -> R.string.dpad_setup_confirm_subtitle
                        else -> R.string.dpad_setup_create_subtitle
                    }
                ),
                style = NuvioTheme.textStyles.pinSupport,
                color = when {
                    saved -> tokens.successText
                    failed -> tokens.pinErrorText
                    else -> NuvioTheme.marquee.onImage2
                },
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(tokens.dpadSupportToRing))
            DpadRing(
                pressTick = pressTick,
                outcome = outcome,
                centreLabel = stringResource(R.string.profile_hint_key_ok),
                accent = NuvioPrimitives.white,
                modifier = Modifier.graphicsLayer { translationX = shakeX.value }
            )
            Spacer(modifier = Modifier.height(tokens.dpadRingToFooter))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val ready = flow.canSubmit && !saved && !failed
                DetailKeyHint(
                    key = stringResource(R.string.profile_hint_key_ok),
                    label = stringResource(R.string.dpad_setup_save),
                    modifier = Modifier.alpha(if (ready) 1f else tokens.keyDisabledAlpha)
                )
                Text(
                    text = "  ·  " + stringResource(R.string.profile_pin_back_to_cancel),
                    style = NuvioTheme.textStyles.cardLabel,
                    color = NuvioTheme.marquee.onImage2,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * Primary-PIN gate before changing or removing the combination: the shared [PinKeypad] (focus on
 * 5), dots for typed digits, auto-verify on the 4th. A server lockout counts down on the error
 * line and dims the keypad until 0 (shared with the profile picker via `PinLockouts`).
 */
@Composable
private fun DpadPinGate(
    isRemoveFlow: Boolean,
    onVerified: () -> Unit,
    onBackPress: () -> Unit,
    viewModel: DpadUnlockSettingsViewModel
) {
    val pinEntry = remember { ProfilePinFlow(confirmNew = false) }
    var failed by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    val lockedUntil by viewModel.primaryPinLockedUntil.collectAsStateWithLifecycle()
    // Seeded in composition so a locked gate never shows a frame unlocked.
    var lockedSeconds by remember(lockedUntil) {
        mutableIntStateOf(PinLockouts.secondsLeft(lockedUntil, SystemClock.elapsedRealtime()))
    }
    val keypadFocus = remember { FocusRequester() }

    fun pressDigit(digit: Char) {
        if (working || lockedSeconds > 0) return
        failed = false
        val outcome = pinEntry.type(digit)
        if (outcome !is PinEntryOutcome.Submit) return
        working = true
        viewModel.verifyPrimaryPin(outcome.pin) { result ->
            working = false
            when (result) {
                PinVerifyOutcome.Unlocked -> onVerified()
                // The countdown takes the error line.
                is PinVerifyOutcome.LockedOut -> Unit
                PinVerifyOutcome.Wrong, PinVerifyOutcome.Failed -> failed = true
            }
        }
    }

    BackHandler(onBack = onBackPress)

    LaunchedEffect(Unit) {
        // Frame-anchored, the ProfilePinOverlay pattern.
        keypadFocus.requestFocusAfterFrames()
    }

    LaunchedEffect(lockedUntil) {
        // A digit typed in the frame before the lockout arrived goes too.
        if (lockedSeconds > 0) pinEntry.clear()
        while (lockedSeconds > 0) {
            delay(250)
            lockedSeconds = PinLockouts.secondsLeft(lockedUntil, SystemClock.elapsedRealtime())
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.dpad_setup_pin_title),
            color = NuvioTheme.colors.TextPrimary,
            fontSize = 32.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = stringResource(
                if (isRemoveFlow) R.string.dpad_setup_pin_subtitle_remove else R.string.dpad_setup_pin_subtitle_change
            ),
            color = NuvioTheme.colors.TextSecondary,
            fontSize = 18.sp
        )
        Spacer(modifier = Modifier.height(30.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            repeat(pinEntry.length) { index ->
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .background(
                            // The entry clears on submit; the dots stay full while the server checks.
                            if (working || index < pinEntry.pin.length) NuvioTheme.colors.TextPrimary else IdleDpadDot,
                            CircleShape
                        )
                )
            }
        }
        Spacer(modifier = Modifier.height(30.dp))
        PinKeypad(
            enabled = lockedSeconds == 0,
            initialFocus = keypadFocus,
            onDigit = ::pressDigit,
            onBackspace = { if (!working) pinEntry.backspace() }
        )
        Spacer(modifier = Modifier.height(26.dp))
        Text(
            text = when {
                lockedSeconds > 0 -> stringResource(R.string.profile_pin_locked, lockedSeconds)
                failed -> stringResource(R.string.dpad_setup_pin_wrong)
                working -> stringResource(R.string.dpad_setup_pin_verifying)
                else -> " "
            },
            color = NuvioTheme.colors.Error,
            fontSize = 17.sp
        )
    }
}
