package com.nuvio.tv.core.player.autosync

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedSubtitleTrackDetectionTest {
    private fun element(id: Long, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val idBytes = generateSequence(id) { it ushr 8 }.takeWhile { it > 0 }.map { (it and 0xFF).toByte() }
            .toList().reversed()
        idBytes.forEach { out.write(it.toInt()) }
        out.write(0x01)
        for (shift in 48 downTo 0 step 8) out.write(((payload.size.toLong() ushr shift) and 0xFF).toInt())
        out.write(payload)
        return out.toByteArray()
    }

    private fun element(id: Long, vararg children: ByteArray) =
        element(id, ByteArrayOutputStream().apply { children.forEach(::write) }.toByteArray())

    private fun uint(id: Long, value: Long): ByteArray {
        val bytes = generateSequence(value) { it ushr 8 }.takeWhile { it > 0 }.map { (it and 0xFF).toByte() }
            .toList().reversed().toByteArray()
        return element(id, if (bytes.isEmpty()) byteArrayOf(0) else bytes)
    }

    private fun text(id: Long, value: String) = element(id, value.toByteArray())

    private fun track(number: Long, type: Long, codec: String, language: String = "eng") = element(
        0xAE,
        uint(0xD7, number),
        uint(0x83, type),
        text(0x86, codec),
        text(0x22B59C, language),
    )

    private fun cues(trackNumber: Long, count: Int, durationTicks: Long = 2_000) = element(
        0x1C53BB6B,
        *Array(count) { index ->
            element(
                0xBB,
                uint(0xB3, 10_000L + index * 4_000L),
                element(0xB7, uint(0xF7, trackNumber), uint(0xF1, 0), uint(0xB2, durationTicks)),
            )
        },
    )

    private fun matroska(vararg segmentChildren: ByteArray) =
        element(0x1A45DFA3, text(0x4282, "matroska")) + element(0x18538067, *segmentChildren)

    private val info = element(0x1549A966, uint(0x2AD7B1, 1_000_000))
    private val videoAndAudio = arrayOf(track(1, 1, "V_MPEGH/ISO/HEVC"), track(2, 2, "A_EAC3"))

    private fun serve(
        file: ByteArray,
        refuseFrom: Long = Long.MAX_VALUE,
        slowRequests: Int = 0,
        block: suspend (String) -> Unit,
    ) {
        val slowLeft = AtomicInteger(slowRequests)
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val range = request.getHeader("Range")!!.removePrefix("bytes=").split('-')
                    val start = range[0].toLong()
                    if (start >= refuseFrom) return MockResponse().setResponseCode(503)
                    val end = minOf(range[1].toLong(), file.size - 1L)
                    return MockResponse()
                        .setResponseCode(206)
                        .setHeader("Content-Range", "bytes $start-$end/${file.size}")
                        .setBody(Buffer().write(file.copyOfRange(start.toInt(), end.toInt() + 1)))
                        .apply { if (slowLeft.getAndDecrement() > 0) throttleBody(16, 1, TimeUnit.SECONDS) }
                }
            }
            runBlocking { block(server.url("/${System.nanoTime()}.mkv").toString()) }
        }
    }

    @Test fun `a PGS track is found and gives its timing`() = serve(
        matroska(info, element(0x1654AE6B, *videoAndAudio, track(3, 17, "S_HDMV/PGS")), cues(3, 20))
    ) { url ->
        val timeline = EmbeddedSubtitleTimelineLoader.load(url)
        assertNotNull(timeline)
        assertFalse(timeline!!.noSubtitleTracks)
        assertEquals(1, timeline.tracks.size)
        assertEquals(20, timeline.tracks.single().cues.size)
    }

    @Test fun `an SRT track is found and gives its timing`() = serve(
        matroska(info, element(0x1654AE6B, *videoAndAudio, track(3, 17, "S_TEXT/UTF8")), cues(3, 20))
    ) { url ->
        val timeline = EmbeddedSubtitleTimelineLoader.load(url)
        assertEquals(20, timeline!!.tracks.single().cues.size)
    }

    @Test fun `a cue duration that would overflow its end time is estimated instead`() = serve(
        matroska(
            info,
            element(0x1654AE6B, *videoAndAudio, track(3, 17, "S_TEXT/UTF8")),
            cues(3, 20, durationTicks = Long.MAX_VALUE),
        )
    ) { url ->
        val cues = EmbeddedSubtitleTimelineLoader.load(url)!!.tracks.single().cues
        assertEquals(20, cues.size)
        assertTrue(cues.all { it.endTimeMs > it.startTimeMs })
    }

    @Test fun `a crafted index cannot list more than the per-track cue cap`() = serve(
        matroska(info, element(0x1654AE6B, *videoAndAudio, track(3, 17, "S_TEXT/UTF8")), cues(3, 20_005))
    ) { url ->
        assertEquals(20_000, EmbeddedSubtitleTimelineLoader.load(url)!!.tracks.single().cues.size)
    }

    private fun box(type: String, payload: ByteArray = ByteArray(0)): ByteArray {
        val size = 8 + payload.size
        return byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()) +
            type.toByteArray() + payload
    }

    @Test fun `a crafted MP4 nesting boxes deeper than real files is rejected without overflowing the stack`() {
        val levels = 60_000
        val nested = ByteArray(levels * 8)
        for (level in 0 until levels) {
            val size = (levels - level) * 8
            val at = level * 8
            nested[at] = (size ushr 24).toByte()
            nested[at + 1] = (size ushr 16).toByte()
            nested[at + 2] = (size ushr 8).toByte()
            nested[at + 3] = size.toByte()
            "mdia".toByteArray().copyInto(nested, at + 4)
        }
        val hdlr = box("hdlr", ByteArray(8) + "text".toByteArray() + ByteArray(12))
        val file = box("ftyp", "isom".toByteArray() + ByteArray(4)) +
            box("moov", box("trak", box("mdia", hdlr + nested)))
        serve(file) { url -> assertNull(EmbeddedSubtitleTimelineLoader.load(url)) }
    }

    @Test fun `a video without subtitle tracks says so`() = serve(
        matroska(info, element(0x1654AE6B, *videoAndAudio), cues(1, 20))
    ) { url ->
        val timeline = EmbeddedSubtitleTimelineLoader.load(url)
        assertTrue(timeline!!.noSubtitleTracks)
    }

    @Test fun `a track list that could not be read is not taken for no subtitles`() {
        val tracks = element(0x1654AE6B, *videoAndAudio, track(3, 17, "S_HDMV/PGS"))
        val cueIndex = cues(3, 20)
        val void = element(0xEC, ByteArray(600 * 1024))
        fun position(value: Long) = element(0x53AC, ByteArray(8) { ((value ushr (56 - it * 8)) and 0xFF).toByte() })
        fun seek(id: ByteArray, value: Long) = element(0x4DBB, element(0x53AB, id), position(value))
        fun seekHead(tracksPosition: Long) = element(
            0x114D9B74,
            seek(byteArrayOf(0x16, 0x54, 0xAE.toByte(), 0x6B), tracksPosition),
            seek(byteArrayOf(0x1C, 0x53, 0xBB.toByte(), 0x6B), tracksPosition + tracks.size),
        )
        val tracksPosition = (seekHead(0).size + info.size + void.size).toLong()
        val file = matroska(seekHead(tracksPosition), info, void, tracks, cueIndex)
        val tracksAbsolute = file.size - tracks.size - cueIndex.size

        serve(file, refuseFrom = tracksAbsolute.toLong()) { url ->
            val timeline = EmbeddedSubtitleTimelineLoader.load(url)
            assertTrue(timeline == null || !timeline.noSubtitleTracks)
        }
        serve(file) { url ->
            val timeline = EmbeddedSubtitleTimelineLoader.load(url)
            assertEquals(1, timeline!!.tracks.size)
        }
    }

    @Test fun `a request that runs past its time limit ends as a miss and is tried again next time`() = serve(
        matroska(info, element(0x1654AE6B, *videoAndAudio, track(3, 17, "S_HDMV/PGS")), cues(3, 20)),
        slowRequests = 1,
    ) { url ->
        assertNull(EmbeddedSubtitleTimelineLoader.load(url))
        assertEquals(1, EmbeddedSubtitleTimelineLoader.load(url)!!.tracks.size)
    }
}
