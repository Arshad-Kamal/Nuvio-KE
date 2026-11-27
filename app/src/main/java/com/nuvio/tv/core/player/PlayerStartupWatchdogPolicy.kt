package com.nuvio.tv.core.player

import com.nuvio.tv.R
import com.nuvio.tv.core.torrent.TorrServerBinary

/**
 * Startup watchdog decisions (T3, ysosrs 01b6b4bae + 859ecf718): whether a check
 * should extend the watchdog instead of firing, what a fire means given track state,
 * whether a fire tries the next source first (Spec 86 R4) and whether to stop the player
 * after the post-fire grace (Spec 56 R1). Pure logic so the ceiling arithmetic and fire
 * classification stay pinnable.
 */
object PlayerStartupWatchdogPolicy {

    /**
     * Spec 138 D1: a torrent start waits this long from arm with no swarm progress (a dead swarm
     * then fails over), and up to [TORRENT_CEILING_MS] while the swarm shows progress. Same numbers
     * as ysosrs 343b27591 `TorrentMetadataGiveUp` (idle 60 s, active 180 s).
     */
    const val TORRENT_IDLE_LIMIT_MS = 60_000L
    const val TORRENT_CEILING_MS = 180_000L

    /**
     * Spec 138: TorrServer's view of a torrent start at one check. [cachedBytes] is its cache fill
     * (`preloaded_bytes`), [downloadSpeed] bytes/s from peers over the last second.
     */
    data class SwarmSample(val cachedBytes: Long, val downloadSpeed: Long)

    /**
     * Spec 138: the swarm made progress since [last]: the cache grew, or bytes are coming in and the
     * stats moved (a failed TorrServer poll keeps the last state, so an unchanged sample with a
     * speed is a frozen one, not progress). The loopback bytes the player counts are no signal
     * here: TorrServer hands over a piece only once it is complete, so a healthy swarm reads as
     * silence between pieces.
     */
    fun swarmProgressed(sample: SwarmSample, last: SwarmSample?): Boolean =
        swarmDelivered(sample, last) || (sample.downloadSpeed > 0L && sample != last)

    /**
     * Spec 138: the cache grew since [last]: data reached TorrServer for the player. Names the fire
     * ([FireInput.networkBytesGrew]); a speed alone does not, as TorrServer's bounded read-ahead
     * keeps a wedged decoder's swarm busy for a while.
     */
    fun swarmDelivered(sample: SwarmSample, last: SwarmSample?): Boolean =
        last != null && sample.cachedBytes > last.cachedBytes

    /** Spec 138 D4: swarm bytes over the window ([armed]: the first polled sample in the window) for the fire's measured speed. */
    fun swarmBytesSince(sample: SwarmSample, armed: SwarmSample?): Long =
        if (armed == null) 0L else (sample.cachedBytes - armed.cachedBytes).coerceAtLeast(0L)

    /**
     * Spec 138: the [shouldExtend] `swarmProgressed` argument for one check. Null (judged as any
     * start) when this is not a torrent start, or its video format was refused: the swarm says
     * nothing about a format the device cannot play. False with no stats for the stream.
     */
    fun torrentExtendSignal(
        torrentStart: Boolean,
        sample: SwarmSample?,
        last: SwarmSample?,
        fireReason: FireReason,
    ): Boolean? = when {
        !torrentStart || fireReason == FireReason.VideoTrackUnsupported -> null
        sample == null -> false
        else -> swarmProgressed(sample, last)
    }

    /**
     * Spec 138: a window's baseline: the first polled sample. A window armed before TorrServer's first
     * poll answered gets it at a later check, while its `last` stays null, so that sample's speed
     * counts as progress ([swarmProgressed] with no last) instead of reading as a frozen repeat.
     */
    fun swarmBaseline(armed: SwarmSample?, sample: SwarmSample?): SwarmSample? = armed ?: sample

    /**
     * Spec 138 D4: the bytes a fire measures over its window: a torrent's swarm bytes (0 with no
     * stats), else [loopbackBytes] (null = not measured, mpv).
     */
    fun fireWindowBytes(
        torrentStart: Boolean,
        sample: SwarmSample?,
        armed: SwarmSample?,
        loopbackBytes: Long?,
    ): Long? = if (torrentStart) sample?.let { swarmBytesSince(it, armed) } ?: 0L else loopbackBytes

