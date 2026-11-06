package com.nuvio.tv.core.player

import com.nuvio.tv.core.util.safeHost
import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import com.nuvio.tv.R
import com.nuvio.tv.core.stream.limitKey
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val MAX_AUTO_RETRIES = 2
private const val MAX_DEAD_SOURCE_FAILOVERS = 3

/** How long the empty-list dead-source arm waits for the (cached) source list. */
private const val SOURCE_LIST_WAIT_MS = 15_000L

/**
 * Waits for the in-player source list to become available for a dead-source
 * failover. Replaces the old single-predicate wait, which read the PRE-load
 * state (not loading yet, list empty) as "completed with nothing" and gave up
 * in the same millisecond it started the load — while the scrape it never
 * waited for landed seconds later with dozens of failover candidates
 * (2026-09-03, Arshad's 404 report: the failover surrendered instantly on
 * every attempt). Two phases: the load must START (or a cached list appear),
 * then FINISH (list non-empty, or loading ended). Null on timeout.
 */
internal suspend fun awaitSourceListForFailover(
    states: StateFlow<PlayerUiState>,
    timeoutMs: Long = SOURCE_LIST_WAIT_MS
): PlayerUiState? = withTimeoutOrNull(timeoutMs) {
    val loadingStarted = states.first {
        it.isLoadingSourceStreams || it.sourceAllStreams.isNotEmpty()
    }
    if (loadingStarted.sourceAllStreams.isNotEmpty()) {
        loadingStarted
    } else {
        states.first { it.sourceAllStreams.isNotEmpty() || !it.isLoadingSourceStreams }
    }
}

// nt6 fix B: ceiling on TOTAL automatic recoveries for one stream URL (between
// stable-progress windows, which zero it) across the rungs of the ExoPlayer
// onPlayerError path (e.g. DV modes, bitstream retry, safe audio, PCM, 416, parsing
// probe, auto-retry; order: PlayerErrorRecoveryPolicy). A failover to another URL (dead-source,
// mid-play, startup-exhausted) is itself a budgeted rung there, but the next URL
// starts a fresh budget because the counter keys on the URL. Outside it, each
// bounded by its own latch: the background-crash branch (runs before the gate),
// the first-frame watchdog's codec rungs, the MPV path, and the startup failover
// taken when initializePlayer itself throws. Each ladder is individually bounded, but chained they observably looped a
// dead decoder pipeline for minutes — ~40 s per cycle on a large moov-at-tail
// MP4. Five covers the deepest legitimate chain while bounding the pathological case.
private const val MAX_TOTAL_AUTO_RECOVERIES_PER_STREAM = 5
private const val RETRY_DELAY_MS = 1_500L
private const val STABLE_PROGRESS_RESET_DELAY_MS = 5_000L

internal fun PlayerRuntimeController.showRecoveryOverlay() {
    _uiState.update { state ->
        state.copy(
            error = null,
            isBuffering = true,
            showLoadingOverlay = true,
            loadingMessage = context.getString(R.string.player_loading_buffering),
            showPauseOverlay = false
        )
    }
}

/**
 * Determines whether the given [PlaybackException] is transient and worth retrying.
 *
 * Retryable errors include source/IO errors, parsing glitches, and unexpected runtime
 * exceptions that commonly occur after pause/resume or seek on flaky streams.
 * Decoder-init and DRM errors are considered fatal.
 */
internal fun isRetryablePlaybackError(error: PlaybackException): Boolean {
    return when (error.errorCode) {
        // --- Source / IO errors (the 2xxx range) ---
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE, -> true

        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
            val httpCause = error.findCause<HttpDataSource.InvalidResponseCodeException>()
            if (httpCause != null) {
                val code = httpCause.responseCode
                !(code in DEAD_LINK_HTTP_RESPONSE_CODES)
            } else {
                true
            }
        }
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,

        // --- Decoder errors (often transient after pause/resume on some hardware) ---
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> true

        // --- Behind-the-scenes / unexpected errors (often IllegalStateException / NPE) ---
        PlaybackException.ERROR_CODE_UNSPECIFIED -> {
            val cause = error.cause
            cause is IllegalStateException || cause is NullPointerException
        }

        // Media3's stuck-buffering watchdog: a loading deadlock, re-prepared on the same link.
        PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK -> error.isStuckBufferingWatchdog()

        else -> false
    }
}

/**
 * Audio-track failures that the safe-audio → audio-disabled fallback ladder can recover from.
 *
 * - [PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED] (5001): the AudioTrack could not be
 *   created (e.g. the requested passthrough/offload encoding is not actually accepted by the sink).
 * - [PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED] (5002): a write to the AudioTrack
 *   failed, most commonly with `AudioTrack.ERROR_DEAD_OBJECT` (-6) when an HDMI/audio-route
 *   renegotiation invalidates an E-AC-3/AC-3 passthrough or offload track mid-playback.
 *
 * Both are remedied by re-selecting audio with tunneling/passthrough off and the channel count
 * constrained to the device's capabilities (safe-audio mode), or by dropping audio entirely — so
 * a write failure must take the same recovery path as an init failure rather than landing on the
 * fatal error screen.
 *
 * [combinedMessage] is the concatenated exception/cause messages; the string checks are a safety
 * net for devices that surface the same failure under a generic error code.
 */
internal fun isAudioTrackFailure(errorCode: Int, combinedMessage: String): Boolean {
    if (errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED) return true
    if (errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED) return true
    return combinedMessage.contains("audiotrack init failed", ignoreCase = true) ||
        combinedMessage.contains("audiotrack write failed", ignoreCase = true)
}

/**
 * The subset of [isAudioTrackFailure] that is a refused AudioTrack open (5001, or the init
 * message under a generic code): the only failure F3 may learn from. A 5002 write failure is a
 * track that opened and later died (DEAD_OBJECT on an HDMI renegotiation), so it says nothing
 * about whether the receiver takes the format (Register A2 P1).
 */
internal fun isAudioTrackInitFailure(errorCode: Int, combinedMessage: String): Boolean {
    if (errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED) return true
    if (errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED) return false
    return combinedMessage.contains("audiotrack init failed", ignoreCase = true)
}

