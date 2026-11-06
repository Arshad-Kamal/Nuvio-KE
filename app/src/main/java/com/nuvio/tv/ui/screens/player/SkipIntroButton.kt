package com.nuvio.tv.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.data.repository.SkipInterval
import com.nuvio.tv.core.player.isSkipIntroButtonVisible
import com.nuvio.tv.core.player.skipIntroAutoHideRemainingMs

/**
 * Skip Intro/Outro/Recap prompt: the OSD's small glass pill (label, then the skip glyph; focused =
 * white fill). Shown while playback is inside a skip interval; the caller places it bottom-left.
 * Auto-hides 10 s after showing, the countdown paused while controls are visible.
 */
@Composable
fun SkipIntroButton(
    interval: SkipInterval?,
    dismissed: Boolean,
    controlsVisible: Boolean,
    suppressFocus: Boolean = false,
    canFocus: Boolean = true,
    onSkip: () -> Unit,
    onUpKey: (() -> Unit)? = null,
    onVisibilityChanged: (Boolean) -> Unit = {},
    onFocused: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null,
    rightFocusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier
) {

    var lastType by remember { mutableStateOf(interval?.type) }
    if (interval != null) lastType = interval.type
    val hasActiveInterval = interval != null
    val shouldShow = hasActiveInterval && (!dismissed || controlsVisible)

    var autoHidden by remember { mutableStateOf(false) }
    var manuallyDismissed by remember { mutableStateOf(false) }
    val internalFocusRequester = remember { FocusRequester() }
    val activeFocusRequester = focusRequester ?: internalFocusRequester
    // Auto-hide clock (0 -> 1 over the 10 s); kept as an Animatable so a pause resumes where it stopped.
    val progress = remember { Animatable(1f) }

    // Reset auto-hide and progress when interval changes
    LaunchedEffect(interval?.startTime, interval?.type) {
        autoHidden = false
        manuallyDismissed = false
        progress.snapTo(0f)
    }

    LaunchedEffect(dismissed) {
        if (!dismissed) {
            if (!manuallyDismissed) {
                autoHidden = false
                progress.snapTo(0f)
            }
        } else {
            manuallyDismissed = true
        }
    }

    // Auto-hide after 10 seconds — pause countdown while controls are visible. Once auto-hidden it
    // re-shows with the controls (isSkipIntroButtonVisible) without restarting the countdown.
    LaunchedEffect(shouldShow, autoHidden, controlsVisible) {
        if (shouldShow && !autoHidden && !controlsVisible) {
            progress.animateTo(1f, animationSpec = tween(
                durationMillis = skipIntroAutoHideRemainingMs(progress.value),
                easing = LinearEasing
            ))
            autoHidden = true
        }
    }

    val isVisible = isSkipIntroButtonVisible(
        hasActiveInterval = hasActiveInterval,
        dismissed = dismissed,
        controlsVisible = controlsVisible,
        autoHidden = autoHidden,
    )

    LaunchedEffect(isVisible) { onVisibilityChanged(isVisible) }

    // Request focus when becoming visible or when controls hide
    // but not when the next episode card has priority, and not when focus is
    // reserved for an overlay (e.g. subtitle selection — #2874).
    LaunchedEffect(isVisible, controlsVisible, suppressFocus, canFocus) {
        if (isVisible && !controlsVisible && !suppressFocus && canFocus) {
            try { activeFocusRequester.requestFocus() } catch (_: Exception) {}
        }
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(tween(300)) + scaleIn(tween(300), initialScale = 0.8f),
        exit = fadeOut(tween(200)) + scaleOut(tween(200), targetScale = 0.8f),
        modifier = modifier
    ) {
        OsdPill(
            label = getSkipLabel(lastType),
            painter = rememberVectorPainter(Icons.Default.FastForward),
            onClick = onSkip,
            trailingIcon = true,
            modifier = Modifier
                .focusRequester(activeFocusRequester)
                .focusProperties {
                    this.canFocus = canFocus
                    downFocusRequester?.let { down = it }
                    upFocusRequester?.let { up = it }
                    rightFocusRequester?.let { right = it }
                }
                .onPreviewKeyEvent { keyEvent ->
                    if (!canFocus) return@onPreviewKeyEvent false
                    if (keyEvent.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) {
                        when (keyEvent.nativeKeyEvent.keyCode) {
                            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                                if (downFocusRequester != null) {
                                    try { downFocusRequester.requestFocus() } catch (_: Exception) {}
                                    true
                                } else {
                                    false
                                }
                            }
                            android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                                // Up goes to upFocusRequester, else the caller's onUpKey decides
                                if (upFocusRequester != null) {
                                    try { upFocusRequester.requestFocus() } catch (_: Exception) {}
                                    true
                                } else if (onUpKey != null) {
                                    onUpKey()
                                    true
                                } else {
                                    false
                                }
                            }
                            else -> false
                        }
                    } else {
                        false
                    }
                }
                .onFocusChanged { if (it.isFocused) onFocused?.invoke() }
        )
    }
}

@Composable
private fun getSkipLabel(type: String?): String = when (type?.trim()?.lowercase()) {
    "op", "opening", "mixed-op", "intro" -> stringResource(R.string.skip_intro)
    "ed", "ending", "mixed-ed", "outro", "credits" -> stringResource(R.string.skip_ending)
    "recap" -> stringResource(R.string.skip_recap)
    else -> stringResource(R.string.skip_generic)
}