    /** Spec 138: which load errors on a torrent start's URI do not count toward the fatal retry limit. */
    enum class StartupLoadHold {
        NONE,

        /** D2: read timeouts only, while the watchdog judges the start. */
        TIMEOUTS,

        /** F1: every error, while the watchdog's own box is on screen. */
        ALL_ERRORS,
    }

    /**
     * Spec 138: the hold for a load of [host]:[port]. Only a TorrServer loopback load (its port is
     * [torrServerPort], Spec 148) of the stream
     * playing now ([isCurrentStream]) in a torrent start with no first frame is held.
     *
     * D2: while the startup watchdog job judges the start in the foreground ([watchdogJobActive], no
     * error on screen), read timeouts ([StartupLoadHold.TIMEOUTS]: TorrServer answers only once the
     * first piece is in). In the background the watchdog never reaches a verdict, so the hold would
     * keep a paused loader retrying for ever. Another error on screen ends it.
     *
     * F1 (Shield window 2026-11-20): under the watchdog's own box ([errorIsWatchdogBox]), in the
     * background too, every error ([StartupLoadHold.ALL_ERRORS]) while the job runs its grace: an
     * error thrown first (TorrServer's own add-torrent timeout answers HTTP 500 ~15 s after a dead
     * swarm's box) would reach onPlayerError, whose auto-retry clears the box and restarts the
     * torrent. After the grace stop [keepsBoxOnLateError] takes over.
     */
    fun torrentStartLoadHold(
        host: String?,
        port: Int,
        torrServerPort: Int,
        isCurrentStream: Boolean,
        torrentStream: Boolean,
        renderedFirstFrame: Boolean,
        watchdogJobActive: Boolean,
        inBackground: Boolean,
        errorShown: Boolean,
        errorIsWatchdogBox: Boolean,
    ): StartupLoadHold = when {
        !torrentStream || !isCurrentStream || renderedFirstFrame -> StartupLoadHold.NONE
        host != TorrServerBinary.HOST || port != torrServerPort -> StartupLoadHold.NONE
        !watchdogJobActive -> StartupLoadHold.NONE
        errorShown -> if (errorIsWatchdogBox) StartupLoadHold.ALL_ERRORS else StartupLoadHold.NONE
        inBackground -> StartupLoadHold.NONE
        else -> StartupLoadHold.TIMEOUTS
    }

    /**
     * Spec 138 F1: a player error on a torrent start whose box's grace stop ended it ([graceStopped],
     * reset at the next arm) is dropped and the box kept, before the recovery ladder: stop() only
     * posts MSG_STOP, so a loader error thrown first survives the stop, and a later re-prepare without
     * an arm (the subtitle media reload) fails on the normal retry count; either would otherwise reach
     * the auto-retry, which clears the box and restarts the torrent. A first frame retracts the box.
     */
    fun keepsBoxOnLateError(
        torrentStream: Boolean,
        graceStopped: Boolean,
        errorIsWatchdogBox: Boolean,
        renderedFirstFrame: Boolean,
    ): Boolean = torrentStream && graceStopped && errorIsWatchdogBox && !renderedFirstFrame

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
     *
     * [dataArriving] ([waitsForData]) extends on the same terms when the buffer is flat. Both
     * extensions share the ceiling.
     *
     * [swarmProgressed] is non-null for a torrent start (Spec 138 D1): it always extends before
     * [TORRENT_IDLE_LIMIT_MS], and after that while the swarm or the buffer shows progress, up to
     * [TORRENT_CEILING_MS] ([torrentExtendSignal]).
     */
    fun shouldExtend(
        elapsedMs: Long,
        bufferedAheadMs: Long,
        lastBufferedAheadMs: Long,
        dataArriving: Boolean = false,
        swarmProgressed: Boolean? = null,
    ): Boolean {
        val growing = bufferedAheadMs > lastBufferedAheadMs || dataArriving
        if (swarmProgressed == null) {
            return growing && elapsedMs < PlayerRuntimeController.STARTUP_WATCHDOG_CEILING_MS
        }
        return elapsedMs < TORRENT_IDLE_LIMIT_MS ||
            ((growing || swarmProgressed) && elapsedMs < TORRENT_CEILING_MS)
    }

