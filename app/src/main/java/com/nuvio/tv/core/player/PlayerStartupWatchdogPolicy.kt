package com.nuvio.tv.core.player

import com.nuvio.tv.R

/**
 * Startup watchdog decisions (T3, ysosrs 01b6b4bae + 859ecf718): whether a check
 * should extend the watchdog instead of firing, what a fire means given track state,
 * and whether to stop the player after the post-fire grace (Spec 56 R1). Pure logic so
 * the ceiling arithmetic and fire classification stay pinnable.
 */
internal object PlayerStartupWatchdogPolicy {

    /**
     * Extend instead of firing while buffered-ahead keeps growing and this check
     * landed before the hard ceiling; with a 20 s interval and a 60 s ceiling that
     * grants extends at ~20/~40 s and fires at ~60 s. Growth must be strict: a flat
     * buffer is a stall.
     *
     * The boundary deliberately does not require drift-free timing: `delay()` never
     * undershoots, so a check's elapsed is always a few ms past its nominal mark, and
     * an `elapsed + interval <= ceiling` comparison would deny the ~40 s re-arm and
     * cap the grace window at ~40 s. A pathological check delay (main thread stalled
     * for seconds) can land the last extendable check just under the ceiling, so the
     * latest possible fire is the ceiling plus one interval.
     */
    fun shouldExtend(
        elapsedMs: Long,
        bufferedAheadMs: Long,
        lastBufferedAheadMs: Long,
    ): Boolean {
        return bufferedAheadMs > lastBufferedAheadMs &&
            elapsedMs < PlayerRuntimeController.STARTUP_WATCHDOG_CEILING_MS
    }

    /**
     * Spec 56 R1: one interval after a surfaced fire, stop the player only if nothing
     * changed the case: still no first frame (a late frame retracts the error instead,
     * T3), the same player instance (a reinit or release replaced it), and the watchdog's
     * own message still on screen (any other error owns its own player handling).
     */
    fun shouldStopAfterGrace(
        hasRenderedFirstFrame: Boolean,
        samePlayer: Boolean,
        currentError: String?,
        fireMessage: String,
    ): Boolean = !hasRenderedFirstFrame && samePlayer && currentError == fireMessage

    enum class FireReason(val logToken: String) {
        /** onTracksChanged never ran: the container headers never arrived. Source problem. */
        TracksNotRead("tracks_not_read"),

        /** Video group exists but nothing selected: the device rejected the format. */
        VideoTrackUnsupported("video_track_unsupported"),

        /** Tracks read, no video group at all: trackless stream. Source problem. */
        NoVideoTrack("no_video_track"),

        /** Tracks read, video selected, still no frame: the original decoder wedge. */
        DecoderUnresponsive("decoder_unresponsive"),
    }

    data class FireInput(
        val hasScannedTracksOnce: Boolean,
        val streamHasVideoTrack: Boolean,
        val videoTrackSelected: Boolean,
    )

    /**
     * Order matters: checked first, so stale track flags from a previous stream can
     * never masquerade as decoder blame when the new stream's headers never arrived.
     */
    fun fireReason(input: FireInput): FireReason = when {
        !input.hasScannedTracksOnce -> FireReason.TracksNotRead
        input.streamHasVideoTrack && !input.videoTrackSelected -> FireReason.VideoTrackUnsupported
        !input.streamHasVideoTrack -> FireReason.NoVideoTrack
        else -> FireReason.DecoderUnresponsive
    }

    /** Tracks-not-read and no-video-track are both source-facing; same string. */
    fun messageRes(reason: FireReason): Int = when (reason) {
        FireReason.VideoTrackUnsupported -> R.string.player_error_startup_video_track_unsupported
        FireReason.TracksNotRead, FireReason.NoVideoTrack -> R.string.player_error_startup_no_stream_data
        FireReason.DecoderUnresponsive -> R.string.player_error_startup_timeout
    }
}
