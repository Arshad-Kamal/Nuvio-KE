package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import com.nuvio.tv.core.player.AudioOutputMode
import com.nuvio.tv.core.player.FrameRateSource
import com.nuvio.tv.data.local.FrameRateMatchingMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Stats panel rows added in Spec 26/26k + 26k2 (P12 colour, P14 decoder, P15 codec, P16 frame rate, P2P, sections). */
class StatsPanelRowsTest {

    @Test
    fun `codec strings read as profile and level`() {
        assertEquals("HEVC Main 10 L5.1", videoCodecProfileLabel("hvc1.2.4.L153.B0"))
        assertEquals("HEVC Main L4", videoCodecProfileLabel("hev1.1.6.L120.90"))
        assertEquals("H.264 High L4.1", videoCodecProfileLabel("avc1.640029"))
        assertEquals("dvhe.07.06", videoCodecProfileLabel("dvhe.07.06, hvc1.2.4.L153"))
        assertEquals("hvc1.x", videoCodecProfileLabel("hvc1.x"))
        assertNull(videoCodecProfileLabel(" "))
        assertNull(videoCodecProfileLabel(null))
    }

    @Test
    fun `frame rate row names what AFR did`() {
        fun row(mode: FrameRateMatchingMode = FrameRateMatchingMode.START, probe: Boolean = false,
                fps: Float = 23.976f, source: FrameRateSource? = FrameRateSource.TRACK, applied: Float? = 23.976f) =
            afrStatsRow(mode, probe, fps, source, applied).let { it.value to it.dot }
        assertEquals("AFR off" to StatsDot.NONE, row(mode = FrameRateMatchingMode.OFF))
        assertEquals("detecting…" to StatsDot.NONE, row(probe = true))
        assertEquals("not detected" to StatsDot.NONE, row(fps = 0f))
        assertEquals("matched · 23.976 fps · from container" to StatsDot.GOOD, row())
        assertEquals("matched · 23.976 fps · from probe" to StatsDot.GOOD, row(source = FrameRateSource.PROBE, applied = 47.952f))
        assertEquals("60 Hz · 23.976 fps · from container" to StatsDot.WARN, row(applied = 60f))
        assertEquals("no switch · 23.976 fps · from container" to StatsDot.NONE, row(applied = null))
    }

    @Test
    fun `torrent row waits for a size and names the file only in a pack`() {
        val size: (Long) -> String = { "${it / 1_000_000_000} GB" }
        assertNull(torrentStatsValue(0L, 1, 3, 0.5f, size))
        assertEquals("57 GB · file 1 of 3 · 12%", torrentStatsValue(57_900_000_000L, 1, 3, 0.125f, size))
        assertEquals("57 GB · 12%", torrentStatsValue(57_900_000_000L, 1, 1, 0.125f, size))
        assertEquals("57 GB · 12%", torrentStatsValue(57_900_000_000L, 0, 3, 0.125f, size))
    }

    @Test
    fun `row group overrides the label map and sections keep column order`() {
        val sections = buildStatsSections(
            listOf(
                StatsRow("Speed", "meas 300 Mbit/s"),
                StatsRow("Speed", "↓ 8 MB/s", group = StatsGroup.P2P),
                StatsRow("Output", "3840×2160 · 23.98 Hz"),
                StatsRow("Engine", "ExoPlayer"),
                StatsRow("Unknown", "x")
            )
        )
        assertEquals(
            listOf(StatsGroup.VIDEO, StatsGroup.DISPLAY, StatsGroup.NETWORK, StatsGroup.P2P, StatsGroup.SYSTEM),
            sections.map { it.group }
        )
        assertEquals("↓ 8 MB/s", sections.single { it.group == StatsGroup.P2P }.rows.single().value)
        assertEquals(listOf(0, 0, 1, 1, 1), sections.map { it.group.column })
    }

    @Test
    fun `colour row names space, transfer and range it knows`() {
        assertEquals(
            "BT.2020 · PQ · limited",
            colourLabel(C.COLOR_SPACE_BT2020, C.COLOR_TRANSFER_ST2084, C.COLOR_RANGE_LIMITED)
        )
        assertEquals("HLG", colourLabel(null, C.COLOR_TRANSFER_HLG, -1))
        assertNull(colourLabel(null, -1, null))
    }

    private fun staticInfo(maxNits: Int, cll: Int, fall: Int): ByteArray {
        val buffer = ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0.toByte())
        repeat(8) { buffer.putShort(25_000.toShort()) }
        buffer.putShort(maxNits.toShort()).putShort(0).putShort(cll.toShort()).putShort(fall.toShort())
        return buffer.array()
    }

    @Test
    fun `mastering row reads CTA-861 static info and flags the MKV default pair`() {
        assertEquals("1000 nits · CLL 942 · FALL 318", masteringLabel(staticInfo(1000, 942, 318)))
        assertEquals("4000 nits · CLL/FALL unknown", masteringLabel(staticInfo(4000, 1000, 200)))
        assertEquals("1000 nits", masteringLabel(staticInfo(1000, 0, 0)))
        assertEquals("40000 nits · CLL 942 · FALL 318", masteringLabel(staticInfo(40000, 942, 318)))
        assertNull(masteringLabel(ByteArray(10)))
        assertNull(masteringLabel(null))
    }

    @Test
    fun `decoder row marks software codecs by Media3's name rule`() {
        assertEquals("c2.nvidia.hevc.decoder · HW", decoderLabel("c2.nvidia.hevc.decoder"))
        assertEquals("OMX.amlogic.hevc.decoder.awesome2 · HW", decoderLabel("OMX.amlogic.hevc.decoder.awesome2"))
        assertEquals("c2.android.hevc.decoder · SW", decoderLabel("c2.android.hevc.decoder"))
        assertEquals("ffmpegLib-vc1 · SW", decoderLabel("ffmpegLib-vc1"))
    }

    @Test
    fun `audio decoder row says none under passthrough`() {
        assertEquals("none (passthrough)", audioDecoderValue(AudioOutputMode.BITSTREAM, "c2.android.raw.decoder"))
        assertEquals("none (passthrough)", audioDecoderValue(AudioOutputMode.MAT, null))
        assertEquals("ffmpegLib-truehd", audioDecoderValue(AudioOutputMode.PCM, "ffmpegLib-truehd"))
        assertNull(audioDecoderValue(null, null))
    }
}
