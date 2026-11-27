package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.mergeCatalogPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 117 S4c: the Marquee row load-more trigger (P5b paging, D26 rev 13). */
class RowLoadMoreTriggerTest {

    /** One row as the trigger sees it, plus its stored entry; [observe] is one snapshot emission. */
    private class Sim(
        var lastVisible: Int? = 19,
        var drawn: Int = 20,
        var page: Int = 0,
        var hasMore: Boolean = true,
        var isLoading: Boolean = false,
        var active: Boolean = true,
        var focused: Int? = 19
    ) {
        var entry: RowLoadMoreEntry? = null
        var fires = 0

        fun inputs() = RowLoadMoreInputs(lastVisible, drawn, page, hasMore, isLoading, active, focused)

        fun observe(): Boolean {
            val decision = decideRowLoadMore(inputs(), entry)
            entry = decision.entry
            if (decision.fire) fires++
            return decision.fire
        }

        /** The requested page lands: [added] new drawn cards, page + 1 (focus and scroll unchanged). */
        fun pageLands(added: Int = 0) {
            isLoading = true
            observe()
            isLoading = false
            drawn += added
            page++
        }
    }

    @Test
    fun `an unfocused row never fires`() {
        val sim = Sim(active = false)
        assertFalse(sim.observe())
    }

    @Test
    fun `a row with nothing laid out never fires`() {
        val sim = Sim(lastVisible = null)
        assertFalse(sim.observe())
    }

    @Test
    fun `no more pages or a load in flight never fires`() {
        assertFalse(Sim(hasMore = false).observe())
        assertFalse(Sim(isLoading = true).observe())
    }

    @Test
    fun `away from the end nothing fires`() {
        assertFalse(Sim(lastVisible = 15).observe())
        assertTrue(Sim(lastVisible = 16).observe())
    }

    @Test
    fun `pages adding no card fire the next page, three pages at most`() {
        val sim = Sim()
        repeat(5) {
            sim.observe()
            sim.pageLands(added = 0)
        }
        assertEquals(3, sim.fires)
        assertEquals(setOf(0, 1, 2), sim.entry!!.firedPages)
    }

    @Test
    fun `a page whose titles are all unreleased counts as a page`() {
        // filterReleasedItems drops the page's cards before the row model: drawn count unchanged.
        val sim = Sim()
        assertTrue(sim.observe())
        sim.pageLands(added = 0)
        assertTrue(sim.observe())
        assertEquals(setOf(0, 1), sim.entry!!.firedPages)
    }

    @Test
    fun `a row gaining one card per page stops at three`() {
        val sim = Sim()
        repeat(6) {
            sim.observe()
            sim.pageLands(added = 1)
        }
        assertEquals(3, sim.fires)
    }

    @Test
    fun `a re-fire at the same page after leaving the end is not counted`() {
        val sim = Sim()
        assertTrue(sim.observe())
        sim.lastVisible = 10 // scrolled back without a focus change: the key clears
        assertFalse(sim.observe())
        sim.lastVisible = 19
        assertTrue(sim.observe())
        assertEquals(setOf(0), sim.entry!!.firedPages)
        sim.pageLands()
        assertTrue(sim.observe())
        sim.pageLands()
        assertTrue(sim.observe())
        sim.pageLands()
        assertFalse(sim.observe())
        assertEquals(4, sim.fires)
    }

    @Test
    fun `focus entering the row re-arms it`() {
        val sim = Sim()
        repeat(3) { sim.observe(); sim.pageLands() }
        assertFalse(sim.observe())
        sim.active = false
        assertFalse(sim.observe())
        sim.active = true
        assertTrue(sim.observe())
        assertEquals(setOf(3), sim.entry!!.firedPages)
    }

    @Test
    fun `a card move re-arms the row and clears the key`() {
        val sim = Sim()
        repeat(3) { sim.observe(); sim.pageLands() }
        assertFalse(sim.observe())
        sim.focused = 18
        assertTrue(sim.observe())
    }

    @Test
    fun `after a load error a move fires again at the same page`() {
        val sim = Sim()
        assertTrue(sim.observe())
        sim.isLoading = true
        assertFalse(sim.observe())
        sim.isLoading = false // Error: no page added, page unchanged
        assertFalse(sim.observe())
        sim.focused = 18
        assertTrue(sim.observe())
        assertEquals(setOf(0), sim.entry!!.firedPages)
    }

    @Test
    fun `a page decrease (reload) re-arms the row`() {
        val sim = Sim()
        repeat(3) { sim.observe(); sim.pageLands() }
        assertFalse(sim.observe())
        sim.page = 1
        assertTrue(sim.observe())
        assertEquals(setOf(1), sim.entry!!.firedPages)
    }

    @Test
    fun `an effect restart with unchanged inputs does not reset`() {
        val sim = Sim()
        repeat(3) { sim.observe(); sim.pageLands() }
        assertFalse(sim.observe())
        val stored = sim.entry
        // A restarted effect's first emission sees the same inputs and the stored entry.
        val decision = decideRowLoadMore(sim.inputs(), stored)
        assertFalse(decision.fire)
        assertEquals(stored, decision.entry)
    }

    @Test
    fun `a merge adding five or more cards fires nothing until the user reaches the new end`() {
        val sim = Sim()
        assertTrue(sim.observe())
        sim.pageLands(added = 5)
        assertFalse(sim.observe())
        sim.focused = 24
        sim.lastVisible = 24
        assertTrue(sim.observe())
    }

    @Test
    fun `a folder addon row pages past pages that add no card, bounded by the trigger`() {
        // FolderDetail's addon tabs merge with mergeCatalogPage (FolderDetailViewModel). Each page adds one
        // title the draw drops (unreleased), so the merged row keeps hasMore and only the trigger bounds it.
        var row = catalogRow(items = (0 until 20).map(::item), hasMore = true)
        val sim = Sim()
        repeat(6) {
            sim.page = row.currentPage
            sim.hasMore = row.hasMore
            if (sim.observe()) {
                row = row.mergeCatalogPage(catalogRow(items = listOf(item(100 + row.currentPage)), hasMore = true))
            }
        }
        assertTrue(row.hasMore)
        assertEquals(3, sim.fires)
        assertEquals(3, row.currentPage)
    }

    @Test
    fun `a folder addon row of duplicate pages ends when the merge says no more`() {
        var row = catalogRow(items = (0 until 20).map(::item), hasMore = true)
        val sim = Sim()
        repeat(6) {
            sim.focused = it // a move each step: the trigger's cap never binds
            sim.page = row.currentPage
            sim.hasMore = row.hasMore
            if (sim.observe()) row = row.mergeCatalogPage(catalogRow(items = (0 until 20).map(::item), hasMore = true))
        }
        assertFalse(row.hasMore)
        assertEquals(3, sim.fires) // DUPLICATE_PAGE_ADVANCE_LIMIT
    }

    private fun item(i: Int) = MetaPreview(
        id = "tt$i", type = ContentType.MOVIE, name = "Title $i", poster = null, posterShape = PosterShape.POSTER,
        background = null, logo = null, description = null, releaseInfo = null, imdbRating = null, genres = emptyList()
    )

    private fun catalogRow(items: List<MetaPreview>, hasMore: Boolean) = CatalogRow(
        addonId = "addon",
        addonName = "Addon",
        addonBaseUrl = "https://addon.example",
        catalogId = "top",
        catalogName = "Top",
        type = ContentType.MOVIE,
        items = items,
        hasMore = hasMore,
        supportsSkip = true
    )
}