/**
 * Media3's stuck-buffering watchdog ("Playback stuck buffering and not loading", an
 * IllegalStateException surfaced as 8000 FAILED_RUNTIME_CHECK): the player sat buffering with a
 * near-empty buffer while the LoadControl had stopped loading (a deadlock, e.g. a byte target
 * met by one track; see the dvmkv MatroskaExtractor note). Not a decoder or audio fault: it takes
 * the same-link auto-retry (a fresh player on the second attempt), never the DV-conversion or
 * audio fallbacks, which would drop the stream to the HDR10 base layer or turn passthrough off
 * for nothing (upstream-sync Register A2 P2; upstream `0954ef772` guards the DV arm only).
 */
internal fun isStuckBufferingWatchdog(errorCode: Int, combinedMessage: String): Boolean {
    if (errorCode != PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK) return false
    return combinedMessage.contains("stuck buffering and not loading", ignoreCase = true)
}

internal fun PlaybackException.isStuckBufferingWatchdog(): Boolean =
    isStuckBufferingWatchdog(errorCode, causeChainText())

/** message, cause.message and cause.cause.message joined by spaces, for message matching. */
internal fun PlaybackException.causeChainText(): String = buildString {
    append(message ?: "")
    append(' ')
    append(cause?.message ?: "")
    append(' ')
    append(cause?.cause?.message ?: "")
}

internal fun PlaybackException.findInvalidResponseCodeException(): HttpDataSource.InvalidResponseCodeException? {
    var current: Throwable? = cause
    while (current != null) {
        if (current is HttpDataSource.InvalidResponseCodeException) return current
        current = current.cause
    }
    return null
}

internal fun PlaybackException.toDisplayMessage(context: android.content.Context): String {
    val responseException = findInvalidResponseCodeException()
    if (responseException != null) {
        val code = responseException.responseCode
        val statusText = responseException.responseMessage?.takeIf { it.isNotBlank() }
        val providerHint = when (code) {
            400 -> context.getString(com.nuvio.tv.R.string.player_error_stream_blocked)
            401 -> context.getString(com.nuvio.tv.R.string.player_error_stream_expired)
            403 -> context.getString(com.nuvio.tv.R.string.player_error_stream_blocked)
            404 -> context.getString(com.nuvio.tv.R.string.player_error_stream_removed)
            410 -> context.getString(com.nuvio.tv.R.string.player_error_stream_expired)
            429 -> context.getString(com.nuvio.tv.R.string.player_error_stream_rate_limited)
            500, 502, 503, 504 -> context.getString(com.nuvio.tv.R.string.player_error_stream_unavailable)
            else -> ""
        }
        return buildString {
            append("HTTP $code")
            statusText?.let { append(" $it") }
            append(" [$errorCodeName]")
            append(providerHint)
        }
    }

    // Check for unrecognized format (provider returned non-video content)
    val isUnrecognizedFormat = findCause<androidx.media3.exoplayer.source.UnrecognizedInputFormatException>() != null
    if (isUnrecognizedFormat) {
        return context.getString(com.nuvio.tv.R.string.player_error_source_invalid_content, errorCodeName)
    }

    // Check for codec/renderer errors
    val isRendererError = errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
        errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
    if (isRendererError) {
        val meaningfulMessage = findMostRelevantCauseMessage()
        val decoderHeader = meaningfulMessage ?: context.getString(com.nuvio.tv.R.string.player_error_decoder)
        val unsupported = context.getString(com.nuvio.tv.R.string.player_error_unsupported_format, errorCodeName)
        return "$decoderHeader\n\n$unsupported"
    }

    val meaningfulMessage = findMostRelevantCauseMessage()
    return if (meaningfulMessage != null) {
        "$meaningfulMessage [$errorCodeName]"
    } else {
        errorCodeName
    }
}

internal fun Throwable.toDisplayMessage(context: android.content.Context, fallback: String? = null): String {
    val meaningfulMessage = findMostRelevantCauseMessage()
    return meaningfulMessage
        ?: message?.takeIf { it.isNotBlank() }
        ?: fallback
        ?: context.getString(com.nuvio.tv.R.string.player_error_playback_fallback)
}

private fun Throwable.findMostRelevantCauseMessage(): String? {
    val candidates = buildList {
        var current: Throwable? = this@findMostRelevantCauseMessage
        while (current != null) {
            current.message
                ?.trim()
                ?.takeIf {
                    it.isNotBlank() &&
                        !it.equals("Playback error", ignoreCase = true) &&
                        !it.equals("Source error", ignoreCase = true) &&
                        !it.equals("Unexpected runtime error", ignoreCase = true)
                }
                ?.let(::add)
            current = current.cause
        }
    }
    return candidates.firstOrNull()
}

/**
 * Attempts an automatic retry of the current stream, preserving the playback position.
 *
 * The first retry re-prepares the current player, and the second retry fully rebuilds it,
 * so recovery stays on the loading overlay until playback succeeds or finally fails. A track
 * that needs a warm decoder (Spec 57 D2) rebuilds on both: the error released its codec.
 *
 * Returns `true` if a retry was scheduled, `false` if the error should be shown to the user.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.attemptAutoRetry(
    error: PlaybackException,
    detailedError: String
): Boolean {
    if (!isRetryablePlaybackError(error)) return false
    // Dead URLs (non-media body, 404/410) never benefit from same-URL retries;
    // they are handled by attemptDeadSourceFailover before this is reached.
    if (isDeadSourcePlaybackError(error)) return false
    if (errorRetryCount >= MAX_AUTO_RETRIES) return false

    val paused = userPausedManually
    val attempt = errorRetryCount
    errorRetryCount++

    Log.w(
        PlayerRuntimeController.TAG,
        "Auto-retry ${attempt + 1}/$MAX_AUTO_RETRIES after ${RETRY_DELAY_MS}ms for: $detailedError"
    )

    // Capture the current position so we can resume after re-init.
    val savedPosition = _exoPlayer?.let(::exoCarryPositionMs)?.takeIf { it > 0L } ?: 0L
    val isFirstAttempt = attempt == 0
    // Spec 57 D2: an in-place re-prepare after the error released the codec enters it cold at the
    // position; on a track that needs a warm decoder only the full rebuild (which primes) recovers.
    val inPlace = isFirstAttempt && !videoNeedsWarmDecoder

    errorRetryJob?.cancel()
    errorRetryJob = scope.launch {
        _uiState.update {
            it.copy(
                error = null,
                showLoadingOverlay = if (inPlace) false else it.loadingOverlayEnabled,
                showPauseOverlay = false
            )
        }

        delay(RETRY_DELAY_MS)

        if (inPlace) {
            // Lightweight recovery: re-prepare the same source without destroying the player.
            val player = _exoPlayer
            if (player != null) {
                if (savedPosition > 0L) {
                    player.seekTo((savedPosition - 1).coerceAtLeast(0L))
                }
                player.prepare()
                // Only resume playback if the user hadn't paused.
                player.playWhenReady = !paused
            } else {
                rebuildCurrentStream("auto-retry", savedPosition, paused)
            }
        } else {
            // Full teardown — clears any corrupt decoder/internal state.
            rebuildCurrentStream("auto-retry", savedPosition, paused)
        }
    }
    return true
}

/**
 * Resets the per-stream retry state. Called after stable progress
 * (scheduleStableProgressReset), on a manual engine switch, and when a new
 * stream or episode starts. Automatic paths pass
 * [resetDeadSourceFailovers] = false: a dead-source failover (or a recovery on
 * the source it landed on) must not refill the MAX_DEAD_SOURCE_FAILOVERS cap it
 * is drawing on, or the cap never trips.
 */
