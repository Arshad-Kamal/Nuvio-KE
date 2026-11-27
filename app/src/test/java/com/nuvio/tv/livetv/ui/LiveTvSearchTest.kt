package com.nuvio.tv.livetv.ui

import androidx.compose.ui.graphics.Color
import com.nuvio.tv.livetv.model.Channel
import com.nuvio.tv.livetv.ui.search.LiveChannelSearch
import com.nuvio.tv.livetv.ui.search.LiveChannelSearchIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 111 stages 3-5: what a result row marks. */
class LiveTvSearchTest {
    private fun ch(key: String, name: String) = Channel(key = key, sourceId = "a", name = name, streams = emptyList())

    private val lineup = Lineup(
        channels = listOf(ch("a", "TV9 Bangla"), ch("b", "WION")),
        numbers = mapOf("a" to "6391", "b" to "7069"),
        withLost = emptyList(),
        tags = emptyMap(),
        categoryLabels = emptyMap()
    )
    private val index = LiveChannelSearchIndex(lineup)

    @Test
    fun `a name hit marks the name, a number hit the number, a non-result nothing`() {
        val byName = LiveSearch("bangla", index.search("bangla"), total = 2)
        assertEquals(LiveRowMark(onNumber = false, range = 4..9), byName.mark("a"))
        assertNull(byName.mark("b"))
        val byNumber = LiveSearch("706", index.search("706"), total = 2)
        assertEquals(LiveRowMark(onNumber = true, range = 0..2), byNumber.mark("b"))
        assertNull((null as LiveSearch?).mark("a"))
    }

    @Test
    fun `the mark is clamped to the text and dropped when outside it`() {
        val fill = Color.White
        val marked = liveTvMarked("WION", 2..9, fill)
        assertEquals("WION", marked.text)
        assertEquals(listOf(2 to 4), marked.spanStyles.map { it.start to it.end })
        assertTrue(liveTvMarked("WION", 6..8, fill).spanStyles.isEmpty())
        assertTrue(liveTvMarked("WION", null, fill).spanStyles.isEmpty())
    }
}

/** Spec 111 stages 2, 3, 6: the count line and the list's message never show a pending or stale search. */
class LiveSearchStatusTest {
    private fun s(query: String, found: Int, pending: Boolean = false, total: Int = 100) = LiveSearch(
        query,
        List(found) { LiveChannelSearch.Hit(Channel(key = "k$it", sourceId = "a", name = "n$it", streams = emptyList()), LiveChannelSearch.Tier.PREFIX, 0, 1) },
        pending = pending,
        total = total
    )

    @Test
    fun `before typing every channel counts and the start note shows`() {
        assertEquals(LiveSearchStatus(LiveSearchCount.All(100), LiveSearchNote.START), liveSearchStatus(s("", 0, pending = true), null))
        assertEquals(LiveSearchStatus(LiveSearchCount.All(100), LiveSearchNote.START), liveSearchStatus(s("  ", 0), s("  ", 0)))
        assertEquals(LiveSearchStatus(null, LiveSearchNote.START), liveSearchStatus(s("", 0, total = 0), null)) // stores loading
        // A lineup change before typing: the pending frame already counts the new lineup.
        assertEquals(LiveSearchStatus(LiveSearchCount.All(99), LiveSearchNote.START), liveSearchStatus(s("", 0, pending = true, total = 99), s("", 0)))
    }

    @Test
    fun `Search or Down enters only the typed text's settled results`() {
        assertTrue(s("tv9", 6).settledFor("tv9"))
        assertFalse(s("tv9", 6, pending = true).settledFor("tv9"))
        assertFalse(s("tv", 6).settledFor("tv9")) // the results trail the field
        assertFalse((null as LiveSearch?).settledFor("tv9"))
    }

    @Test
    fun `a settled search of the same query counts and says no match when empty`() {
        val found = s("tv9", 6)
        assertEquals(LiveSearchStatus(LiveSearchCount.Found(6, 100), null), liveSearchStatus(found, found))
        val none = s("tv99", 0)
        assertEquals(LiveSearchStatus(LiveSearchCount.Found(0, 100), LiveSearchNote.NO_MATCH), liveSearchStatus(none, none))
        // Pending again (a refresh) after the same query settled: the held frame keeps what it showed.
        assertEquals(LiveSearchStatus(LiveSearchCount.Found(0, 100), LiveSearchNote.NO_MATCH), liveSearchStatus(s("tv99", 0, pending = true), none))
    }

    @Test
    fun `a pending search says nothing until its own query settles`() {
        assertEquals(LiveSearchStatus(null, null), liveSearchStatus(s("tv9", 0, pending = true), null))
        // The settled search is an older query's (typed while the index was made again): no stale count or note.
        assertEquals(LiveSearchStatus(null, null), liveSearchStatus(s("tv99", 0, pending = true), s("tv9", 0)))
        assertEquals(LiveSearchStatus(null, null), liveSearchStatus(s("tv9", 3, pending = true), s("", 0)))
    }
}

/** Spec 111 stages 4, 10: Search or Down enters the first result once the typed text's results settle. */
class LiveSearchEntryTest {
    private fun s(query: String, pending: Boolean = false) = LiveSearch(query, emptyList(), pending = pending, total = 1)

    @Test
    fun `settled results are entered at once, pending ones once they settle`() {
        val entry = LiveSearchEntry()
        assertTrue(entry.submit("tv9", s("tv9")))
        assertFalse(entry.settled(s("tv9"))) // nothing waits after an entry at once
        assertFalse(entry.submit("tv9", s("tv9", pending = true)))
        assertFalse(entry.settled(s("tv9", pending = true)))
        assertTrue(entry.settled(s("tv9")))
        assertFalse(entry.settled(s("tv9"))) // once
        assertFalse(entry.submit("tv9", s("tv"))) // the results trail the field
        assertTrue(entry.settled(s("tv9")))
    }

    @Test
    fun `typing, leaving the field or closing search drops a waiting entry`() {
        val entry = LiveSearchEntry()
        entry.submit("tv9", s("tv9", pending = true))
        entry.drop()
        assertFalse(entry.settled(s("tv9")))
        entry.submit("tv9", s("tv9", pending = true))
        assertFalse(entry.settled(null)) // closed
        assertFalse(entry.settled(s("tv9"))) // reopened later with the same text: no jump
    }
}
