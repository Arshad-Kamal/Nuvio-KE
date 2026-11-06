package com.nuvio.tv.data.mapper

import com.nuvio.tv.core.di.NetworkModule
import com.nuvio.tv.data.remote.dto.StreamResponseDto
import com.nuvio.tv.domain.model.AioParsedFile
import com.nuvio.tv.domain.model.AioStreamData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * X1 (U5b): AIOStreams' `streamData` maps to [AioStreamData]; a malformed one drops only
 * that stream's data, never the stream or the response. Fixtures are synthetic. Hostile
 * nesting depth is stopped at the transport (UntrustedResponseGuardTest).
 */
class StreamDataMapperTest {

    private val json = NetworkModule.provideWireJson()

    private fun streams(payload: String) =
        json.decodeFromString<StreamResponseDto>(payload).streams.orEmpty().map { it.toDomain("AIOStreams", null) }

    @Test
    fun `streamData maps the fields the app reads and ignores the rest`() {
        val stream = streams(
            """
            {"streams":[{"name":"4K","url":"https://cdn.example.net/a.mkv","streamData":{
              "type":"debrid","size":88868565731,"filename":"Movie.2010.2160p.REMUX.mkv",
              "service":{"id":"debrid-x","cached":true},"streamExpressionScore":6749,
              "torrent":{"infoHash":"abc","seeders":13},
              "parsedFile":{"resolution":"2160p","quality":"BluRay REMUX","encode":"HEVC",
                "releaseGroup":"GRP","editions":["IMAX"],"visualTags":["DV","HDR10"],
                "audioTags":["DD+","DTS-HD MA"],"audioChannels":["5.1"],"languages":["English","Hindi"],
                "audioTracks":[{"lang":"English","codec":"dts"}],"bitrate":65693609}}}]}
            """.trimIndent()
        ).single()

        assertEquals(
            AioStreamData(
                size = 88_868_565_731L,
                filename = "Movie.2010.2160p.REMUX.mkv",
                cached = true,
                parsedFile = AioParsedFile(
                    resolution = "2160p",
                    quality = "BluRay REMUX",
                    encode = "HEVC",
                    releaseGroup = "GRP",
                    visualTags = listOf("DV", "HDR10"),
                    audioTags = listOf("DD+", "DTS-HD MA"),
                    audioChannels = listOf("5.1"),
                    languages = listOf("English", "Hindi"),
                    editions = listOf("IMAX")
                ),
                type = "debrid",
                serviceId = "debrid-x"
            ),
            stream.streamData
        )
    }

    // Spec 42 D6: the upstream addon's name is display text: one line, capped at 40.
    @Test
    fun `streamData addon maps to a one-line capped upstream name`() {
        val long = "U".repeat(60)
        val result = streams(
            """
            {"streams":[
              {"url":"https://cdn.example.net/1.mkv","streamData":{"addon":"Torrentio\n  4K","type":"p2p"}},
              {"url":"https://cdn.example.net/2.mkv","streamData":{"addon":"$long"}},
              {"url":"https://cdn.example.net/3.mkv","streamData":{"addon":"   "}}
            ]}
            """.trimIndent()
        )

        assertEquals("Torrentio 4K", result[0].streamData?.upstream)
        assertEquals("p2p", result[0].streamData?.type)
        assertEquals("U".repeat(40), result[1].streamData?.upstream)
        assertNull(result[2].streamData?.upstream)
    }

    @Test
    fun `invisible and separator characters never reach the upstream name`() {
        // U+202E bidi override and U+200B zero-width space dropped; U+2028 reads as a space;
        // a cut at 40 that would split the emoji's surrogate pair drops the half.
        val split = "A".repeat(39) + "\uD83C\uDFAC"
        val result = streams(
            """
            {"streams":[
              {"url":"https://cdn.example.net/1.mkv","streamData":{"addon":"\u202ETorr\u200Bentio\u2028Web"}},
              {"url":"https://cdn.example.net/2.mkv","streamData":{"addon":"$split"}}
            ]}
            """.trimIndent()
        )

        assertEquals("Torrentio Web", result[0].streamData?.upstream)
        assertEquals("A".repeat(39), result[1].streamData?.upstream)
    }

    @Test
    fun `a malformed streamData drops only that stream's data`() {
        val result = streams(
            """
            {"streams":[
              {"name":"bad tags","url":"https://cdn.example.net/1.mkv","streamData":{"parsedFile":{"visualTags":"DV"}}},
              {"name":"bad size","url":"https://cdn.example.net/2.mkv","streamData":{"size":"88 GB"}},
              {"name":"not an object","url":"https://cdn.example.net/3.mkv","streamData":["2160p"]},
              {"name":"absent","url":"https://cdn.example.net/4.mkv"},
              {"name":"good","url":"https://cdn.example.net/5.mkv","streamData":{"service":{"cached":false}}}
            ]}
            """.trimIndent()
        )

        assertEquals(listOf("bad tags", "bad size", "not an object", "absent", "good"), result.map { it.name })
        result.take(4).forEach { assertNull(it.name, it.streamData) }
        assertEquals(AioStreamData(size = null, filename = null, cached = false, parsedFile = null), result[4].streamData)
    }

    // Spec 51 P1: the file's own length, ms; zod's z.number() may send a fraction.
    @Test
    fun `streamData duration maps to whole positive milliseconds`() {
        val result = streams(
            """
            {"streams":[
              {"url":"https://cdn.example.net/1.mkv","streamData":{"duration":2828583,"size":5}},
              {"url":"https://cdn.example.net/2.mkv","streamData":{"duration":2828583.7}},
              {"url":"https://cdn.example.net/3.mkv","streamData":{"duration":0}},
              {"url":"https://cdn.example.net/4.mkv","streamData":{"size":5}},
              {"url":"https://cdn.example.net/5.mkv","streamData":{"duration":"47:08","size":5}},
              {"url":"https://cdn.example.net/6.mkv","streamData":{"duration":{"ms":1},"size":5}}
            ]}
            """.trimIndent()
        )

        assertEquals(
            listOf(2_828_583L, 2_828_583L, null, null, null, null),
            result.map { it.streamData?.durationMs }
        )
        // A duration of another type drops alone: the rest of the stream's data stays.
        assertEquals(listOf(5L, null, null, 5L, 5L, 5L), result.map { it.streamData?.size })
    }

    @Test
    fun `missing tag lists map to empty lists`() {
        val parsed = streams(
            """{"streams":[{"url":"https://cdn.example.net/a.mkv","streamData":{"parsedFile":{"resolution":"1080p"}}}]}"""
        ).single().streamData?.parsedFile

        assertEquals("1080p", parsed?.resolution)
        assertEquals(emptyList<String>(), parsed?.visualTags)
        assertEquals(emptyList<String>(), parsed?.editions)
    }
}