internal fun PlayerRuntimeController.resetErrorRetryState(resetDeadSourceFailovers: Boolean = true) {
    errorRetryCount = 0
    if (resetDeadSourceFailovers) deadSourceFailoverCount = 0
    hasRetriedAfterMimeOverrideClear = false
    parsingErrorProbeAttempted = false
    pendingAudioPcmFallbackRebuild = false
    errorRetryJob?.cancel()
    errorRetryJob = null
}

internal fun PlayerRuntimeController.scheduleStableProgressReset() {
    stableProgressResetJob?.cancel()
    stableProgressResetJob = scope.launch {
        delay(STABLE_PROGRESS_RESET_DELAY_MS)
        val player = _exoPlayer ?: return@launch
        if (player.playbackState == Player.STATE_READY && player.isPlaying) {
            resetErrorRetryState()
            // Healthy playback ends a recovery chain: a later, independent error
            // gets a fresh nt6 fix B budget instead of inheriting this one.
            autoRecoveryCountForCurrentStream = 0
        }
    }
}

internal fun PlayerRuntimeController.cancelStableProgressReset() {
    stableProgressResetJob?.cancel()
    stableProgressResetJob = null
}

internal fun PlayerRuntimeController.refreshStableProgressResetGate() {
    if (!hasRenderedFirstFrame) return
    val player = _exoPlayer ?: return
    val healthy = player.playbackState == Player.STATE_READY && player.isPlaying
    if (healthy) {
        if (stableProgressResetJob?.isActive != true) {
            scheduleStableProgressReset()
        }
    } else {
        cancelStableProgressReset()
    }
}

/** The safe-audio ladder's rungs, in order (see [tryAudioRecoveryLadder]). */
internal enum class AudioRecoveryRung { SAFE_AUDIO, PCM, AUDIO_OFF }

/**
 * The next rung, or null when the ladder is spent. Audio off is terminal: [pcmTried] is per player
 * build (initializePlayer clears it unless a PCM rebuild is pending), so after the audio-off
 * rebuild it reads false again and must not buy a pointless muted PCM rebuild.
 */
internal fun nextAudioRecoveryRung(
    safeAudioActive: Boolean,
    pcmTried: Boolean,
    audioDisabled: Boolean
): AudioRecoveryRung? = when {
    audioDisabled -> null
    !safeAudioActive -> AudioRecoveryRung.SAFE_AUDIO
    !pcmTried -> AudioRecoveryRung.PCM
    else -> AudioRecoveryRung.AUDIO_OFF
}

/**
 * The audio-failure ladder (decode failure, AudioTrack failure, stuck playing): safe audio, then
 * safe audio with the FFmpeg software (PCM) decoder, then audio off so video keeps playing.
 * Schedules the rebuild and returns true, or false once every rung was tried. The PCM rung arms
 * [PlayerRuntimeController.pendingAudioPcmFallbackRebuild] as [tryAudioTrackPcmFallback] does:
 * without it `initializePlayer` cleared the flag, the rung replayed plain safe audio and the
 * recovery budget ran out before audio-off (DRDYK B1). The arming is not unit-pinned (no controller
 * harness); this KDoc and tryAudioTrackPcmFallback are its guard.
 */
internal fun PlayerRuntimeController.tryAudioRecoveryLadder(fromPositionMs: Long): Boolean {
    val rung = nextAudioRecoveryRung(
        safeAudioActive = isSafeAudioModeActiveForCurrentPlayback,
        pcmTried = hasTriedAudioPcmFallback,
        audioDisabled = isAudioDisabledForCurrentPlayback
    ) ?: return false
    when (rung) {
        AudioRecoveryRung.SAFE_AUDIO -> {
            safeAudioForcedStreamUrls.add(currentStreamUrl)
            scheduleDeferredPlayerReinitialize("safe audio", fromPositionMs)
        }
        AudioRecoveryRung.PCM -> {
            hasTriedAudioPcmFallback = true
            pendingAudioPcmFallbackRebuild = true
            scheduleDeferredPlayerReinitialize("safe audio PCM", fromPositionMs)
        }
        AudioRecoveryRung.AUDIO_OFF -> {
            audioDisabledForcedStreamUrls.add(currentStreamUrl)
            scheduleDeferredPlayerReinitialize("audio off", fromPositionMs)
        }
    }
    return true
}

