package com.nuvio.tv.core.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import com.nuvio.tv.core.player.PlayerErrorRecoveryPolicy.ErrorTraits
import com.nuvio.tv.core.player.PlayerErrorRecoveryPolicy.Route
import com.nuvio.tv.core.player.PlayerErrorRecoveryPolicy.Rung
import com.nuvio.tv.core.player.PlayerErrorRecoveryPolicy.StreamState
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * Spec 21 P1: the golden route table for `onPlayerError` (rung order as it stood inline in
 * PlayerRuntimeControllerInitialization before the extraction) plus the error classification.
 */
class PlayerErrorRecoveryPolicyTest {

    private val plain = ErrorTraits(
        retryable = false,
        dolbyVisionDecoderFailure = false,
        runtimeCheckFault = false,
        decodingFailed = false,
        audioRendererFailed = false,
        audioTrackFailure = false,
        stuckPlayingNoProgress = false,
        rangeNotSatisfiable = false,
        deadSourceHttp = false,
        containerCorruption = false,
    )

    private val fresh = StreamState(
        inBackground = false,
        mapDv7ToHevcActive = false,
        manualDv81Mode2Active = false,
        experimentalDv7ToDv81Active = false,
        dv7Mode1AlreadyForced = false,
        rangeNotSatisfiableRetried = false,
        renderedFirstFrame = false,
    )

    private val audioHead = listOf(Rung.DENIED_AUDIO_FFMPEG, Rung.AUDIO_TRACK_PCM)
    private val sourceTail = listOf(Rung.PARSING_PROBE, Rung.AUTO_RETRY, Rung.STARTUP_EXHAUSTED_FAILOVER)
    private val defaultLadder = audioHead + sourceTail

    private data class Row(val name: String, val error: ErrorTraits, val state: StreamState, val expected: Route)

    private fun ladder(vararg rungs: Rung) = Route.Ladder(rungs.toList())
    private fun ladder(rungs: List<Rung>) = Route.Ladder(rungs)

    private val dvConversion = fresh.copy(experimentalDv7ToDv81Active = true)
    private val mode2 = fresh.copy(manualDv81Mode2Active = true)
    private val dvVideoFailure = plain.copy(dolbyVisionDecoderFailure = true, decodingFailed = true, retryable = true)

    // A message-matched DV failure or an 8000 carries no renderer format, so it also reads as an
    // audio-renderer failure: these rows pin DV precedence over AUDIO_LADDER_RENDERER_FAULT.
    private val dvNoFormat = dvVideoFailure.copy(audioRendererFailed = true)
    private val runtimeCheck = plain.copy(runtimeCheckFault = true, audioRendererFailed = true)

