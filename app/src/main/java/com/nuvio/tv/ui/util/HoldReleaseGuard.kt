package com.nuvio.tv.ui.util

import android.view.KeyEvent
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.onPreviewKeyEvent

/**
 * Swallows select/menu events until a fresh press (ACTION_DOWN with repeatCount 0) arrives, so
 * the repeats and release of the hold (long OK or Menu) that opened a dialog, panel or keypad do
 * not activate its first focused item. With [enabled] false (opened by a tap) every event passes.
 * [enabled] is read once, when the guard enters composition.
 */
fun Modifier.holdReleaseGuard(enabled: Boolean = true): Modifier = composed {
    val ready = remember { booleanArrayOf(!enabled) }
    onPreviewKeyEvent { event ->
        val native = event.nativeKeyEvent
        val step = holdReleaseStep(ready[0], native.keyCode, native.action, native.repeatCount)
        ready[0] = step.ready
        step.consume
    }
}

/** One key event's outcome: whether the guard is now [ready] and whether to [consume] the event. */
internal data class HoldReleaseStep(val ready: Boolean, val consume: Boolean)

internal fun holdReleaseStep(ready: Boolean, keyCode: Int, action: Int, repeatCount: Int): HoldReleaseStep {
    if (!isSelectOrMenuKey(keyCode)) return HoldReleaseStep(ready, consume = false)
    val nowReady = ready || (action == KeyEvent.ACTION_DOWN && repeatCount == 0)
    return HoldReleaseStep(nowReady, consume = !nowReady)
}