/**
 * Silent PCM audio fallback for ERROR_CODE_AUDIO_TRACK_INIT_FAILED (5001).
 *
 * When the decoder is set to EXTENSION_RENDERER_MODE_ON (decoderPriority == 1,
 * the default) and tunneling is NOT active, audio passthrough may fail on certain devices/formats.
 * Rebuilds the player at the current position with PCM output forced
 * (pendingAudioPcmFallbackRebuild, read by the next initializePlayer) before the safe-audio ladder.
 *
 * This is a one-shot attempt per stream; if it fails again the normal retry
 * logic takes over.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.tryAudioTrackPcmFallback(
    error: PlaybackException
): Boolean {
    if (error.errorCode != PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED) return false
    if (hasTriedAudioPcmFallback) return false
    if (cachedDecoderPriority != 1) return false // Only for EXTENSION_RENDERER_MODE_ON
    if (_uiState.value.tunnelingEnabled) return false

    hasTriedAudioPcmFallback = true
    pendingAudioPcmFallbackRebuild = true

    val player = _exoPlayer ?: return false
    val savedPosition = exoCarryPositionMs(player).takeIf { it > 0L } ?: 0L
    val paused = userPausedManually

    Log.d(PlayerRuntimeController.TAG, "Audio track init failed (5001) — rebuilding player with PCM forcing, position=${savedPosition}ms")
    showRecoveryOverlay()

    errorRetryJob?.cancel()
    errorRetryJob = scope.launch {
        rebuildCurrentStream("audio PCM fallback", savedPosition, paused)
    }

    return true
}

/**
 * Same-configuration AudioTrack-open retries for one stream (upstream-sync Register A2 P5):
 * [MAX_ATTEMPTS] per stream URL, refilled once [REFILL_AFTER_MS] pass after the last retry's
 * rebuild (checked lazily at the next refusal). Upstream `PlaybackRecoveryGate` (`0954ef772`).
 */
internal class BitstreamAudioRetryBudget {
    private var streamUrl: String? = null
    private var used = 0
    private var lastRebuildAtMs = 0L

    /** The 1-based attempt number when a retry is available for [url], else null. */
    fun take(url: String, nowMs: Long): Int? {
        if (url != streamUrl) {
            streamUrl = url
            used = 0
        } else if (used > 0 && nowMs - lastRebuildAtMs >= REFILL_AFTER_MS) {
            used = 0
        }
        if (used >= MAX_ATTEMPTS) return null
        used++
        lastRebuildAtMs = nowMs
        return used
    }

    /** Restarts the refill clock when the retry's rebuild starts (after any output wait). */
    fun noteRebuild(nowMs: Long) {
        lastRebuildAtMs = nowMs
    }

    companion object {
        const val MAX_ATTEMPTS = 2
        const val REFILL_AFTER_MS = 60_000L
        const val POLL_MS = 500L
        const val MAX_WAIT_MS = 30_000L

        fun delayMs(attempt: Int): Long = 400L * attempt
    }
}

/**
 * Whether a failure is a refused open of a bitstream the policy lets through: the only case a
 * same-configuration retry can fix (the receiver or HDMI link was briefly not ready). A PCM sink
 * input or a denied format already decodes; a 5002 is a track that opened.
 */
internal fun isBitstreamSameConfigRetryCandidate(
    errorCode: Int,
    combinedMessage: String,
    sinkInputMime: String?,
    policyDeniesMime: Boolean
): Boolean = isAudioTrackInitFailure(errorCode, combinedMessage) &&
    AudioTrackRejectionLog.labelForMime(sinkInputMime) != null &&
    !policyDeniesMime

/**
 * Whether the wait before a bitstream retry is over: the output claims the format again, media
 * moved to a non-HDMI output (that receiver is not coming back for this play), or the cap passed.
 */
internal fun bitstreamRetryWaitOver(outputClaimsFormat: Boolean, routeKey: String?, waitedMs: Long): Boolean =
    outputClaimsFormat ||
        (routeKey != null && !com.nuvio.tv.core.player.AudioOutputRouteDetector.isHdmiRouteKey(routeKey)) ||
        waitedMs >= BitstreamAudioRetryBudget.MAX_WAIT_MS