    private val table: List<Row> = listOf(
        // Background: only a retryable error defers; nothing else runs.
        Row("background retryable", plain.copy(retryable = true), fresh.copy(inBackground = true), Route.Background),
        Row("background fatal", plain, fresh.copy(inBackground = true), ladder(defaultLadder)),
        Row("foreground retryable", plain.copy(retryable = true), fresh, ladder(defaultLadder)),
        // DV decoder failure: mode 2 -> mode 1 once, else HDR10; nothing after it.
        Row("dv decoder", dvVideoFailure, fresh, ladder(Rung.DV_HDR10_AFTER_DECODER_FAILURE)),
        Row("dv decoder mode2", dvVideoFailure, mode2, ladder(Rung.DV_MODE1_AFTER_DECODER_FAILURE)),
        Row("dv decoder without renderer format", dvNoFormat, fresh, ladder(Rung.DV_HDR10_AFTER_DECODER_FAILURE)),
        Row("dv decoder without renderer format mode2", dvNoFormat, mode2, ladder(Rung.DV_MODE1_AFTER_DECODER_FAILURE)),
        Row(
            "dv decoder mode2 mode1 spent", dvVideoFailure, mode2.copy(dv7Mode1AlreadyForced = true),
            ladder(Rung.DV_HDR10_AFTER_DECODER_FAILURE)
        ),
        Row(
            "dv decoder wins over audio track", dvVideoFailure.copy(audioTrackFailure = true), fresh,
            ladder(Rung.DV_HDR10_AFTER_DECODER_FAILURE)
        ),
        Row("dv decoder under map-to-HEVC", dvVideoFailure, fresh.copy(mapDv7ToHevcActive = true), ladder(defaultLadder)),
        // 8000 with DV conversion armed: the DV ladder, not the audio one.
        Row("runtime check dv conversion", runtimeCheck, dvConversion, ladder(Rung.DV_HDR10_AFTER_RUNTIME_CHECK)),
        Row("runtime check mode2", runtimeCheck, mode2, ladder(Rung.DV_MODE1_AFTER_RUNTIME_CHECK)),
        Row(
            "runtime check mode2 mode1 spent", runtimeCheck, mode2.copy(dv7Mode1AlreadyForced = true),
            ladder(Rung.DV_HDR10_AFTER_RUNTIME_CHECK)
        ),
        Row(
            "runtime check no conversion", runtimeCheck, fresh,
            ladder(listOf(Rung.AUDIO_LADDER_RENDERER_FAULT) + defaultLadder)
        ),
        Row(
            "runtime check under map-to-HEVC", runtimeCheck,
            dvConversion.copy(mapDv7ToHevcActive = true),
            ladder(listOf(Rung.AUDIO_LADDER_RENDERER_FAULT) + defaultLadder)
        ),
        // Audio review F5: a video decoder crash skips the audio ladder.
        Row("video decoding failed", plain.copy(decodingFailed = true, retryable = true), fresh, ladder(defaultLadder)),
        Row(
            "audio decoding failed", plain.copy(decodingFailed = true, audioRendererFailed = true, retryable = true), fresh,
            ladder(listOf(Rung.AUDIO_LADDER_RENDERER_FAULT) + defaultLadder)
        ),
        Row("audio renderer, other code", plain.copy(audioRendererFailed = true), fresh, ladder(defaultLadder)),
        Row(
            "audio track failure", plain.copy(audioTrackFailure = true, audioRendererFailed = true), fresh,
            ladder(audioHead + Rung.AUDIO_LADDER_AUDIO_TRACK + sourceTail)
        ),
        Row(
            "stuck playing", plain.copy(stuckPlayingNoProgress = true, audioRendererFailed = true), fresh,
            ladder(audioHead + Rung.AUDIO_LADDER_STUCK_NO_PROGRESS + sourceTail)
        ),
        // The one same-URL retry with a one-shot latch ends the list.
        Row("416", plain.copy(rangeNotSatisfiable = true, retryable = true), fresh, ladder(audioHead + Rung.RANGE_416_FROM_START)),
        Row(
            "416 spent", plain.copy(rangeNotSatisfiable = true, retryable = true), fresh.copy(rangeNotSatisfiableRetried = true),
            ladder(defaultLadder)
        ),
        // Source-level rungs.
        Row(
            "dead source http", plain.copy(deadSourceHttp = true), fresh,
            ladder(audioHead + Rung.DEAD_SOURCE_HTTP_FAILOVER + sourceTail)
        ),
        Row("corruption before first frame", plain.copy(containerCorruption = true, retryable = true), fresh, ladder(defaultLadder)),
        Row(
            "corruption mid-play", plain.copy(containerCorruption = true, retryable = true), fresh.copy(renderedFirstFrame = true),
            ladder(
                audioHead + listOf(
                    Rung.PARSING_PROBE, Rung.MID_PLAY_CORRUPT_FAILOVER,
                    Rung.AUTO_RETRY, Rung.STARTUP_EXHAUSTED_FAILOVER,
                )
            )
        ),
        Row(
            "dead source and mid-play corruption", plain.copy(deadSourceHttp = true, containerCorruption = true),
            fresh.copy(renderedFirstFrame = true),
            ladder(
                audioHead + listOf(
                    Rung.DEAD_SOURCE_HTTP_FAILOVER, Rung.PARSING_PROBE,
                    Rung.MID_PLAY_CORRUPT_FAILOVER, Rung.AUTO_RETRY, Rung.STARTUP_EXHAUSTED_FAILOVER,
                )
            )
        ),
    )

