package com.nuvio.tv.ui.util

import android.view.KeyEvent
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import androidx.compose.ui.input.key.NativeKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent

/**
 * True when [native] is the ACTION_DOWN half of the TV remote's BACK press.
 * The W5.2 contract: consume BACK at ACTION_DOWN and act immediately (the
 * in-repo reference shape — ProfileSelectionScreen's PIN overlay).
 */
fun isTvBackActionDown(native: NativeKeyEvent): Boolean =
    native.action == KeyEvent.ACTION_DOWN && native.keyCode == KeyEvent.KEYCODE_BACK

/**
 * W5.2 single home for the TV text-field BACK contract: while [enabled],
 * consume BACK at ACTION_DOWN and run [onBack] (leave the field — exit edit
 * mode / hide keyboard / return focus to the surface). The key stays consumed
 * for the press; when [enabled] is false the event propagates untouched.
 *
 * Callers must move focus off the field in [onBack] when the field has no
 * edit state to exit (always-editable fields hide the keyboard and clear
 * focus), otherwise BACK stays captured by the field.
 */
fun Modifier.onTvBackDown(
    enabled: Boolean = true,
    onBack: () -> Unit
): Modifier = onPreviewKeyEvent { event: ComposeKeyEvent ->
    if (enabled && isTvBackActionDown(event.nativeKeyEvent)) {
        onBack()
        true
    } else {
        false
    }
}
