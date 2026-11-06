package com.nuvio.tv.core.stream

import com.nuvio.tv.domain.model.AioStreamData
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.model.StreamClientResolve
import com.nuvio.tv.domain.model.StreamClientResolveRaw
import com.nuvio.tv.domain.model.StreamClientResolveStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Spec 51 P1: the bitrate estimate (size rule D5, length check, ceiling). */
class StreamBitrateEstimateTest {

    private fun stream(
        title: String? = null,
        structuredSize: Long? = null,
        durationMs: Long? = null,
        filename: String? = null,
        aioFilename: String? = null,
        resolveFilename: String? = null,
        rawFilename: String? = null,
        rawSize: Long? = null,
        videoSize: Long? = null
    ) = Stream(
        name = "Addon",
        title = title,
        description = null,
        url = "https://download.real-debrid.com/d/abc/file.mkv",
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null, bingeGroup = null, countryWhitelist = null, proxyHeaders = null,
            videoSize = videoSize, filename = filename
        ),
        addonName = "Addon",
        addonLogo = null,
        clientResolve = if (resolveFilename != null || rawFilename != null || rawSize != null) {
            StreamClientResolve(
                type = "debrid", infoHash = null, fileIdx = null, sources = null, torrentName = null,
                filename = resolveFilename, mediaType = null, title = null, season = null, episode = null,
                service = null,
                stream = StreamClientResolveStream(
                    StreamClientResolveRaw(torrentName = null, filename = rawFilename, size = rawSize, network = null, parsed = null)
                )
            )
        } else {
            null
        },
        streamData = if (structuredSize != null || durationMs != null || aioFilename != null) {
            AioStreamData(size = structuredSize, filename = aioFilename, cached = null, parsedFile = null, durationMs = durationMs)
        } else {
            null
        }
    )

    private fun mbps(sizeBytes: Long, seconds: Double) = sizeBytes * 8.0 / seconds / 1_000_000.0

    @Test
    fun `every structured size counts over the list runtime`() {
        val expected = mbps(13_000_000_000L, 3000.0)
        assertEquals(expected, estimatedStreamMbps(stream(structuredSize = 13_000_000_000L), 50, episode = true)!!, 1e-9)
        assertEquals(expected, estimatedStreamMbps(stream(rawSize = 13_000_000_000L), 50, episode = true)!!, 1e-9)
        assertEquals(expected, estimatedStreamMbps(stream(videoSize = 13_000_000_000L), 50, episode = true)!!, 1e-9)
    }

    @Test
    fun `a text size reads in decimal units and counts for a film, for an episode only with one episode's file`() {
        val text = "Show 1080p\n💾 1.5 GB"
        val decimal = 1_500_000_000L
        assertEquals(mbps(decimal, 2700.0), estimatedStreamMbps(stream(title = text), 45, episode = false)!!, 1e-9)
        assertNull(estimatedStreamMbps(stream(title = text), 45, episode = true))

        val single = stream(title = text, filename = "Breaking.Bad.S02E03.1080p.BluRay.x265-RARBG.mkv")
        assertEquals(mbps(decimal, 2700.0), estimatedStreamMbps(single, 45, episode = true)!!, 1e-9)
        assertNull(estimatedStreamMbps(stream(title = text, filename = "Show.S02.1080p.BluRay"), 45, episode = true))
    }

    @Test
    fun `an episode file named as several episodes counts no size, even a structured one`() {
        val double = stream(structuredSize = 4_000_000_000L, filename = "Show.S01E01E02.1080p.mkv")
        assertNull(estimatedStreamMbps(double, 45, episode = true))
        assertEquals(mbps(4_000_000_000L, 2700.0), estimatedStreamMbps(double, 45, episode = false)!!, 1e-9)
    }

    @Test
    fun `episode file kinds`() {
        fun kind(file: String) = episodeFileKind(stream(filename = file))
        assertEquals(EpisodeFileKind.SINGLE, kind("Show.S02E03.1080p.mkv"))
        assertEquals(EpisodeFileKind.SINGLE, kind("show s2 e3 720p.mkv"))
        assertEquals(EpisodeFileKind.SINGLE, kind("Show.S02E03.Episode.Title.mkv"))
        assertEquals(EpisodeFileKind.MULTI, kind("Show.S02E03E04.1080p.mkv"))
        assertEquals(EpisodeFileKind.MULTI, kind("Show.S02E03-04.1080p.mkv"))
        assertEquals(EpisodeFileKind.MULTI, kind("Show.S02E03-E04.1080p.mkv"))
        assertEquals(EpisodeFileKind.MULTI, kind("Show.S02E03 - E04.1080p.mkv"))
        assertEquals(EpisodeFileKind.MULTI, kind("Show.S02E03.E04.1080p.mkv"))
        assertEquals(EpisodeFileKind.MULTI, kind("Show.S01E01-S01E02.mkv"))
        assertEquals(EpisodeFileKind.MULTI, kind("Show.S01E01.S01E02.mkv"))
        // A hyphen then a digit reads as a range on purpose: the size is then only unknown.
        assertEquals(EpisodeFileKind.MULTI, kind("Show.S02E03-720p.mkv"))
        assertNull(kind("Show.S01-S05.Complete.1080p"))
        assertNull(kind("PS02E03.mkv"))
    }

    @Test
    fun `every file name the stream sends is read`() {
        assertEquals(EpisodeFileKind.SINGLE, episodeFileKind(stream(aioFilename = "Show.S02E03.mkv")))
        assertEquals(EpisodeFileKind.SINGLE, episodeFileKind(stream(resolveFilename = "Show.S02E03.mkv")))
        assertEquals(EpisodeFileKind.SINGLE, episodeFileKind(stream(rawFilename = "Show.S02E03.mkv")))
        assertEquals(
            EpisodeFileKind.MULTI,
            episodeFileKind(stream(filename = "Show.S02E03.mkv", rawFilename = "Show.S02E03E04.mkv"))
        )
    }

    @Test
    fun `the file's own length is used unless under half the runtime`() {
        val size = 13_100_000_000L
        val inside = stream(structuredSize = size, durationMs = 2_828_583L)
        assertEquals(mbps(size, 2828.583), estimatedStreamMbps(inside, 47, episode = true)!!, 1e-9)

        val tooShort = stream(structuredSize = size, durationMs = 10 * 60_000L)
        assertEquals(mbps(size, 47 * 60.0), estimatedStreamMbps(tooShort, 47, episode = true)!!, 1e-9)
        val half = stream(structuredSize = size, durationMs = 30 * 60_000L)
        assertEquals(mbps(size, 30 * 60.0), estimatedStreamMbps(half, 60, episode = true)!!, 1e-9)
        // Longer than the runtime (a double episode, a wrong runtime): its own length, the lower estimate.
        val long = stream(structuredSize = size, durationMs = 95 * 60_000L)
        assertEquals(mbps(size, 95 * 60.0), estimatedStreamMbps(long, 45, episode = true)!!, 1e-9)

        // No list runtime yet: the file's length alone.
        assertEquals(mbps(size, 2828.583), estimatedStreamMbps(inside, null, episode = true)!!, 1e-9)
    }

    @Test
    fun `unknown size or length is null`() {
        assertNull(estimatedStreamMbps(stream(structuredSize = 13_000_000_000L), runtimeMinutes = null, episode = false))
        assertNull(estimatedStreamMbps(stream(structuredSize = 13_000_000_000L), runtimeMinutes = 0, episode = false))
        assertNull(estimatedStreamMbps(stream(), runtimeMinutes = 45, episode = false))
    }

    @Test
    fun `an estimate over the ceiling is null`() {
        // 67.5 GB over 60 min = 150 Mbit/s exactly: kept; 100 GB = 222: a wrong size or length.
        assertEquals(150.0, estimatedStreamMbps(stream(structuredSize = 67_500_000_000L), 60, episode = false)!!, 1e-9)
        assertNull(estimatedStreamMbps(stream(structuredSize = 100_000_000_000L), 60, episode = false))
    }
}
