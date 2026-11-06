package com.nuvio.tv.ui.components.marquee

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioMotion
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

/** What a [MarqueeToastHost] shows: [show] replaces the current message and restarts its time. */
@Stable
class MarqueeToastState {
    /** The last message shown; it stays set while the toast fades out. */
    var message by mutableStateOf("")
        private set
    internal val transition = MutableTransitionState(false)
    internal var shownCount by mutableIntStateOf(0)
        private set
    /** This message's time on screen; null = the toast tokens' duration. */
    internal var durationMs: Long? = null
        private set
    /** The last message is a success (green check and edge, Spec 52 `.toast.ok`). */
    internal var success by mutableStateOf(false)
        private set

    /** True from [show] until the fade-out has finished. */
    val onScreen: Boolean
        get() = transition.targetState || transition.currentState

    fun show(message: String, durationMs: Long? = null, success: Boolean = false) {
        this.message = message
        this.durationMs = durationMs
        this.success = success
        transition.targetState = true
        shownCount++
    }

    /** Hides the toast unless another [show] came after [shown]. */
    internal fun hide(shown: Int) {
        if (shownCount == shown) transition.targetState = false
    }
}

@Composable
fun rememberMarqueeToastState(): MarqueeToastState = remember { MarqueeToastState() }

/** The app-wide toast MainActivity hosts over the top-bar scaffold; null outside it. */
val LocalMarqueeToast = staticCompositionLocalOf<MarqueeToastState?> { null }

/**
 * `.l10toast`: the message in a pill, faded in and out, gone after the toast tokens' duration
 * (or the one its [MarqueeToastState.show] asked for); a success message leads with a green check
 * and a green edge (`.toast.ok`).
 * Place it where it should sit (callers align it bottom-centre over their screen); it never
 * takes focus.
 */
@Composable
fun MarqueeToastHost(state: MarqueeToastState, modifier: Modifier = Modifier) {
    val tokens = NuvioTheme.marquee.toast
    LaunchedEffect(state.shownCount) {
        val shown = state.shownCount
        if (shown == 0) return@LaunchedEffect
        delay(state.durationMs ?: tokens.durationMs)
        state.hide(shown)
    }
    AnimatedVisibility(
        visibleState = state.transition,
        enter = fadeIn(tween(NuvioMotion.tokens.durations.fast)),
        exit = fadeOut(tween(NuvioMotion.tokens.durations.fast)),
        modifier = modifier
    ) {
        val success = state.success
        Row(
            modifier = Modifier
                .padding(bottom = tokens.bottom)
                .background(tokens.fill, CircleShape)
                .border(NuvioTheme.spacing.hairline, if (success) tokens.successEdge else tokens.edge, CircleShape)
                .then(
                    if (success) {
                        Modifier.padding(
                            start = tokens.successPaddingStart,
                            end = tokens.successPaddingEnd,
                            top = tokens.successPaddingVertical,
                            bottom = tokens.successPaddingVertical
                        )
                    } else {
                        Modifier.padding(horizontal = tokens.paddingHorizontal, vertical = tokens.paddingVertical)
                    }
                ),
            horizontalArrangement = Arrangement.spacedBy(tokens.successIconGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (success) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = tokens.successIcon,
                    modifier = Modifier.size(tokens.successIconSize)
                )
            }
            Text(
                text = state.message,
                style = if (success) NuvioTheme.textStyles.meta else NuvioTheme.textStyles.cardLabel,
                color = NuvioPrimitives.white
            )
        }
    }
}
