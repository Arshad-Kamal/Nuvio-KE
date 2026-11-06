package com.nuvio.tv.data.remote.dto

import com.nuvio.tv.core.di.NetworkModule
import com.nuvio.tv.data.remote.api.ImdbApiParentsGuideResponse
import com.nuvio.tv.data.remote.api.IntroDbSegmentsResponse
import com.nuvio.tv.data.remote.api.SeriesGraphSeasonRatingsDto
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Moshi retirement M2: decode/encode fixtures for the small-endpoint services
 * (introDb, seriesGraph, imdbTapframe, trailer, parentalGuide). The encodeDefaults
 * pin lives in WireJsonParityTest. All use NetworkModule.provideWireJson — the
 * single wire-Json home.
 */
class WireDtoDecodeTest {

    private val json = NetworkModule.provideWireJson()

    @Test
    fun `introdb segments decode with nested intro and recap`() {
        val payload = """
        {"imdb_id":"tt123","season":1,"episode":2,"unknown_top":true,
         "intro":{"start_sec":12.5,"end_sec":75.25,"start_ms":12500,"end_ms":75250,
                  "confidence":0.98,"submission_count":14,"updated_at":"2026-01-01"},
         "recap":{"start_sec":0.0,"end_sec":10.0},
         "outro":null}
        """.trimIndent()
        val decoded = json.decodeFromString<IntroDbSegmentsResponse>(payload)
        assertEquals("tt123", decoded.imdbId)
        assertEquals(2, decoded.episode)
        assertEquals(12.5, decoded.intro?.startSec!!, 0.0)
        assertEquals(75250L, decoded.intro.endMs)
        assertEquals(14, decoded.intro.submissionCount)
        assertEquals(0.0, decoded.recap?.startSec!!, 0.0)
        assertNull(decoded.outro)
    }

    @Test
    fun `series graph season ratings decode snake_case`() {
        val payload = """
        [{"episodes":[{"season_number":1,"episode_number":1,"vote_average":8.4,"name":"Pilot","tconst":"tt0001"},
                      {"season_number":1,"episode_number":2,"vote_average":7.1,"new_field":true}]},
         {}]
        """.trimIndent()
        val decoded = json.decodeFromString<List<SeriesGraphSeasonRatingsDto>>(payload)
        assertEquals(2, decoded.size)
        assertEquals(8.4, decoded[0].episodes!![0].voteAverage!!, 0.0)
        assertEquals("tt0001", decoded[0].episodes!![0].tconst)
        assertEquals(7.1, decoded[0].episodes!![1].voteAverage!!, 0.0)
        assertNull(decoded[1].episodes)
    }

    @Test
    fun `parental guide decodes nested categories`() {
        val payload = """
        {"parentsGuide":[
          {"category":"Violence","severityBreakdowns":[{"severityLevel":"Mild","voteCount":42}],
           "reviews":[{"text":"some text","isSpoiler":false},{"text":null,"isSpoiler":null}]},
          {"category":"Nudity"}
        ]}
        """.trimIndent()
        val decoded = json.decodeFromString<ImdbApiParentsGuideResponse>(payload)
        assertEquals(2, decoded.parentsGuide?.size)
        val violence = decoded.parentsGuide!![0]
        assertEquals("Mild", violence.severityBreakdowns!![0].severityLevel)
        assertEquals(42, violence.severityBreakdowns[0].voteCount)
        assertNull(violence.reviews!![1].text)
        assertNull(decoded.parentsGuide[1].reviews)
        assertNull(decoded.parentsGuide[1].severityBreakdowns)
    }

    @Test
    fun `parental guide missing required category rejects`() {
        assertThrows(SerializationException::class.java) {
            json.decodeFromString<ImdbApiParentsGuideResponse>("""{"parentsGuide":[{"severityBreakdowns":[]}]}""")
        }
    }
}
