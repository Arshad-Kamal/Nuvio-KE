package com.nuvio.tv.ui.util

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.withFrameNanos

/**
 * Requests focus on this [FocusRequester] after waiting `frames` composition
 * frames, retrying up to 4 times (one frame between attempts).
 *
 * The single home for the frame-anchored focus-restore idiom (G6, 2026-08-30):
 * use it instead of a hand-rolled `repeat(N) { withFrameNanos { } }` +
 * `requestFocus()`. The retry covers a target that composes a frame late.
 */
suspend fun FocusRequester.requestFocusAfterFrames(frames: Int = 2): Boolean {
    repeat(frames.coerceAtLeast(0)) {
        withFrameNanos { }
    }
    repeat(4) { attempt ->
        val requested = runCatching { requestFocus() }.getOrDefault(false)
        if (requested) return true
        if (attempt < 3) {
            withFrameNanos { }
        }
    }
    return false
}
