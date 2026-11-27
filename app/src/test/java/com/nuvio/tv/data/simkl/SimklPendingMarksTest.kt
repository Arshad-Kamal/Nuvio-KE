package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingEpisode
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Spec 106 S2b: marks kept while the allowance is spent. */
class SimklPendingMarksTest {
    @get:Rule val folder = TemporaryFolder()

    private val dropped = mutableListOf<Int>()
    private fun marks() = SimklPendingMarks(folder.root, Dispatchers.Unconfined) { dropped += it }

    private fun film(imdb: String) = TrackingMediaReference(TrackingMediaKind.MOVIE, "Film $imdb", 2020, TrackingExternalIds(imdb = imdb))
    private fun episode(number: Int) = TrackingMediaReference(
        TrackingMediaKind.SHOW, "Show", 2021, TrackingExternalIds(tmdb = 7), TrackingEpisode(season = 1, number = number)
    )

    @Test
    fun `marks keep call order, one per key, the later word replacing the earlier`() = runTest {
        val store = marks()
        store.add(1, listOf(TrackingHistoryItem(film("tt1"), 10L), TrackingHistoryItem(episode(1), 11L)))
        store.remove(1, listOf(film("tt2")))
        store.remove(1, listOf(episode(1)))
        store.add(1, listOf(TrackingHistoryItem(episode(2), 12L)))

        val all = store.get(1)
        assertEquals(listOf("tt1", "tt2", null, null), all.map { it.ids.imdb })
        assertEquals(listOf(SimklPendingOp.ADD, SimklPendingOp.REMOVE, SimklPendingOp.REMOVE, SimklPendingOp.ADD), all.map { it.op })
        assertEquals(listOf(null, null, 1, 2), all.map { it.episode })
        assertEquals(listOf(SimklPendingOp.ADD), store.firstBatch(1).map { it.op })
    }

    @Test
    fun `past the cap the oldest go and the count is logged`() = runTest {
        val store = marks()
        store.add(1, (1..SimklPendingMarks.CAP).map { TrackingHistoryItem(film("tt$it"), it.toLong()) })
        assertTrue(dropped.isEmpty())
        store.add(1, (501..503).map { TrackingHistoryItem(film("tt$it"), it.toLong()) })

        val all = store.get(1)
        assertEquals(SimklPendingMarks.CAP, all.size)
        assertEquals("tt4", all.first().ids.imdb)
        assertEquals("tt503", all.last().ids.imdb)
        assertEquals(listOf(3), dropped)
    }

    @Test
    fun `marks survive a restart, per profile, and clear deletes them`() = runTest {
        marks().add(1, listOf(TrackingHistoryItem(episode(3), 99L)))
        marks().remove(2, listOf(film("tt9")))

        val reloaded = marks()
        val item = reloaded.get(1).single().toHistoryItem()
        assertEquals(episode(3).copy(catalog = null), item.media)
        assertEquals(99L, item.watchedAtEpochMs)
        assertEquals("tt9", reloaded.get(2).single().ids.imdb)

        reloaded.clear(1)
        assertTrue(reloaded.get(1).isEmpty())
        assertTrue(marks().get(1).isEmpty())
        assertFalse(File(folder.root, "pending_1.json").exists())
        assertEquals(1, marks().get(2).size)
    }

    @Test
    fun `drop keeps a mark that replaced a sent one, supersede drops by key`() = runTest {
        val store = marks()
        store.add(1, listOf(TrackingHistoryItem(film("tt1"), 1L), TrackingHistoryItem(film("tt2"), 2L)))
        val sent = store.firstBatch(1)
        store.remove(1, listOf(film("tt1")))
        store.drop(1, sent)
        assertEquals(listOf(SimklPendingOp.REMOVE), store.get(1).map { it.op })

        store.add(1, listOf(TrackingHistoryItem(film("tt3"), 3L)))
        store.supersede(1, listOf(film("tt1")))
        assertEquals(listOf("tt3"), store.get(1).map { it.ids.imdb })
    }

    @Test
    fun `an unreadable file holds nothing`() = runTest {
        File(folder.root, "pending_1.json").writeText("{not json")
        assertTrue(marks().get(1).isEmpty())
    }
}