/**
 * P5: a 5001 on a bitstream the policy allows rebuilds the player with the SAME configuration
 * (up to two times, see [BitstreamAudioRetryBudget]) before the PCM-forced rebuild, after
 * waiting for the output to come back: a rebuild while HDMI is gone pins PCM-only capabilities
 * for the rest of the title. Keeps lossless audio through receiver/HDMI-CEC blips.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.tryBitstreamAudioTrackSameConfigRetry(error: PlaybackException): Boolean {
    val format = failedAudioTrackInputFormat(error) ?: return false
    val mime = format.sampleMimeType
    val eligible = isBitstreamSameConfigRetryCandidate(
        errorCode = error.errorCode,
        combinedMessage = error.causeChainText(),
        sinkInputMime = mime,
        policyDeniesMime = currentAudioPassthroughPolicy?.deniesPassthrough(mime) == true
    )
    if (!eligible) return false
    val player = _exoPlayer ?: return false
    val url = currentStreamUrl
    val attempt = bitstreamAudioRetryBudget.take(url, android.os.SystemClock.elapsedRealtime()) ?: return false
    val savedPosition = exoCarryPositionMs(player).takeIf { it > 0L } ?: 0L
    val paused = userPausedManually
    val delayMs = BitstreamAudioRetryBudget.delayMs(attempt)
    Log.w(
        PlayerRuntimeController.TAG,
        "AUDIO_BITSTREAM_RETRY attempt=$attempt/${BitstreamAudioRetryBudget.MAX_ATTEMPTS} mime=$mime " +
            "delayMs=$delayMs position=${savedPosition}ms"
    )
    showRecoveryOverlay()
    seedTrackPreferenceCarryForPlayerRebuild()
    // Release synchronously (what the inline Main.immediate launch elsewhere amounts to), THEN
    // start the wait: releasePlayer cancels errorRetryJob, and this job must stay in it so exit,
    // a new stream, an engine switch or another release cancel the wait (review 193037 #1).
    errorRetryJob?.cancel()
    releasePlayer(flushPlaybackState = false)
    if (savedPosition > 0L) {
        _uiState.update { it.copy(pendingSeekPosition = savedPosition) }
    }
    errorRetryJob = scope.launch {
        delay(delayMs)
        awaitAudioOutputForBitstreamRetry(format)
        if (currentStreamUrl != url) return@launch
        if (isInBackground) {
            // Same deferral as onPlayerError's background branch: no decoder grab while away;
            // resumeForLifecycle rebuilds paused at the saved position.
            backgroundCrashSavedPositionMs = savedPosition
            pendingBackgroundCrashRecovery = true
            return@launch
        }
        bitstreamAudioRetryBudget.noteRebuild(android.os.SystemClock.elapsedRealtime())
        initializePlayer(url, currentHeaders, startPaused = paused || userPausedManually)
    }
    return true
}

private suspend fun PlayerRuntimeController.awaitAudioOutputForBitstreamRetry(format: androidx.media3.common.Format) {
    val startedMs = android.os.SystemClock.elapsedRealtime()
    var waiting = false
    while (true) {
        val waitedMs = android.os.SystemClock.elapsedRealtime() - startedMs
        val routeKey = detectAudioRouteKey()
        if (bitstreamRetryWaitOver(liveOutputClaimsPassthrough(format), routeKey, waitedMs)) {
            if (waiting) {
                // The output was gone and the route callback was unregistered with the player:
                // stand in for the device events it missed, so a refusal right after the
                // output returns reads ROUTE_CHANGING in the F3 outage guard (review 193037 #2).
                lastAudioDeviceChangeAtMs = android.os.SystemClock.elapsedRealtime()
                Log.i(PlayerRuntimeController.TAG, "AUDIO_BITSTREAM_RETRY wait over after ${waitedMs}ms route=$routeKey")
            }
            return
        }
        if (!waiting) {
            Log.w(PlayerRuntimeController.TAG, "AUDIO_BITSTREAM_RETRY waiting for the audio output route=$routeKey")
            waiting = true
        }
        delay(BitstreamAudioRetryBudget.POLL_MS)
    }
}

/**
 * FFmpeg-preferred rebuild for ERROR_CODE_DECODER_INIT_FAILED (4001) on an audio
 * renderer whose failing format belongs to a policy-denied group.
 *
 * Root cause (F5 investigation, 5 Aug 2026): a hybrid track (e.g. DTS-HD MA in
 * Matroska) is exposed at selection time under its base MIME (audio/vnd.dts),
 * which the user may not have denied, so the MediaCodec audio renderer wins the
 * mapping tie. When the sample pipeline reads the extension substream it upgrades
 * the format mid-stream to the denied MIME (audio/vnd.dts.hd); the policy
 * abdication then leaves the already-selected renderer with no decoder (-49999),
 * and media3 never remaps a track mid-stream, so the generic retry rebuilds into
 * the identical trap. Retrying with FFmpeg audio preferred (the same audio-local
 * reorder Force AC-3 uses) makes FFmpeg win the tie for the whole family; it
 * decodes both the base and upgraded formats.
 *
 * Deliberate trade-off: while active (this stream only), FFmpeg wins ties for
 * every audio format it fully supports, so a second audio track that could have
 * passed through decodes to PCM instead. Degraded-but-playing beats the error
 * screen.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.tryDeniedAudioFfmpegFallback(
    error: PlaybackException
): Boolean {
    if (error.errorCode != PlaybackException.ERROR_CODE_DECODER_INIT_FAILED) return false
    if (currentStreamUrl in preferFfmpegAudioStreamUrls) return false
    if (cachedDecoderPriority == 0) return false // No FFmpeg renderer without extensions.
    val failingMime = (error as? androidx.media3.exoplayer.ExoPlaybackException)
        ?.rendererFormat?.sampleMimeType
    if (failingMime == null || !androidx.media3.common.MimeTypes.isAudio(failingMime)) return false
    val policy = currentAudioPassthroughPolicy ?: return false
    if (!policy.deniesPassthrough(failingMime)) return false

    preferFfmpegAudioStreamUrls.add(currentStreamUrl)

    val paused = userPausedManually
    val savedPosition = _exoPlayer?.let(::exoCarryPositionMs)?.takeIf { it > 0L } ?: 0L

    Log.d(
        PlayerRuntimeController.TAG,
        "Decoder init failed (4001) on policy-denied audio $failingMime - retrying with FFmpeg audio preferred, position=${savedPosition}ms"
    )

    resetErrorRetryState(resetDeadSourceFailovers = false)

    errorRetryJob = scope.launch {
        showRecoveryOverlay()

        rebuildCurrentStream("denied audio FFmpeg", savedPosition, paused)
    }
    return true
}

internal fun PlayerRuntimeController.tryParsingErrorProbeFallback(
    error: PlaybackException,
    detailedError: String,
    savedPosition: Long = 0L,
    paused: Boolean = userPausedManually
): Boolean {
    val isSourceOrParsingError = error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
        error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
        error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ||
        error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
        error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ||
        error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
        error.findCause<androidx.media3.exoplayer.source.UnrecognizedInputFormatException>() != null ||
        error.cause?.toString()?.contains("UnrecognizedInputFormatException") == true

    if (!isSourceOrParsingError) return false
    // NuvioTV fork (T2, ysosrs cad15d29e): mid-play, the container already played
    // for a while under a known non-HLS mimeType - re-probing the format cannot
    // help (the data is corrupt, not the container label) and costs a network
    // round trip. Skip the probe; the mid-play malformed failover in
    // onPlayerError and the auto-retry ladder handle it. HLS (M3U8) still probes
    // for live-window recovery.
    if (hasRenderedFirstFrame &&
        currentStreamMimeType != null &&
        currentStreamMimeType != androidx.media3.common.MimeTypes.APPLICATION_M3U8
    ) {
        return false
    }
    if (parsingErrorProbeAttempted) return false
    parsingErrorProbeAttempted = true

    val previousMimeType = currentStreamMimeType
    Log.w(
        PlayerRuntimeController.TAG,
        "Source/parsing error [${error.errorCode}] detected (previous mimeType=$previousMimeType). " +
            "Probing stream format..."
    )

    errorRetryJob?.cancel()
    errorRetryJob = scope.launch {
        showRecoveryOverlay()
        val probedMime = PlayerMediaSourceFactory.probeNetworkMimeType(
            url = currentStreamUrl,
            headers = currentHeaders
        )

        if (probedMime != null && probedMime != previousMimeType) {
            Log.i(
                PlayerRuntimeController.TAG,
                "Stream probe resolved mimeType=$probedMime (was $previousMimeType). Retrying playback..."
            )
            currentStreamMimeType = probedMime
            currentStreamResponseHeaders = emptyMap()
            rebuildCurrentStream("parsing probe", savedPosition, paused)
        } else if (previousMimeType == androidx.media3.common.MimeTypes.APPLICATION_M3U8) {
            currentStreamMimeType = null
            currentStreamResponseHeaders = emptyMap()
            rebuildCurrentStream("parsing probe HLS", savedPosition, paused)
        } else {
            // Fork dead-source rung (community 3003 report): the probe failed to
            // name a better container, so a sniff-failure body (HTML page behind
            // HTTP 200, .rar/.zip payload) or HTTP 404/410 is permanent for this
            // URL. Advance to the next source instead of burning both same-URL
            // retries on a doomed link.
            if (isDeadSourcePlaybackError(error) && attemptDeadSourceFailover(error, detailedError)) {
                return@launch
            }
            if (attemptAutoRetry(error, detailedError)) {
                return@launch
            }
            // The rungs after PARSING_PROBE never run (it took the error), so its tail mirrors them.
            if (attemptStartupExhaustedSourceFailover(detailedError)) {
                return@launch
            }
            val userFacingError = error.toDisplayMessage(context)
            _uiState.update {
                it.copy(
                    error = userFacingError,
                    isBuffering = false,
                    showLoadingOverlay = false,
                    showPauseOverlay = false
                )
            }
        }
    }
    return true
}

/** @return true if a mimeType override was present and has been cleared. */
private fun PlayerRuntimeController.clearMimeOverrideForParsingError(error: PlaybackException): Boolean {
    if (error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
        error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
        error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ||
        error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED
    ) {
        if (currentStreamMimeType != null) {
            Log.w(
                PlayerRuntimeController.TAG,
                "Parsing error [${error.errorCode}] detected with mimeType=$currentStreamMimeType. " +
                        "Clearing mimeType override for fallback."
            )
            currentStreamMimeType = null
            currentStreamResponseHeaders = emptyMap()
            return true
        }
    }
    return false
}

