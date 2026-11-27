package com.nuvio.tv.livetv.ui.search

import com.nuvio.tv.livetv.model.Channel
import com.nuvio.tv.livetv.ui.Lineup
import com.nuvio.tv.livetv.ui.LiveTvModel
import com.nuvio.tv.livetv.ui.search.LiveChannelSearch.Tier
import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec 111 D8: search looks through every listable channel of the lineup, by shown name and number. */
class LiveChannelSearchIndexTest {

    private fun ch(key: String, name: String, lostAtMs: Long? = null) =
        Channel(key = key, sourceId = "a", name = name, streams = emptyList(), lostAtMs = lostAtMs)

    @Test
    fun `searches the listable channels only, in lineup order`() {
        val all = listOf(
            ch("a", "TV9 Bangla"),
            ch("b", "TV9 Lost", lostAtMs = 1L),
            ch("c", "TV9 Hidden"),
            ch("d", "TV9 Gujarati")
        )
        val lineup = LiveTvModel.lineup(all, sourceIds = listOf("a"), meta = null, hidden = setOf("c"))
        val hits = LiveChannelSearchIndex(lineup).search("tv9")
        assertEquals(listOf("a", "d"), hits.map { it.item.key })
    }

    @Test
    fun `matches the shown name, not the playlist tags`() {
        val lineup = Lineup(
            channels = listOf(ch("a", "Sky News (720p) [Geo-blocked]")),
            numbers = mapOf("a" to "501"),
            withLost = emptyList(),
            tags = emptyMap(),
            categoryLabels = emptyMap()
        )
        val index = LiveChannelSearchIndex(lineup)
        assertEquals(emptyList<String>(), index.search("720p").map { it.item.key })
        assertEquals(listOf(Tier.WORD_START), index.search("news").map { it.tier })
        assertEquals(listOf(Tier.NUMBER_EXACT), index.search("501").map { it.tier })
    }

    @Test
    fun `highlight marks the shown name or the number`() {
        val channel = ch("a", "(720p) Sky News")
        val lineup = Lineup(listOf(channel), mapOf("a" to "501"), emptyList(), emptyMap(), emptyMap())
        val index = LiveChannelSearchIndex(lineup)
        val byName = index.search("news").single()
        assertEquals("News", "Sky News".substring(LiveChannelSearchIndex.highlight(byName)))
        assertEquals(0..<2, LiveChannelSearchIndex.highlight(index.search("50").single()))
    }
}
