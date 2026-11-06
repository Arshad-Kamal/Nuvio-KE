package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.model.Channel
import com.nuvio.tv.livetv.model.LiveStream
import com.nuvio.tv.livetv.parse.EpgChannel
import com.nuvio.tv.livetv.parse.EpgSnapshot
import com.nuvio.tv.livetv.source.LiveSource
import com.nuvio.tv.livetv.source.LiveSourceManager
import com.nuvio.tv.livetv.source.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Spec 34 L7e Sources route rules: row facts, health precedence, hidden list, refresh age. */
class LiveTvSourcesModelTest {

    private fun ch(source: String, key: String, lostAtMs: Long? = null) = Channel(
        key = key,
        sourceId = source,
        name = key,
        streams = listOf(LiveStream("https://example.org/$key.m3u8")),
        lostAtMs = lostAtMs
    )

    private fun src(
        id: String,
        kind: SourceKind = SourceKind.M3U,
        count: Int = 0,
        refreshed: Long? = 1L,
        error: String? = null
    ) = LiveSource(id, kind, "name-$id", "https://example.org/$id", 0L, refreshed, error, count)

    @Test
    fun `rows carry counts, lost channels and the preset's names`() {
        val preset = LiveSourceManager.PRESET_ID
        val sources = listOf(src(preset, count = 2), src("p", count = 3), src("g", SourceKind.XMLTV, count = 41), src("g2", SourceKind.XMLTV))
        val channels = listOf(
            ch(preset, "DW"),
            ch(preset, "NHK"),
            ch(preset, "Gone", lostAtMs = 5),
            ch("p", "a"),
            ch("p", "b", lostAtMs = 5),
            ch("p", "c", lostAtMs = 6)
        )
        val guide = EpgSnapshot(mapOf("x" to EpgChannel("x", listOf("X"), null), "y" to EpgChannel("y", listOf("Y"), null)), emptyMap(), 0, 1, true)
        val rows = LiveTvSourcesModel.rows(sources, channels, mapOf("g" to guide), setOf("g2"))

        assertEquals(listOf("DW", "NHK"), rows[0].preview)
        assertEquals(SourceHealth.Lost(1), rows[0].health)
        assertEquals(3, rows[1].listed)
        assertNull(rows[1].matched)
        assertEquals(SourceHealth.Lost(2), rows[1].health)
        assertEquals(listOf<String>(), rows[1].preview)
        assertEquals(2, rows[2].listed)
        assertEquals(41, rows[2].matched)
        assertEquals(SourceHealth.Ok, rows[2].health)
        // An uncached guide has no file count yet; this one is refreshing.
        assertNull(rows[3].listed)
        assertEquals(SourceHealth.Refreshing, rows[3].health)
    }

    @Test
    fun `the Pluto guide is suggested while listed channels hold Pluto streams and no source has it`() {
        fun pluto(source: String, key: String, lostAtMs: Long? = null) = Channel(
            key = key,
            sourceId = source,
            name = key,
            streams = listOf(LiveStream("https://example.org/$key.m3u8"), LiveStream("https://jmp2.uk/plu-5ee92e72fb286e0007285fec.m3u8")),
            lostAtMs = lostAtMs
        )
        val m = LiveTvSourcesModel
        val sources = listOf(src("p"), src("q"))
        val one = listOf(pluto("p", "a"), pluto("p", "b"), pluto("p", "gone", lostAtMs = 5), ch("q", "c"))
        assertEquals(GuideSuggestion(2, "name-p"), m.plutoSuggestion(sources, one))
        // Held by several sources: no single name.
        assertEquals(GuideSuggestion(3, null), m.plutoSuggestion(sources, one + pluto("q", "d")))
        // No Pluto stream, or only lost ones: nothing to suggest.
        assertNull(m.plutoSuggestion(sources, listOf(ch("p", "a"), pluto("p", "gone", lostAtMs = 5))))
        // Any source with the guide's URL (the preset or a hand-added copy) ends the suggestion.
        val added = LiveSource("mine", SourceKind.XMLTV, "Mine", LiveSourceManager.PLUTO_GUIDE.url, 0L)
        assertNull(m.plutoSuggestion(sources + added, one))
    }

