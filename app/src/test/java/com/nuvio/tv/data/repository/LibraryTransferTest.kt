package com.nuvio.tv.data.repository

import com.nuvio.tv.core.tracking.TrackingLibraryEntry
import com.nuvio.tv.domain.model.LibraryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Spec 104 S4a: the transfer planner (mockup stage D1 counts, the Move candidates of D11). */
class LibraryTransferTest {

    private fun entry(id: String, type: String = "movie", vararg ids: String) = TrackingLibraryEntry(
        LibraryEntry(
            id = id, type = type, name = id, poster = null, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList(), addonBaseUrl = null
        ),
        ids.toSet()
    )

    @Test
    fun `only tt and tmdb ids count, normalised`() {
        assertEquals("tt0133093", transferId(" TT0133093 "))
        assertEquals("tt0133093", transferId("imdb:tt0133093"))
        assertEquals("tmdb:603", transferId("tmdb:0603"))
        assertNull(transferId("603"))
        assertNull(transferId("kitsu:1"))
        assertNull(transferId("mal:5114"))
        assertNull(transferId("myaddon:42"))
    }

    @Test
    fun `counts add up to copy, already there and unmatched`() {
        val plan = computeTransferPlan(
            source = listOf(entry("tt1"), entry("tmdb:2"), entry("tt3"), entry("48213"), entry("kitsu:9", "anime")),
            destination = listOf(entry("tt3"), entry("tt9"))
        )
        assertEquals(5, plan.sourceTitles)
        assertEquals(listOf("tt1", "tmdb:2"), plan.toCopy.map { it.first.entry.id })
        assertEquals(listOf("tt3"), plan.alreadyThere.map { it.first.entry.id })
        assertEquals(2, plan.unmatched)
        assertEquals(3, plan.moveCandidates.size)
    }

    @Test
    fun `a movie and a series with the same tmdb id are different titles`() {
        val plan = computeTransferPlan(
            source = listOf(entry("tmdb:1399", "series"), entry("tmdb:1399", "movie")),
            destination = listOf(entry("tmdb:1399", "movie"))
        )
        assertEquals(2, plan.sourceTitles)
        assertEquals(listOf("series"), plan.toCopy.map { it.first.entry.type })
        assertEquals(listOf("movie"), plan.alreadyThere.map { it.first.entry.type })
    }

    @Test
    fun `show and tv are series`() {
        val plan = computeTransferPlan(listOf(entry("tt5", "series")), listOf(entry("tt5", "show")))
        assertEquals(1, plan.alreadyThere.size)
    }

    @Test
    fun `a tracker's other ids match a local tmdb entry, and one title stored twice is one title with both entries`() {
        val local = listOf(entry("tt603"), entry("tmdb:603"))
        val tracker = listOf(entry("tt603", "movie", "tmdb:603"))
        val plan = computeTransferPlan(local, tracker)
        assertEquals(1, plan.sourceTitles)
        assertEquals(listOf("tt603", "tmdb:603"), plan.alreadyThere.single().members.map { it.entry.id })
        assertEquals(0, plan.toCopy.size)
    }

    @Test
    fun `an entry bridging two earlier titles merges them`() {
        val (titles, unmatched) = groupTransferTitles(
            listOf(entry("tt1"), entry("tmdb:1"), entry("tt1", "movie", "tmdb:1"))
        )
        assertEquals(0, unmatched)
        assertEquals(1, titles.size)
        assertEquals(3, titles.single().members.size)
    }

    @Test
    fun `a Move removes only candidates the destination lists after the copy`() {
        val plan = computeTransferPlan(listOf(entry("tt1"), entry("tt2"), entry("tt3")), listOf(entry("tt3")))
        val confirmed = confirmedAt(plan.moveCandidates, listOf(entry("tt3"), entry("tt1", "movie", "tmdb:1")))
        assertEquals(listOf("tt1", "tt3"), confirmed.map { it.first.entry.id }.sorted())
    }

    @Test
    fun `a type other than movie or series can't be matched, anime counts as series`() {
        val plan = computeTransferPlan(listOf(entry("tt5", "channel"), entry("tt6", "anime")), listOf(entry("tt6", "series")))
        assertEquals(1, plan.unmatched)
        assertEquals(1, plan.alreadyThere.size)
        assertEquals(2, plan.sourceTitles)
    }

    @Test
    fun `the write takes the tt id first and the normalised type`() {
        val title = computeTransferPlan(listOf(entry("tmdb:1399", "show", "tt0944947")), emptyList()).toCopy.single()
        val input = title.writeInput()
        assertEquals("tt0944947", input.itemId)
        assertEquals("series", input.itemType)
        val tmdbOnly = computeTransferPlan(listOf(entry("tmdb:7", "movie")), emptyList()).toCopy.single()
        assertEquals("tmdb:7", tmdbOnly.writeInput().itemId)
    }
}
