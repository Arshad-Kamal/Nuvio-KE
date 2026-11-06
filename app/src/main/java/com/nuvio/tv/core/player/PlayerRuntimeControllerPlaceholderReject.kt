package com.nuvio.tv.core.player

import com.nuvio.tv.core.util.safeHost
import android.util.Log
import com.nuvio.tv.R
import kotlinx.coroutines.flow.update

/**
 * 5c: act on a placeholder verdict from [PlaceholderStreamPolicy].
 *
 * 5b probed and only logged; this stops the stream the policy rejected. Two entry
 * points share it: the byte floor at STATE_READY (content-length is stable there)
 * and the duration backstop on the progress tick (decoded duration is only
 * trustworthy once playback is under way, so a provisional READY duration cannot
 * cause a false reject). The policy's runtime guard is the fail-safe -- with no
 * >= 20 min runtime it returns Accept, so this is never reached for a real stream
 * or a no-runtime transition.
 *
 * Spec 21 P3 (D3): the next live source is tried first, through the dead-source
 * failover (same cap, Toast and loading line). Only when none is left does it halt
 * with [error].
 *
 * [PlayerRuntimeController.placeholderRejectedForStream] (set first, reset when the
 * next stream starts) is what keeps the placeholder out of the title's watch state:
 * no progress save (saveWatchProgress / saveWatchProgressIfNeeded, including the
 * save stop() triggers), no live-position resume carry into the next source, no
 * second backstop reject. The progress tick ends after a reject, so post-play and
 * next-episode arming never see the stopped placeholder while an advance waits for
 * the source list (no error is shown then). A non-blank [error] additionally stops
 * natural-end handling once the halt path is taken.
 *
 * Upstream: NuvioMedia/NuvioTV. Licensed under GPL-3.0.
 */
internal fun PlayerRuntimeController.rejectPlaceholderStream(
    verdict: PlaceholderStreamPolicy.Verdict.Reject
) {
    Log.w(
        PlayerRuntimeController.TAG,
        "PLACEHOLDER_REJECT reason=${verdict.reason} detail=${verdict.detail} " +
            "host=${currentStreamUrl.safeHost()}"
    )
    placeholderRejectedForStream = true
    // A stopped placeholder renders nothing: a watchdog left armed would show its own
    // error over a pending advance.
    cancelStartupWatchdog()
    cancelFirstFrameWatchdog()
    cancelStallWatchdog()
    runCatching { _exoPlayer?.stop() }
    cancelNextEpisodeAutoPlayOnFatalError()
    val message = context.getString(R.string.player_error_placeholder_stream)
    if (advanceToNextLiveSource(message)) return
    _uiState.update {
        it.copy(
            error = message,
            showLoadingOverlay = false
        )
    }
}
