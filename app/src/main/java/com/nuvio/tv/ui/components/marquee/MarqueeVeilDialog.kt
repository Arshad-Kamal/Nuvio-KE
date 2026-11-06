package com.nuvio.tv.ui.components.marquee

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.nuvio.tv.ui.util.requestFocusAfterFrames

/**
 * Marquee overlay in its own Dialog window, so the [veil] dims the whole screen (top bar and
 * side panels included). The veil is the whole dim: the platform dim is switched off. Back calls
 * [onDismissRequest]. [content] fills the screen; it positions or aligns its own panel.
 * [initialFocus], when given, takes focus once the window is up, and again whenever it changes.
 */
@Composable
internal fun MarqueeVeilDialog(
    onDismissRequest: () -> Unit,
    veil: Color,
    initialFocus: FocusRequester? = null,
    content: @Composable BoxScope.() -> Unit
) {
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        if (initialFocus != null) {
            LaunchedEffect(initialFocus) { initialFocus.requestFocusAfterFrames() }
        }
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { dialogWindow?.setDimAmount(0f) }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(veil),
            content = content
        )
    }
}