/**
 * Dead-source classification (community 3003 report).
 *
 * A container-sniff failure where the content is NOT malformed media - media3's
 * [androidx.media3.exoplayer.source.UnrecognizedInputFormatException], surfaced as
 * ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED (3003) - means the body is not media at
 * all: an HTML error page behind HTTP 200, or a .rar/.zip payload from a torrent.
 * HTTP 404/410 are equally permanent for the URL. Same-URL retries only make the
 * user sit through doomed rebuild cycles.
 *
 * Deliberately NOT classified dead: HTTP 429 and timeouts - auto-advancing on a
 * debrid rate limit (TorBox parallel-connection 429s are transient) would wrongly
 * burn perfectly good sources.
 */
internal fun PlayerRuntimeController.isDeadSourcePlaybackError(error: PlaybackException): Boolean {
    if (isDeadSourceHttpError(error)) return true
    return error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED &&
        error.findCause<androidx.media3.exoplayer.source.UnrecognizedInputFormatException>() != null
}

/**
 * The HTTP arm of the dead-source classification. 404/410 is certain-dead with
 * no probe value, so callers can advance without spending the 0.8.5
 * parsing-error probe on it.
 */
internal fun isDeadSourceHttpError(error: PlaybackException): Boolean {
    val http = error.findInvalidResponseCodeException()
    return http != null && (http.responseCode == 404 || http.responseCode == 410)
}

/**
 * Handles a dead-source error: one narrowly-scoped same-URL retry if a mimeType
 * override was steering the extractor (the only genuinely recoverable sub-case),
 * otherwise mark the URL dead for the session and auto-advance to the next source
 * in the user's existing sort order. Returns false when out of options (failover
 * cap reached or no live sources left) so the caller surfaces the error screen.
 */
internal fun PlayerRuntimeController.attemptDeadSourceFailover(
    error: PlaybackException,
    detailedError: String
): Boolean {
    // The override is baked into the MediaItem, so only a FULL re-init applies the
    // clear - a bare prepare() retry cannot (which is why the old auto-retry's first
    // attempt never fixed this case). One full retry, then the URL is treated as dead.
    if (clearMimeOverrideForParsingError(error) && !hasRetriedAfterMimeOverrideClear) {
        hasRetriedAfterMimeOverrideClear = true
        val paused = userPausedManually
        val savedPosition = _exoPlayer?.let(::exoCarryPositionMs)?.takeIf { it > 0L } ?: 0L
        Log.w(
            PlayerRuntimeController.TAG,
            "Dead-source check: mimeType override cleared; one full re-init for: $detailedError"
        )
        errorRetryJob?.cancel()
        errorRetryJob = scope.launch {
            _uiState.update {
                it.copy(error = null, showLoadingOverlay = it.loadingOverlayEnabled, showPauseOverlay = false)
            }
            delay(RETRY_DELAY_MS)
            rebuildCurrentStream("mime override cleared", savedPosition, paused)
        }
        return true
    }

    return advanceToNextLiveSource(detailedError)
}

/**
 * Marks the current stream URL dead for this session (greying it in the source
 * panel), then auto-advances to the next live source in the user's existing
 * sort order. Shared by the dead-source, mid-play, startup-exhausted and
 * placeholder paths; all draw on the same MAX_DEAD_SOURCE_FAILOVERS cap. Returns
 * false when out of options (cap reached or no live sources left) so the caller
 * surfaces the error screen. After a placeholder reject the placeholder's playhead
 * is not carried ([failoverPendingSeek]); the next source resumes from saved progress.
 */
