package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.data.mdblist.MdbListOwnedList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 105 S3b: the detail page's MDBList chip count and list-change bookkeeping (C1, C3). */
class DetailMdbListListsTest {
    private val connected = DetailMdbListLive(session = 7, onWatchlist = true, watchlistCount = 212)
    private val lists = listOf(MdbListOwnedList(11, "Sunday night films", 41), MdbListOwnedList(12, "Blender shorts", 12))

    @Test
    fun `the chip counts the watchlist and the lists, and hides while unknown, none or disconnected`() {
        assertEquals(2, DetailMdbListUi(connected, membership = setOf(11L)).chipCount)
        assertEquals(1, DetailMdbListUi(connected.copy(onWatchlist = false), membership = setOf(11L)).chipCount)
        assertNull(DetailMdbListUi(connected, membership = null).chipCount)
        assertNull(DetailMdbListUi(connected.copy(onWatchlist = false), membership = emptySet()).chipCount)
        assertNull(DetailMdbListUi(DetailMdbListLive(), membership = setOf(11L)).chipCount)
    }

    @Test
    fun `a confirmed change moves membership and that list's count by one`() {
        val ui = DetailMdbListUi(connected, membership = setOf(11L), panel = MdbListPanelUi(lists, watchlistIsLibrary = true))
        val added = ui.withListChange(7, 12, on = true)
        assertEquals(setOf(11L, 12L), added.membership)
        assertEquals(listOf(41, 13), added.panel!!.lists.map { it.itemCount })
        val removed = added.withListChange(7, 11, on = false)
        assertEquals(setOf(12L), removed.membership)
        assertEquals(listOf(40, 13), removed.panel!!.lists.map { it.itemCount })
    }

    @Test
    fun `a write of the state already known, or with membership unknown, changes nothing`() {
        val ui = DetailMdbListUi(connected, membership = setOf(11L), panel = MdbListPanelUi(lists, watchlistIsLibrary = false))
        assertSame(ui, ui.withListChange(7, 11, on = true))
        assertSame(ui, ui.withListChange(7, 12, on = false))
        assertSame(ui, ui.withListChange(6, 12, on = true))
        val unknown = DetailMdbListUi(connected)
        assertSame(unknown, unknown.withListChange(7, 11, on = true))
    }

    @Test
    fun `a membership read lands only in its own session and never over a known membership`() {
        val empty = DetailMdbListUi(connected)
        assertEquals(setOf(11L), empty.withLoadedMembership(7, setOf(11L)).membership)
        assertSame(empty, empty.withLoadedMembership(6, setOf(11L)))
        // A write confirmed since the read started set membership: the older read must not undo it.
        val written = DetailMdbListUi(connected, membership = setOf(11L, 12L))
        assertSame(written, written.withLoadedMembership(7, setOf(11L)))
        val disconnected = DetailMdbListUi(DetailMdbListLive())
        assertSame(disconnected, disconnected.withLoadedMembership(0, setOf(11L)))
    }

    @Test
    fun `a new key session starts empty, the same session keeps what the page knows`() {
        val ui = DetailMdbListUi(connected, membership = setOf(11L), panel = MdbListPanelUi(lists, true), pending = setOf(12L))
        val sameSession = ui.withLive(connected.copy(onWatchlist = false))
        assertEquals(setOf(11L), sameSession.membership)
        assertEquals(setOf(12L), sameSession.pending)
        assertFalse(sameSession.live.onWatchlist)
        val newKey = ui.withLive(connected.copy(session = 8))
        assertNull(newKey.membership)
        assertNull(newKey.panel)
        assertTrue(newKey.pending.isEmpty())
        assertFalse(ui.withLive(DetailMdbListLive()).live.connected)
    }

    @Test
    fun `paused only before the pause's end`() {
        val ui = DetailMdbListUi(connected.copy(pausedUntilEpochMs = 1_000L))
        assertTrue(ui.isPaused(999L))
        assertFalse(ui.isPaused(1_000L))
        assertFalse(DetailMdbListUi(connected).isPaused(0L))
    }
}
