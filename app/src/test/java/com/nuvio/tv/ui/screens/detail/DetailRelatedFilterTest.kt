package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.core.contentfilter.TagState
import com.nuvio.tv.data.repository.CatalogContentFilter
import com.nuvio.tv.data.repository.GateFixture
import com.nuvio.tv.data.repository.tmdbPreview
import com.nuvio.tv.domain.contentfilter.FilterPack
import com.nuvio.tv.domain.contentfilter.PackWord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

/** Spec 117 S8 (P8): Detail's More like this and collection rows through the content filter's gate. */
class DetailRelatedFilterTest {
    private val packs = listOf(FilterPack("p5", words = listOf(PackWord("zombie")), genreIds = setOf(27)))
    private val items = listOf(tmdbPreview(1, "Zombie Night"), tmdbPreview(2, "Spring"), tmdbPreview(3, "Frozen", genreIds = listOf(27)))

    @Test
    fun `hits hide, by words or by the list's genre ids, and undecided titles hide after one wait`() = runTest {
        val fixture = GateFixture(packs)
        fixture.states["tmdb:2"] = TagState.Undecided
        val shown = filterDetailRow(items, fixture.contentFilter::open)
        assertEquals(emptyList<String>(), shown.map { it.id })
        assertEquals("one wait with the load's whole budget", listOf(listOf("tmdb:2") to CatalogContentFilter.BUDGET_MS), fixture.waits)
    }

    @Test
    fun `clean titles stay in order`() = runTest {
        val shown = filterDetailRow(items, GateFixture(packs)::gate)
        assertEquals(listOf("tmdb:2"), shown.map { it.id })
    }

    @Test
    fun `previewing, hits stay marked with their packs`() = runTest {
        val shown = filterDetailRow(items, GateFixture(packs, preview = true)::gate)
        assertEquals(listOf(listOf("p5"), emptyList(), listOf("p5")), shown.map { it.filterHits })
    }

    @Test
    fun `no filter leaves the row as it is, an empty row asks nothing`() = runTest {
        assertSame(items, filterDetailRow(items) { null })
        var opened = false
        filterDetailRow(emptyList()) { opened = true; null }
        assertEquals(false, opened)
    }

    @Test
    fun `a broken pack file hides the row instead of failing open`() = runTest {
        assertEquals(emptyList<Any>(), filterDetailRow(items) { throw IllegalArgumentException("bad pack file") })
        assertEquals(emptyList<Any>(), filterDetailRow(items) { throw IOException("no asset") })
    }

    @Test
    fun `cancellation and other failures propagate`() = runTest {
        assertThrows(CancellationException::class.java) { runBlocking { filterDetailRow(items) { throw CancellationException("left") } } }
        assertThrows(IllegalStateException::class.java) { runBlocking { filterDetailRow(items) { throw IllegalStateException("bug") } } }
    }
}
