package com.nuvio.tv.core.player

import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException

/**
 * The ExoPlayer `onPlayerError` recovery ladder as one ordered table (Spec 21 P1 / 11/S2a).
 *
 * [classify] reads the error, the controller snapshots its per-stream latches into [StreamState],
 * and [route] returns the rungs to try in order. The controller (`executeErrorRecoveryRung`,
 * PlayerRuntimeControllerErrorRecovery; the rung-to-function mapping has no harness, review guards
 * it) runs them until one takes the error, else it surfaces
 * the terminal error. Between [Route.Background] and the ladder the controller spends one unit of
 * the nt6 fix B budget and tries the P5 bitstream same-config retry; a spent budget skips the
 * whole ladder.
 *
 * Rungs gated here are decided from the snapshot. The self-gated rungs (audio ladder, denied
 * FFmpeg, PCM, dead source, parsing probe, mid-play and startup failover, auto-retry) keep their
 * own latches in their controller functions and may decline. The snapshot stays valid for the
 * whole walk: a rung that changes a snapshotted fact (mode-1 URL set, 416 latch) always takes the
 * error, and [route] ends the list at it; a rung that can decline must leave every [StreamState]
 * fact untouched.
 *
 * Spec 21 P8 (Q2 latch table) removed the inherited timeout, loader-NPE and MediaPeriodHolder
 * same-URL retries (string matches with no evidence; their latches reset on the rebuild they
 * triggered, so they repeated until the budget ran out and never reached the auto-retry or
 * next-source failover) and the Exo-ladder engine failover (MPV -> Exo only, so it always declined
 * on an Exo error). Those errors now take the generic rungs below.
 */
internal object PlayerErrorRecoveryPolicy {

    enum class Rung {
        DV_MODE1_AFTER_DECODER_FAILURE,
        DV_HDR10_AFTER_DECODER_FAILURE,
        DV_MODE1_AFTER_RUNTIME_CHECK,
        DV_HDR10_AFTER_RUNTIME_CHECK,
        AUDIO_LADDER_RENDERER_FAULT,
        DENIED_AUDIO_FFMPEG,
        AUDIO_TRACK_PCM,
        AUDIO_LADDER_AUDIO_TRACK,
        AUDIO_LADDER_STUCK_NO_PROGRESS,
        RANGE_416_FROM_START,
        DEAD_SOURCE_HTTP_FAILOVER,
        PARSING_PROBE,
        MID_PLAY_CORRUPT_FAILOVER,
        AUTO_RETRY,
        STARTUP_EXHAUSTED_FAILOVER,
    }

    /** What the error is, read once from the exception. */
    data class ErrorTraits(
        val retryable: Boolean,
        val dolbyVisionDecoderFailure: Boolean,
        /**
         * 8000 FAILED_RUNTIME_CHECK from a renderer/extractor assertion. Media3's stuck-buffering
         * watchdog shares the code but is a loading deadlock, not a decoder or audio fault (and
         * carries no renderer format, so it would read as an audio failure): it is excluded here,
         * skips the DV and audio rungs and takes the same-link auto-retry (Register A2 P2).
         */
        val runtimeCheckFault: Boolean,
        val decodingFailed: Boolean,
        /** The failing renderer's format is audio, or the error names no renderer format. */
        val audioRendererFailed: Boolean,
        val audioTrackFailure: Boolean,
        val stuckPlayingNoProgress: Boolean,
        /** HTTP 416 as the direct cause. */
        val rangeNotSatisfiable: Boolean,
        /** HTTP 404/410 anywhere in the cause chain. */
        val deadSourceHttp: Boolean,
        /** 3001 malformed container, or 2000 whose cause chain has no connection-level failure. */
        val containerCorruption: Boolean,
    )

    /** The controller's per-stream latches at the moment of the error. */
    data class StreamState(
        val inBackground: Boolean,
        val mapDv7ToHevcActive: Boolean,
        val manualDv81Mode2Active: Boolean,
        val experimentalDv7ToDv81Active: Boolean,
        val dv7Mode1AlreadyForced: Boolean,
        /** The 416 retry-from-start already ran for this URL (reset on a switch, not by its own rebuild). */
        val rangeNotSatisfiableRetried: Boolean,
        val renderedFirstFrame: Boolean,
    )

    sealed interface Route {
        /** Free the decoder and rebuild paused on resume; no ladder, no budget. */
        data object Background : Route

        data class Ladder(val rungs: List<Rung>) : Route
    }

    fun route(error: ErrorTraits, state: StreamState): Route {
        // A codec crash while backgrounded (another app reclaimed the hardware decoder): each
        // retry would re-acquire a decoder the foreground app reclaims again, burning the budget
        // into an unrecoverable 4003 by the time the user returns.
        if (state.inBackground && error.retryable) return Route.Background
        return Route.Ladder(ladder(error, state))
    }

