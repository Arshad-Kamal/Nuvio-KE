package com.nuvio.tv.ui.components.marquee

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import com.nuvio.tv.ui.util.LongPressKeyTracker
import com.nuvio.tv.ui.util.isSelectKey
import com.nuvio.tv.ui.util.isSelectOrMenuKey
import com.nuvio.tv.ui.util.rememberLongPressKeyTracker

/**
 * Long-press for Marquee clickables (cards, buttons): a held select key or MENU fires the
 * long-press action and the release that follows never clicks. Put [modifier] on the clickable
 * and route its onClick through [click].
 */
@Stable
internal class MarqueeLongPress(private val tracker: LongPressKeyTracker) {
    // Read only from key/click callbacks, never during composition: no snapshot state needed.
    private var triggered = false

    fun click(onClick: () -> Unit) {
        if (triggered) triggered = false else onClick()
    }

    fun modifier(onLongPress: (() -> Unit)?): Modifier {
        if (onLongPress == null) return Modifier
        // A long press that moves focus (a panel or dialog opens) never sees its release here, so
        // the flag would swallow the next short press after focus returns. A dialog window takes
        // the release without moving this window's focus, so a fresh press clears the flag too.
        return Modifier.onFocusChanged { if (!it.isFocused) triggered = false }.onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            if (native.action == AndroidKeyEvent.ACTION_DOWN && native.repeatCount == 0 && isSelectOrMenuKey(native.keyCode)) {
                triggered = false
            }
            if (native.action == AndroidKeyEvent.ACTION_DOWN && native.keyCode == AndroidKeyEvent.KEYCODE_MENU) {
                triggered = true
                onLongPress()
                return@onPreviewKeyEvent true
            }
            if (tracker.handle(native, ::isSelectKey) {
                    triggered = true
                    onLongPress()
                }
            ) {
                if (native.action == AndroidKeyEvent.ACTION_UP) triggered = false
                return@onPreviewKeyEvent true
            }
            if (native.action == AndroidKeyEvent.ACTION_UP && triggered && isSelectOrMenuKey(native.keyCode)) {
                triggered = false
                return@onPreviewKeyEvent true
            }
            false
        }
    }
}

@Composable
internal fun rememberMarqueeLongPress(): MarqueeLongPress {
    val tracker = rememberLongPressKeyTracker()
    return remember(tracker) { MarqueeLongPress(tracker) }
}