    @Test
    fun `golden route table`() {
        table.forEach { row ->
            assertEquals(row.name, row.expected, PlayerErrorRecoveryPolicy.route(row.error, row.state))
        }
    }

    @Test
    fun `the table reaches every rung`() {
        val reached = table.flatMap { (it.expected as? Route.Ladder)?.rungs.orEmpty() }.toSet()
        assertEquals(Rung.entries.toSet(), reached)
    }

    @Test
    fun `a rung that changes a snapshotted fact ends the list`() {
        // The snapshot contract in the policy KDoc: these rungs mutate route inputs, so nothing may follow them.
        val mutating = setOf(
            Rung.DV_MODE1_AFTER_DECODER_FAILURE, Rung.DV_MODE1_AFTER_RUNTIME_CHECK,
            Rung.RANGE_416_FROM_START,
        )
        table.mapNotNull { it.expected as? Route.Ladder }.forEach { route ->
            route.rungs.dropLast(1).forEach { assertFalse("$it before the end of ${route.rungs}", it in mutating) }
        }
    }

    // --- classify ---

    private val spec: DataSpec = DataSpec.Builder().setUri(mockk<Uri>(relaxed = true)).build()

    private fun http(code: Int) =
        HttpDataSource.InvalidResponseCodeException(code, null, null, emptyMap(), spec, byteArrayOf())

    private fun renderer(mime: String, code: Int, cause: Throwable = IllegalStateException("codec")) =
        ExoPlaybackException.createForRenderer(
            cause, "renderer", 0, Format.Builder().setSampleMimeType(mime).build(),
            C.FORMAT_HANDLED, null, false, code
        )

    private fun classify(error: PlaybackException) = PlayerErrorRecoveryPolicy.classify(error)