    /**
     * Spec 86 D3: a start whose tracks are read and whose video is selected keeps waiting while
     * network bytes still arrive, even with nothing buffered ahead yet: a link slightly too slow
     * for the file played 4 s after a 20 s fire (Shield window 2026-11-12, S73 b). The same fact
     * names the fire when the ceiling is reached ([FireReason.DataStillArriving]). A link with no
     * data, unread tracks, a refused or missing video track fire as before.
     */
    fun waitsForData(input: FireInput): Boolean = fireReason(input) == FireReason.DataStillArriving

    /**
     * Spec 86 R4/D3: the source-facing fires try the next source before the error box. A refused
     * video format and a decoder that took data and showed nothing stay on the box here; a start
     * READY with sound and no picture is failed over earlier by the first-frame watchdog (R1/D1,
     * [PlayerFirstFrameCodecRecoveryPolicy]).
     */
    fun failsOver(reason: FireReason): Boolean = when (reason) {
        FireReason.TracksNotRead, FireReason.NoVideoTrack, FireReason.DataStillArriving -> true
        FireReason.VideoTrackUnsupported, FireReason.DecoderUnresponsive -> false
    }

    /**
     * Spec 56 R1: one interval after a surfaced fire, stop the player only if nothing
     * changed the case: still no first frame (a late frame retracts the error instead,
     * T3), the same player instance (a reinit or release replaced it), and the watchdog's
     * own failure still on screen (any other error owns its own player handling).
     */
    fun <T : Any> shouldStopAfterGrace(
        hasRenderedFirstFrame: Boolean,
        samePlayer: Boolean,
        currentError: T?,
        fire: T,
    ): Boolean = !hasRenderedFirstFrame && samePlayer && currentError == fire

    enum class FireReason(val logToken: String) {
        /** onTracksChanged never ran: the container headers never arrived. Source problem. */
        TracksNotRead("tracks_not_read"),

        /** Video group exists but nothing selected: the device rejected the format. */
        VideoTrackUnsupported("video_track_unsupported"),

        /** Tracks read, no video group at all: trackless stream. Source problem. */
        NoVideoTrack("no_video_track"),

        /**
         * Spec 86 R9/D3: tracks read, video selected, network bytes still arriving, no frame: the
         * link is too slow for the file (the Shield window's start had nothing buffered at 36 of
         * 38 Mbit/s). Source problem.
         */
        DataStillArriving("data_still_arriving"),

        /**
         * Tracks read, video selected, no frame and no bytes arriving (or unknown, mpv): the
         * original decoder wedge (the Codec2 wedge blocks the playback thread, so loading stops).
         */
        DecoderUnresponsive("decoder_unresponsive"),
    }

    /**
     * [networkBytesGrew]: network bytes arrived since the last check; null = unknown (mpv fetches
     * outside the byte counter).
     */
    data class FireInput(
        val hasScannedTracksOnce: Boolean,
        val streamHasVideoTrack: Boolean,
        val videoTrackSelected: Boolean,
        val networkBytesGrew: Boolean? = null,
    )

    /**
     * Order matters: checked first, so stale track flags from a previous stream can
     * never masquerade as decoder blame when the new stream's headers never arrived.
     */
    fun fireReason(input: FireInput): FireReason = when {
        !input.hasScannedTracksOnce -> FireReason.TracksNotRead
        input.streamHasVideoTrack && !input.videoTrackSelected -> FireReason.VideoTrackUnsupported
        !input.streamHasVideoTrack -> FireReason.NoVideoTrack
        input.networkBytesGrew == true -> FireReason.DataStillArriving
        else -> FireReason.DecoderUnresponsive
    }

    /** The source-facing reasons (no tracks, no video track, slow data) share one string. */
    fun messageRes(reason: FireReason): Int = when (reason) {
        FireReason.VideoTrackUnsupported -> R.string.player_error_startup_video_track_unsupported
        FireReason.TracksNotRead, FireReason.NoVideoTrack, FireReason.DataStillArriving ->
            R.string.player_error_startup_no_stream_data
        FireReason.DecoderUnresponsive -> R.string.player_error_startup_timeout
    }
}