internal fun PlayerRuntimeController.advanceToNextLiveSource(detailedError: String): Boolean {
    // Mark dead: both the resolved playback URL and, where identifiable, the
    // original list entry (debrid resolution can make these differ) so the
    // source panel greys it and a manual re-pick is visibly discouraged.
    deadSourceStreamUrls.add(currentStreamUrl)
    val state = _uiState.value
    val streams = sourceStreamsForPlayingItem()
    val currentIdx = findCurrentStreamIndex(
        streams = streams,
        currentStreamInfoHash = state.currentStreamInfoHash,
        currentStreamFileIdx = state.currentStreamFileIdx,
        currentStreamAddonName = state.currentStreamAddonName,
        currentStreamUrl = state.currentStreamUrl
    )
    if (currentIdx >= 0) {
        streams.getOrNull(currentIdx)?.getStreamUrl()?.let { deadSourceStreamUrls.add(it) }
    }
    _uiState.update { it.copy(deadSourceStreamUrls = deadSourceStreamUrls.toSet()) }

    if (deadSourceFailoverCount >= MAX_DEAD_SOURCE_FAILOVERS) {
        Log.w(
            PlayerRuntimeController.TAG,
            "Dead-source failover cap ($MAX_DEAD_SOURCE_FAILOVERS) reached; surfacing error"
        )
        return false
    }
    if (streams.isEmpty()) {
        // Direct-launch case (2026-08-30, Arshad's expired-link report): the in-player
        // source list only loads when the sources/episodes panel opens, so a dead link
        // on a freshly launched playback arrived with no candidates in hand and the
        // failover gave up straight to the error screen. Load the (repository-cached)
        // list and continue the failover when it lands; only surface the error when
        // nothing is loadable. Also taken after an episode switch.
        Log.w(
            PlayerRuntimeController.TAG,
            "Dead source with no source list for the playing item; loading cached list to fail over"
        )
        errorRetryJob?.cancel()
        errorRetryJob = scope.launch {
            _uiState.update {
                it.copy(
                    error = null,
                    showPauseOverlay = false,
                    showLoadingOverlay = it.loadingOverlayEnabled
                )
            }
            loadSourceStreams(
                // A cached ERROR is not a list to fail over from: when the last
                // load for this item ended in an error, force a fresh scrape
                // instead of letting loadSourceStreams early-return on it. An
                // error left by the previous episode does not force one.
                forceRefresh = sourceListIsForPlayingItem() && _uiState.value.sourceStreamsError != null
            )
            val loaded = awaitSourceListForFailover(_uiState)
            // The same scoped list the re-entered call reads, so a mismatch cannot
            // re-enter this branch.
            if (loaded != null && sourceStreamsForPlayingItem().isNotEmpty() &&
                advanceToNextLiveSource(detailedError)
            ) {
                return@launch
            }
            Log.w(PlayerRuntimeController.TAG, "No sources loadable for dead-source failover; surfacing error")
            _uiState.update {
                it.copy(
                    error = detailedError,
                    showLoadingOverlay = false,
                    showPauseOverlay = false
                )
            }
        }
        return true
    }

    val startIdx = if (currentIdx >= 0) currentIdx + 1 else 0
    // Spec 51 (R4-1): the limit's hidden set computed now, over the list walked, with the playing
    // key and binge exemption as of now; never a stored set, so a stream the user switched away
    // from does not stay eligible. All remaining hidden = the error screen (R5-8).
    val hiddenKeys = sourceHiddenKeysNow(state)
    val next = nextLiveSource(streams, startIdx, deadSourceStreamUrls, hiddenKeys, p2pEnabled) ?: run {
        Log.w(
            PlayerRuntimeController.TAG,
            "Dead source and no live sources after index $currentIdx (hidden=${hiddenKeys.size}); surfacing error"
        )
        return false
    }

    deadSourceFailoverCount++
    val attemptNo = deadSourceFailoverCount
    Log.w(
        PlayerRuntimeController.TAG,
        "Dead source ($detailedError) - failing over to next source ($attemptNo/$MAX_DEAD_SOURCE_FAILOVERS): " +
                "host=${next.getStreamUrl()?.safeHost()}"
    )
    val livePosition = _exoPlayer?.let(::exoCarryPositionMs) ?: 0L
    errorRetryJob?.cancel()
    scope.launch {
        // One quiet line when the app substitutes the user's pick (Arshad, 2026-08-30):
        // silent swapping confuses ("why is the quality different"); a two-second
        // notice does not. Existing Toast pattern, same as the stream screen's.
        android.widget.Toast.makeText(
            context,
            context.getString(com.nuvio.tv.R.string.player_source_unavailable_next),
            android.widget.Toast.LENGTH_SHORT
        ).show()
        _uiState.update {
            it.copy(
                error = null,
                showPauseOverlay = false,
                showLoadingOverlay = it.loadingOverlayEnabled,
                loadingMessage = context.getString(
                    com.nuvio.tv.R.string.player_dead_source_failover,
                    attemptNo,
                    MAX_DEAD_SOURCE_FAILOVERS
                ),
                pendingSeekPosition = failoverPendingSeek(
                    placeholderRejected = placeholderRejectedForStream,
                    livePositionMs = livePosition,
                    pendingSeekMs = it.pendingSeekPosition
                )
            )
        }
        switchToSourceStream(next, resetFailoverBudget = false)
    }
    return true
}

/**
 * The failover's next source from [startIdx]: not proven dead ([deadUrls]), not hidden by the
 * stream list limit ([hiddenKeys], by limitKey: auto never picks a hidden stream, Spec 51 D1), and
 * one the failover may switch to on its own ([isAutoFailoverCandidate]). Null when none is left.
 */
internal fun nextLiveSource(
    streams: List<Stream>,
    startIdx: Int,
    deadUrls: Set<String>,
    hiddenKeys: Set<String>,
    p2pEnabled: Boolean
): Stream? = (startIdx until streams.size).asSequence()
    .map { streams[it] }
    .firstOrNull { candidate ->
        val url = candidate.getStreamUrl()
        (url == null || url !in deadUrls) &&
            candidate.limitKey() !in hiddenKeys &&
            isAutoFailoverCandidate(candidate, p2pEnabled)
    }

/**
 * The next source's pending seek on a failover: the live playhead when there is one, else
 * the pending seek as it stands. After a placeholder reject (Spec 21 P3) the playhead is the
 * placeholder's, not the title's, so only the existing pending seek (a saved resume) carries.
 */
internal fun failoverPendingSeek(placeholderRejected: Boolean, livePositionMs: Long, pendingSeekMs: Long?): Long? =
    if (!placeholderRejected && livePositionMs > 0L) livePositionMs else pendingSeekMs

/**
 * Task 1.6 (Option A): a startup-phase failure that has exhausted the fallback
 * ladders and both same-URL auto-retries advances to the next source instead
 * of surfacing the error screen (previously: one-shot engine failover to MPV
 * when that toggle was enabled, else the error screen). A URL that could not
 * produce a first frame through every recovery path is treated like a dead
 * source for this session.
 */
internal fun PlayerRuntimeController.attemptStartupExhaustedSourceFailover(detailedError: String): Boolean {
    if (hasRenderedFirstFrame) return false
    Log.w(
        PlayerRuntimeController.TAG,
        "Startup recovery exhausted; attempting next-source failover for: $detailedError"
    )
    return advanceToNextLiveSource(detailedError)
}