    @Test
    fun `the India guides are suggested while listed channels come from India and neither guide is added`() {
        fun india(source: String, key: String, country: String? = "in", lostAtMs: Long? = null) =
            Channel(key = key, sourceId = source, name = key, streams = listOf(LiveStream("https://example.org/$key.m3u8")), country = country, lostAtMs = lostAtMs)
        val m = LiveTvSourcesModel
        val sources = listOf(src("p"), src("q"))
        val one = listOf(india("p", "a"), india("p", "b", country = "IN"), india("p", "gone", lostAtMs = 5), india("q", "c", country = "de"))
        assertEquals(GuideSuggestion(2, "name-p"), m.indiaSuggestion(sources, one))
        assertEquals(GuideSuggestion(3, null), m.indiaSuggestion(sources, one + india("q", "d")))
        assertNull(m.indiaSuggestion(sources, listOf(india("p", "x", country = null), india("p", "gone", lostAtMs = 5))))
        // Either guide's URL on any source ends the suggestion.
        for (guide in LiveSourceManager.INDIA_GUIDES) {
            val added = LiveSource("mine", SourceKind.XMLTV, "Mine", guide.url, 0L)
            assertNull(m.indiaSuggestion(sources + added, one))
        }
    }

    @Test
    fun `health precedence`() {
        val m = LiveTvSourcesModel
        assertEquals(SourceHealth.Refreshing, m.health(src("a", error = "HTTP 500"), lost = 2, refreshing = true))
        assertEquals(SourceHealth.Partial, m.health(src("a", error = LiveSourceManager.GUIDE_INCOMPLETE), 0, false))
        assertEquals(SourceHealth.Failed, m.health(src("a", error = "HTTP 500"), lost = 2, refreshing = false))
        assertEquals(SourceHealth.Waiting, m.health(src("a", refreshed = null), 0, false))
        assertEquals(SourceHealth.Failed, m.health(src("a", refreshed = null, error = "HTTP 404"), 0, false))
        assertEquals(SourceHealth.Lost(2), m.health(src("a"), 2, false))
        assertEquals(SourceHealth.Ok, m.health(src("a"), 0, false))
    }

    @Test
    fun `hidden lists existing channels by name with their source`() {
        val rows = LiveTvSourcesModel.hidden(
            keys = setOf("zee", "Aaj", "vanished"),
            channels = listOf(ch("p", "zee"), ch("p", "Aaj"), ch("p", "shown")),
            sources = listOf(src("p"))
        )
        assertEquals(listOf("Aaj", "zee"), rows.map { it.key })
        assertEquals("name-p", rows.first().sourceName)
        assertEquals(emptyList<HiddenRow>(), LiveTvSourcesModel.hidden(emptySet(), listOf(ch("p", "a")), emptyList()))
    }

    @Test
    fun `refresh age`() {
        val zone: ZoneId = ZoneOffset.UTC
        fun at(day: Int, hour: Int, minute: Int) = LocalDateTime.of(2026, 10, day, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()
        val now = at(19, 8, 0)
        val m = LiveTvSourcesModel
        assertEquals(RefreshAge.Never, m.refreshAge(null, now, zone))
        assertEquals(RefreshAge.JustNow, m.refreshAge(now - 59_000, now, zone))
        assertEquals(RefreshAge.Minutes(59), m.refreshAge(at(19, 7, 1), now, zone))
        assertEquals(RefreshAge.Today(at(19, 6, 10)), m.refreshAge(at(19, 6, 10), now, zone))
        assertEquals(RefreshAge.Days(1), m.refreshAge(at(18, 23, 50), now, zone))
        assertEquals(RefreshAge.Days(3), m.refreshAge(at(16, 9, 0), now, zone))
    }
}