    @Test
    fun `DV decoder failure by message, by init MIME and by init codec name`() {
        assertTrue(
            classify(
                PlaybackException(
                    "Decoder failed: c2.dolby-vision.decoder", null, PlaybackException.ERROR_CODE_DECODING_FAILED
                )
            ).dolbyVisionDecoderFailure
        )
        assertTrue(
            classify(renderer(MimeTypes.VIDEO_DOLBY_VISION, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
                .dolbyVisionDecoderFailure
        )
        assertTrue(
            classify(
                PlaybackException("Decoder init failed: dvhe.08", null, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)
            ).dolbyVisionDecoderFailure
        )
        assertFalse(
            classify(renderer(MimeTypes.VIDEO_H265, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
                .dolbyVisionDecoderFailure
        )
        // DECODING_FAILED needs both phrases; another code never matches by message.
        assertFalse(
            classify(PlaybackException("c2.dolby-vision.decoder", null, PlaybackException.ERROR_CODE_DECODING_FAILED))
                .dolbyVisionDecoderFailure
        )
        assertFalse(
            classify(PlaybackException("Decoder failed: c2.hevc.decoder", null, PlaybackException.ERROR_CODE_DECODING_FAILED))
                .dolbyVisionDecoderFailure
        )
        assertFalse(
            classify(PlaybackException("Decoder failed: c2.dolby-vision", null, PlaybackException.ERROR_CODE_UNSPECIFIED))
                .dolbyVisionDecoderFailure
        )
    }

    @Test
    fun `audio renderer failure reads the renderer format, absent format counts as audio`() {
        val video = classify(renderer(MimeTypes.VIDEO_H265, PlaybackException.ERROR_CODE_DECODING_FAILED))
        assertTrue(video.decodingFailed)
        assertFalse(video.audioRendererFailed)
        assertTrue(classify(renderer(MimeTypes.AUDIO_TRUEHD, PlaybackException.ERROR_CODE_DECODING_FAILED)).audioRendererFailed)
        assertTrue(
            classify(PlaybackException("x", null, PlaybackException.ERROR_CODE_DECODING_FAILED)).audioRendererFailed
        )
    }

    @Test
    fun `runtime check fault excludes the stuck-buffering watchdog`() {
        val code = PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK
        assertTrue(classify(PlaybackException("x", IllegalStateException("assertion"), code)).runtimeCheckFault)
        assertFalse(
            classify(PlaybackException("x", IllegalStateException("Playback stuck buffering and not loading"), code))
                .runtimeCheckFault
        )
    }

    @Test
    fun `audio track and stuck-playing classifications`() {
        assertTrue(classify(PlaybackException("x", null, PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED)).audioTrackFailure)
        assertTrue(
            classify(PlaybackException("x", IllegalStateException("Playback stuck playing with no progress"), PlaybackException.ERROR_CODE_TIMEOUT))
                .stuckPlayingNoProgress
        )
        assertFalse(
            classify(PlaybackException("x", IllegalStateException("Playback stuck playing with no progress"), PlaybackException.ERROR_CODE_UNSPECIFIED))
                .stuckPlayingNoProgress
        )
    }

    @Test
    fun `timeout, loader NPE and MediaPeriodHolder errors take the generic rungs (P8)`() {
        val io = PlaybackException.ERROR_CODE_IO_UNSPECIFIED
        fun routeOf(error: PlaybackException, state: StreamState) =
            PlayerErrorRecoveryPolicy.route(classify(error), state)
        // A socket timeout (2002): no same-URL timeout retry ahead of the auto-retry and failover.
        val timeout = PlaybackException(
            "x", IOException("read", SocketTimeoutException("t")), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        )
        assertEquals(ladder(defaultLadder), routeOf(timeout, fresh))
        // Mid-play too: a timeout is a network fault, not corruption (AUTO_RETRY, not a source switch).
        assertEquals(ladder(defaultLadder), routeOf(timeout, fresh.copy(renderedFirstFrame = true)))
        val timeoutUnder2000 = PlaybackException("x", IOException("read", SocketTimeoutException("t")), io)
        assertFalse(classify(timeoutUnder2000).containerCorruption)
        // A Matroska NPE (2000, no connection cause) mid-play is container corruption: next source.
        val npe = PlaybackException("x", IllegalStateException("NullPointerException in MatroskaExtractor.readBlock"), io)
        assertEquals(
            ladder(audioHead + listOf(Rung.PARSING_PROBE, Rung.MID_PLAY_CORRUPT_FAILOVER) + Rung.AUTO_RETRY + Rung.STARTUP_EXHAUSTED_FAILOVER),
            routeOf(npe, fresh.copy(renderedFirstFrame = true))
        )
        assertEquals(ladder(defaultLadder), routeOf(npe, fresh))
        val mediaPeriodHolder = PlaybackException(
            "x", IllegalStateException("MediaPeriodHolder.info is null"), PlaybackException.ERROR_CODE_UNSPECIFIED
        )
        assertEquals(ladder(defaultLadder), routeOf(mediaPeriodHolder, fresh))
    }

    @Test
    fun `416 is read from the direct cause, 404 and 410 from the whole chain`() {
        val bad = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        assertTrue(classify(PlaybackException("x", http(416), bad)).rangeNotSatisfiable)
        assertFalse(classify(PlaybackException("x", IOException("wrap", http(416)), bad)).rangeNotSatisfiable)
        assertTrue(classify(PlaybackException("x", IOException("wrap", http(404)), bad)).deadSourceHttp)
        assertTrue(classify(PlaybackException("x", http(410), bad)).deadSourceHttp)
        assertFalse(classify(PlaybackException("x", http(429), bad)).deadSourceHttp)
    }

    @Test
    fun `container corruption is 3001, or 2000 without a connection-level cause`() {
        assertTrue(
            classify(PlaybackException("x", null, PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)).containerCorruption
        )
        val io = PlaybackException.ERROR_CODE_IO_UNSPECIFIED
        assertTrue(classify(PlaybackException("x", IOException("short chunk"), io)).containerCorruption)
        assertFalse(classify(PlaybackException("x", SocketException("reset"), io)).containerCorruption)
    }
}