/** Spec 21 P1: the latches [PlayerErrorRecoveryPolicy.route] reads, at the moment of the error. */
internal fun PlayerRuntimeController.errorRecoveryStreamState(): PlayerErrorRecoveryPolicy.StreamState =
    PlayerErrorRecoveryPolicy.StreamState(
        inBackground = isInBackground,
        mapDv7ToHevcActive = isMapDv7ToHevcActiveForCurrentPlayback,
        manualDv81Mode2Active = isManualDv81Mode2ActiveForCurrentPlayback,
        experimentalDv7ToDv81Active = isExperimentalDv7ToDv81ActiveForCurrentPlayback,
        dv7Mode1AlreadyForced = dv7Mode1ForcedStreamUrls.contains(currentStreamUrl),
        rangeNotSatisfiableRetried = hasRetriedCurrentStreamAfter416,
        renderedFirstFrame = hasRenderedFirstFrame,
    )

/**
 * Runs one `onPlayerError` rung; true when it took the error (a rebuild, retry or failover is
 * scheduled), false when a self-gated rung declined.
 */
internal fun PlayerRuntimeController.executeErrorRecoveryRung(
    rung: PlayerErrorRecoveryPolicy.Rung,
    error: PlaybackException,
    detailedError: String,
    positionMs: Long
): Boolean = when (rung) {
    PlayerErrorRecoveryPolicy.Rung.DV_MODE1_AFTER_DECODER_FAILURE,
    PlayerErrorRecoveryPolicy.Rung.DV_MODE1_AFTER_RUNTIME_CHECK -> {
        dv7Mode1ForcedStreamUrls.add(currentStreamUrl)
        val prefix = if (rung == PlayerErrorRecoveryPolicy.Rung.DV_MODE1_AFTER_DECODER_FAILURE) {
            "DV7_MODE2_PLAYBACK_FALLBACK: mode 2 decode failed; "
        } else {
            "DV7_MODE2_RUNTIME_CHECK_FALLBACK: mode 2 hit FAILED_RUNTIME_CHECK; "
        }
        Log.i(PlayerRuntimeController.TAG, prefix + "retrying stream at mode 1 host=${currentStreamUrl.safeHost()}")
        scheduleDeferredPlayerReinitialize("DV7 mode 1", positionMs, clearResumeProgress = true)
        true
    }
    PlayerErrorRecoveryPolicy.Rung.DV_HDR10_AFTER_DECODER_FAILURE,
    PlayerErrorRecoveryPolicy.Rung.DV_HDR10_AFTER_RUNTIME_CHECK -> {
        if (rung == PlayerErrorRecoveryPolicy.Rung.DV_HDR10_AFTER_RUNTIME_CHECK) {
            Log.i(
                PlayerRuntimeController.TAG,
                "DV_RUNTIME_CHECK_FALLBACK: FAILED_RUNTIME_CHECK with DV conversion active; " +
                    "forcing HDR10 base layer for host=${currentStreamUrl.safeHost()}"
            )
        }
        dv7ToHevcForcedStreamUrls.add(currentStreamUrl)
        scheduleDeferredPlayerReinitialize("DV HDR10 base layer", positionMs, clearResumeProgress = true)
        true
    }
    PlayerErrorRecoveryPolicy.Rung.AUDIO_LADDER_RENDERER_FAULT,
    PlayerErrorRecoveryPolicy.Rung.AUDIO_LADDER_AUDIO_TRACK,
    PlayerErrorRecoveryPolicy.Rung.AUDIO_LADDER_STUCK_NO_PROGRESS -> tryAudioRecoveryLadder(positionMs)
    PlayerErrorRecoveryPolicy.Rung.DENIED_AUDIO_FFMPEG -> tryDeniedAudioFfmpegFallback(error)
    PlayerErrorRecoveryPolicy.Rung.AUDIO_TRACK_PCM -> tryAudioTrackPcmFallback(error)
    PlayerErrorRecoveryPolicy.Rung.RANGE_416_FROM_START -> {
        retryCurrentStreamFromStartAfter416()
        true
    }
    PlayerErrorRecoveryPolicy.Rung.DEAD_SOURCE_HTTP_FAILOVER -> attemptDeadSourceFailover(error, detailedError)
    PlayerErrorRecoveryPolicy.Rung.PARSING_PROBE -> tryParsingErrorProbeFallback(
        error = error,
        detailedError = detailedError,
        savedPosition = positionMs,
        paused = userPausedManually
    )
    // Bounded by MAX_DEAD_SOURCE_FAILOVERS; false (=> auto-retry, then the error screen) once
    // the cap is reached or no live source remains.
    PlayerErrorRecoveryPolicy.Rung.MID_PLAY_CORRUPT_FAILOVER -> advanceToNextLiveSource(detailedError)
    PlayerErrorRecoveryPolicy.Rung.AUTO_RETRY -> attemptAutoRetry(error, detailedError)
    PlayerErrorRecoveryPolicy.Rung.STARTUP_EXHAUSTED_FAILOVER -> attemptStartupExhaustedSourceFailover(detailedError)
}

/**
 * nt6 fix B: consume one unit of the per-stream auto-recovery budget.
 *
 * Self-keys on the current stream URL — a genuine source switch resets the
 * counter without any external reset call; stable progress
 * (scheduleStableProgressReset) also zeroes it. Returns false once the budget is
 * spent, at which point onPlayerError skips every fallback ladder and surfaces
 * the error to the user instead of silently re-preparing again.
 */
internal fun PlayerRuntimeController.consumeAutoRecoveryBudget(detailedError: String): Boolean {
    val url = currentStreamUrl
    if (url != autoRecoveryBudgetUrl) {
        autoRecoveryBudgetUrl = url
        autoRecoveryCountForCurrentStream = 0
    }
    autoRecoveryCountForCurrentStream += 1
    if (autoRecoveryCountForCurrentStream > MAX_TOTAL_AUTO_RECOVERIES_PER_STREAM) {
        Log.w(
            PlayerRuntimeController.TAG,
            "Auto-recovery budget exhausted ($MAX_TOTAL_AUTO_RECOVERIES_PER_STREAM recoveries) " +
                "for host=${url.safeHost()}; surfacing error instead of retrying: $detailedError"
        )
        return false
    }
    return true
}
