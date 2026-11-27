package com.nuvio.tv.core.player

import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.core.util.TtffTrace
import com.nuvio.tv.core.util.safeHost
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Spec 85 D3: an mpv start that cannot play ends here, in order: the mpv->Exo failover (its setting
 * and G1's one switch per URL), the next source (NO_START), else the error box. The progress job
 * ends first; unless the failover's rebuild releases the core, mpv's file is stopped, so a failed
 * or wedged load never plays (or keeps downloading) behind what comes next.
 *
 * [sourceFailover] is false for an engine fault (an init or setup throw), which says nothing about
 * the source and must not mark it failed. [allowEngineFailover]: only the failover's own Exo
 * rebuild starts with it off, so an mpv start always has it on.
 */
internal fun PlayerRuntimeController.handleMpvStartupFailure(
    failure: PlaybackFailure,
    sourceFailover: Boolean = true,
    allowEngineFailover: Boolean = true
) {
    cancelStartupWatchdog()
    stopProgressUpdates()
    // Before the stop: the switch reads mpv's tracks for the carry.
    if (maybeAutoSwitchInternalPlayerOnStartupError(failure.message, allowEngineFailover)) return
    mpvView?.stopPlayback()
    if (sourceFailover && attemptStartupExhaustedSourceFailover(failure)) return
    cancelNextEpisodeAutoPlayOnFatalError()
    _uiState.update {
        it.copy(
            error = failure,
            showLoadingOverlay = false,
            isBuffering = false,
            isPlaying = false,
            playbackEnded = false,
            postPlayMode = null
        )
    }
}

/**
 * Spec 85 G9: a failed load mpv reported ([NuvioMpvSurfaceView.takeLoadFailure]), polled by the
 * progress loop, the mpv startup watchdog and on return from the background. Before the first frame it fails the start (true: the caller ends the progress
 * job); after it, it is only logged (deferred until a mid-play mpv end-file error shows up in a log).
 */
internal fun PlayerRuntimeController.handleMpvLoadFailure(fileError: String): Boolean {
    if (hasRenderedFirstFrame) {
        Log.w(
            PlayerRuntimeController.TAG,
            "MPV_END_FILE_ERROR after first frame (not handled): $fileError " +
                "host=${currentStreamUrl.safeHost()}"
        )
        return false
    }
    Log.w(PlayerRuntimeController.TAG, "MPV_LOAD_FAILED: $fileError host=${currentStreamUrl.safeHost()}")
    handleMpvStartupFailure(engineFailure("mpv: $fileError"))
    return true
}

/**
 * Spec 85 G10: mpv's startup watchdog, on the shared [PlayerRuntimeController.startupWatchdogJob]
 * (a new stream cancels it; the progress loop cancels it at mpv's first frame). Checks every
 * [PlayerRuntimeController.STARTUP_WATCHDOG_TIMEOUT_MS] and extends while mpv's demuxer cache
 * grows, up to the same ceiling as Exo's ([PlayerStartupWatchdogPolicy.shouldExtend]). While the
 * app is in the background or mpv is paused (a user pause, a paused start) the window restarts
 * instead of firing; keep-open's pause at the end of a file that decoded nothing is not a pause
 * here (`eof-reached`). A fire goes to [handleMpvStartupFailure]. A torrent start takes the same
 * TorrServer-progress rule as Exo's (Spec 138: [PlayerStartupWatchdogPolicy.TORRENT_IDLE_LIMIT_MS],
 * [PlayerStartupWatchdogPolicy.TORRENT_CEILING_MS]) and its fire measures the swarm's bytes.
 */
internal fun PlayerRuntimeController.scheduleMpvStartupWatchdog(view: NuvioMpvSurfaceView) {
    cancelStartupWatchdog()
    // Spec 138: a torrent fire measures bytes (0 for a dead swarm), so an earlier start's kept HTTP
    // error must not name it (PlaybackFailureCauses.startupHttpAnswer); as Exo's arm does.
    startupLoadHttpError = null
    startupWatchdogJob = scope.launch {
        var armedAtMs = SystemClock.elapsedRealtime()
        var lastCacheMs = 0L
        // Spec 138: the URL mpv was given; decided at arm, as Exo's watchdog does.
        val armedUrl = currentStreamUrl
        val torrentStart = isTorrentStartAt(armedUrl)
        var armedSwarm = torrentSwarmSample(armedUrl)
        var lastSwarm = armedSwarm
        while (isActive) {
            delay(PlayerRuntimeController.STARTUP_WATCHDOG_TIMEOUT_MS)
            if (hasRenderedFirstFrame || !isUsingMpvEngine() || mpvView !== view || !view.isCoreAlive()) {
                return@launch
            }
            val nowMs = SystemClock.elapsedRealtime()
            val swarm = if (torrentStart) torrentSwarmSample(armedUrl) else null
            // The window starts at the first polled sample (as Exo's watchdog).
            armedSwarm = PlayerStartupWatchdogPolicy.swarmBaseline(armedSwarm, swarm)
            if (isInBackground) {
                armedAtMs = nowMs
                lastCacheMs = 0L
                armedSwarm = swarm
                lastSwarm = swarm
                continue
            }
            // A failure mpv reported while the progress job was stopped (a user pause) ends the start
            // now, with mpv's own error rather than a timeout.
            val loadFailure = view.takeLoadFailure()
            if (loadFailure != null && handleMpvLoadFailure(loadFailure)) return@launch
            if (view.isPausedNow() && !view.isEofReachedNow()) {
                armedAtMs = nowMs
                lastCacheMs = 0L
                armedSwarm = swarm
                lastSwarm = swarm
                continue
            }
            val elapsedMs = nowMs - armedAtMs
            val cacheMs = (view.demuxerCacheDurationSec() * 1000.0).toLong().coerceAtLeast(0L)
            // Spec 138: mpv's own fetches are not counted; a torrent's swarm data is (read once per check).
            val input = view.startupWatchdogFireInput().let {
                if (torrentStart) {
                    it.copy(networkBytesGrew = swarm != null && PlayerStartupWatchdogPolicy.swarmDelivered(swarm, lastSwarm))
                } else {
                    it
                }
            }
            val swarmProgressed = PlayerStartupWatchdogPolicy.torrentExtendSignal(
                torrentStart = torrentStart,
                sample = swarm,
                last = lastSwarm,
                fireReason = PlayerStartupWatchdogPolicy.fireReason(input),
            )
            if (
                PlayerStartupWatchdogPolicy.shouldExtend(
                    elapsedMs = elapsedMs,
                    bufferedAheadMs = cacheMs,
                    lastBufferedAheadMs = lastCacheMs,
                    swarmProgressed = swarmProgressed,
                )
            ) {
                Log.w(
                    PlayerRuntimeController.TAG,
                    "STARTUP_WATCHDOG: engine=MPV no first frame ${elapsedMs}ms but demuxer cache " +
                        "${lastCacheMs}ms -> ${cacheMs}ms (swarmProgressed=$swarmProgressed); extending"
                )
                lastCacheMs = cacheMs
                if (swarm != null) lastSwarm = swarm
                continue
            }
            val fireReason = PlayerStartupWatchdogPolicy.fireReason(input)
            val windowBytes = PlayerStartupWatchdogPolicy.fireWindowBytes(
                torrentStart, swarm, armedSwarm, loopbackBytes = null
            )
            val fire = startupWatchdogFailure(fireReason, elapsedMs, networkBytes = windowBytes)
            Log.w(
                PlayerRuntimeController.TAG,
                "STARTUP_WATCHDOG: engine=MPV no first frame ${elapsedMs}ms (cacheMs=$cacheMs " +
                    "reason=${fireReason.logToken} tracks=${input.hasScannedTracksOnce} " +
                    "hasVideoTrack=${input.streamHasVideoTrack} videoSelected=${input.videoTrackSelected} " +
                    "host=${currentStreamUrl.safeHost()}); failing the start"
            )
            TtffTrace.mark("startup_watchdog_fired")
            handleMpvStartupFailure(fire)
            return@launch
        }
    }
}

/**
 * Spec 85 E7: the placeholder gate on mpv, once per stream after the first frame, on the decoded
 * duration alone (mpv's HTTP stack reports no content length). The latch is set only once mpv
 * knows a duration. True when the stream was rejected (the caller ends the progress job).
 */
internal fun PlayerRuntimeController.maybeRejectMpvPlaceholder(durationMs: Long): Boolean {
    if (placeholderProbeDone || durationMs <= 0L) return false
    if (
        !PlaceholderStreamPolicy.durationBackstopArmed(
            hasRenderedFirstFrame = hasRenderedFirstFrame,
            errorShown = _uiState.value.error != null,
            rejectedThisStream = placeholderRejectedForStream
        )
    ) {
        return false
    }
    placeholderProbeDone = true
    val verdict = PlaceholderStreamPolicy.evaluate(
        contentLengthBytes = null,
        durationMs = durationMs,
        expectedRuntimeMs = expectedRuntimeMinutes?.let { it * 60_000L }
    )
    Log.i(
        PlayerRuntimeController.TAG,
        "PLACEHOLDER_PROBE engine=MPV verdict=$verdict durationMs=$durationMs " +
            "expectedRuntimeMin=${expectedRuntimeMinutes ?: -1}"
    )
    if (verdict !is PlaceholderStreamPolicy.Verdict.Reject) return false
    rejectPlaceholderStream(verdict)
    return true
}
