package com.nuvio.tv.core.player

import android.util.Log
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer

/**
 * 5b/5c: evaluate the placeholder gate once per stream at STATE_READY and
 * log its inputs and verdict (PLACEHOLDER_PROBE).
 *
 * PlaceholderStreamPolicy needs an expected runtime, and runtime is only present
 * when the stream screen resolved one before the press. On the direct-autoplay
 * path the overlay may hand off before loadMetadataIfNeeded completes, which would
 * leave the policy inert on exactly the flow that matters most -- silently, since
 * a null runtime means "do not judge". The log line shows whether the runtime
 * arrived on each entry path (direct autoplay, cached link reuse, manual pick,
 * binge prefetch).
 *
 * The verdict is returned and acted on: the STATE_READY caller rejects an
 * ImplausibleSize verdict via rejectPlaceholderStream; ImplausibleDuration is
 * left to the progress-tick backstop, where the decoded duration is trustworthy.
 *
 * Upstream: NuvioMedia/NuvioTV. Licensed under GPL-3.0.
 */
internal fun PlayerRuntimeController.probePlaceholderStream(player: ExoPlayer): PlaceholderStreamPolicy.Verdict {
    if (placeholderProbeDone) return PlaceholderStreamPolicy.Verdict.Accept
    placeholderProbeDone = true

    val contentLengthBytes = PlaybackByteCounter.mediaFileSizeFor(
        url = currentStreamUrl,
        isAdaptiveStream = player.currentManifest != null
    )
    val durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
    val expectedRuntimeMs = expectedRuntimeMinutes?.let { it * 60_000L }

    val verdict = PlaceholderStreamPolicy.evaluate(
        contentLengthBytes = contentLengthBytes,
        durationMs = durationMs,
        expectedRuntimeMs = expectedRuntimeMs
    )

    val outcome = when (verdict) {
        is PlaceholderStreamPolicy.Verdict.Accept ->
            if (expectedRuntimeMs == null) "ACCEPT(no-runtime: gate inert)" else "ACCEPT"
        is PlaceholderStreamPolicy.Verdict.Reject ->
            "REJECT(${verdict.reason}: ${verdict.detail})"
    }

    Log.i(
        PlayerRuntimeController.TAG,
        "PLACEHOLDER_PROBE verdict=$outcome " +
            "contentLength=${contentLengthBytes ?: -1} " +
            "durationMs=${durationMs ?: -1} " +
            "expectedRuntimeMin=${expectedRuntimeMinutes ?: -1}"
    )

    return verdict
}