    private fun ladder(error: ErrorTraits, state: StreamState): List<Rung> = buildList {
        if (!state.mapDv7ToHevcActive) {
            // DV decoder failure: manual Convert-to-DV8.1 mode 2 tries libdovi mode 1 once,
            // then HDR10.
            if (error.dolbyVisionDecoderFailure) {
                add(
                    if (state.manualDv81Mode2Active && !state.dv7Mode1AlreadyForced) {
                        Rung.DV_MODE1_AFTER_DECODER_FAILURE
                    } else {
                        Rung.DV_HDR10_AFTER_DECODER_FAILURE
                    }
                )
                return@buildList
            }
            // DV conversion armed and an 8000: the converted bitstream trips a renderer/extractor
            // assertion before the codec reports a decoding failure. A video-path failure, so the
            // DV ladder, not the audio fallbacks (they rebuild with the same broken conversion).
            if (error.runtimeCheckFault &&
                (state.experimentalDv7ToDv81Active || state.manualDv81Mode2Active)
            ) {
                add(
                    if (state.manualDv81Mode2Active && !state.dv7Mode1AlreadyForced) {
                        Rung.DV_MODE1_AFTER_RUNTIME_CHECK
                    } else {
                        Rung.DV_HDR10_AFTER_RUNTIME_CHECK
                    }
                )
                return@buildList
            }
        }
        // Audio review F5: the audio ladder only when the audio renderer failed; a video decoder
        // crash must not end in silent video. No autoSwitchInternalPlayerOnError gate: engine
        // failover is MPV->Exo only, so on Exo this is the only audio-decoder recovery.
        if ((error.decodingFailed || error.runtimeCheckFault) && error.audioRendererFailed) {
            add(Rung.AUDIO_LADDER_RENDERER_FAULT)
        }
        // F5 root cause: 4001 on a policy-denied audio format (hybrid MIME upgraded mid-stream)
        // rebuilds with FFmpeg audio preferred. Self-gated on 4001.
        add(Rung.DENIED_AUDIO_FFMPEG)
        // Audio review F6: the gentler PCM-forcing rebuild for a 5001 before the safe-audio
        // ladder. Self-gated (5001, first attempt, extension mode on, tunneling off).
        add(Rung.AUDIO_TRACK_PCM)
        // AudioTrack init (5001) or write (5002, e.g. DEAD_OBJECT on a passthrough track).
        if (error.audioTrackFailure) add(Rung.AUDIO_LADDER_AUDIO_TRACK)
        if (error.stuckPlayingNoProgress) add(Rung.AUDIO_LADDER_STUCK_NO_PROGRESS)
        if (error.rangeNotSatisfiable && !state.rangeNotSatisfiableRetried) {
            add(Rung.RANGE_416_FROM_START)
            return@buildList
        }
        // Dead-source HTTP arm (community 3003 report): 404/410 is permanent for this URL, so
        // advance now instead of burning same-URL retries.
        if (error.deadSourceHttp) add(Rung.DEAD_SOURCE_HTTP_FAILOVER)
        // 0.8.5 parsing-error probe: a mislabeled container is rescuable; its probe-null branch
        // holds the sniff dead-source arm (non-media body behind HTTP 200, .rar/.zip).
        add(Rung.PARSING_PROBE)
        // T2 (ysosrs cad15d29e): mid-play corruption will not recover on a same-URL re-prepare
        // (auto-retry cue-seeks back into it). PRDS chunk losses arrive as 2001/2002; a 2000 with
        // a connection-level cause is a lost connection and takes the auto-retry (Register A2 P3).
        if (state.renderedFirstFrame && error.containerCorruption) add(Rung.MID_PLAY_CORRUPT_FAILOVER)
        add(Rung.AUTO_RETRY)
        // Task 1.6: a startup failure that exhausted the ladders and both auto-retries tries the
        // next source before the error screen. Self-gated on no first frame.
        add(Rung.STARTUP_EXHAUSTED_FAILOVER)
    }

    fun classify(error: PlaybackException): ErrorTraits {
        val code = error.errorCode
        val chain = error.causeChainText()
        val rendererMime = (error as? ExoPlaybackException)?.rendererFormat?.sampleMimeType
        return ErrorTraits(
            retryable = isRetryablePlaybackError(error),
            dolbyVisionDecoderFailure = error.isDolbyVisionDecoderFailure(rendererMime, chain),
            runtimeCheckFault = code == PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK &&
                !isStuckBufferingWatchdog(code, chain),
            decodingFailed = code == PlaybackException.ERROR_CODE_DECODING_FAILED,
            audioRendererFailed = rendererMime == null || MimeTypes.isAudio(rendererMime),
            audioTrackFailure = isAudioTrackFailure(code, chain),
            stuckPlayingNoProgress = code == PlaybackException.ERROR_CODE_TIMEOUT &&
                chain.contains("stuck playing with no progress", ignoreCase = true),
            rangeNotSatisfiable = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode == 416,
            deadSourceHttp = isDeadSourceHttpError(error),
            containerCorruption = code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
                (code == PlaybackException.ERROR_CODE_IO_UNSPECIFIED && !PlaybackNetworkFailure.hasNetworkCause(error)),
        )
    }

    private fun PlaybackException.isDolbyVisionDecoderFailure(rendererMime: String?, chain: String): Boolean {
        // DV7 review F7: media3 surfaces failure-to-instantiate as ERROR_CODE_DECODER_INIT_FAILED
        // ("Decoder init failed..."), which the message match below never catches, so the
        // mode2 -> mode1 -> HDR10 ladder never fired on devices whose DV decoder exists but refuses
        // to start (the hidden/broken-licence Amlogic case DolbyVisionCodecFallback documents).
        // Match init failures by code + the failing format's DV MIME.
        if (errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED) {
            if (rendererMime?.contains("dolby-vision", ignoreCase = true) == true) return true
            // Init-failure messages name the codec; catch DV decoders that way too.
            val initDetails = (message ?: "") + ' ' + (cause?.message ?: "")
            return initDetails.contains("dvhe", ignoreCase = true) ||
                initDetails.contains("dvh1", ignoreCase = true) ||
                initDetails.contains("dolby", ignoreCase = true)
        }
        if (errorCode != PlaybackException.ERROR_CODE_DECODING_FAILED) return false
        return chain.contains("dolby-vision", ignoreCase = true) && chain.contains("decoder failed", ignoreCase = true)
    }
}
