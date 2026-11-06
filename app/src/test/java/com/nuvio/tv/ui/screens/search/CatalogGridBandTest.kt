package com.nuvio.tv.ui.screens.search

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.theme.NuvioMarquee
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 28c: the VM-free catalog grid band's pure pieces and the shared grid geometry. */
class CatalogGridBandTest {

    @Test
    fun `no row is an empty grid with no tile`() {
        val grid = catalogGrid(row = null, rowKey = "tab|0", landscapeSetting = false)
        assertEquals(0, grid.cellCount)
        assertEquals(DiscoverGridAction.None, grid.action)
        assertEquals("", grid.addonBaseUrl)
    }

    @Test
    fun `a loading row ends with the loading tile, a settled row with none`() {
        val loading = catalogGrid(row(7, isLoading = true), "tab|0", landscapeSetting = false)
        assertEquals(DiscoverGridAction.Loading, loading.action)
        assertEquals(8, loading.cellCount)
        assertEquals(2, loading.rowCount) // 7 posters, then the tile on its own row

        val settled = catalogGrid(row(7), "tab|0", landscapeSetting = false)
        assertEquals(DiscoverGridAction.None, settled.action)
        assertEquals(1, settled.rowCount)
        assertEquals("https://addon", settled.addonBaseUrl)
    }

    @Test
    fun `a first page still loading shows no lone tile`() {
        assertEquals(0, catalogGrid(row(0, isLoading = true), "tab|0", landscapeSetting = false).cellCount)
    }

    @Test
    fun `the card shape setting makes a landscape grid, a mixed row keeps each card's shape`() {
        assertEquals(GridForm.LANDSCAPE, catalogGrid(row(3), "k", landscapeSetting = true).form)
        assertEquals(GridForm.OWN, catalogGrid(row(3), "k", landscapeSetting = false).form)
        val mixed = row(2).let { it.copy(items = it.items + meta("wide", PosterShape.LANDSCAPE)) }
        assertEquals(GridForm.OWN, catalogGrid(mixed, "k", landscapeSetting = false).form)
        val wide = row(0).copy(items = listOf(meta("a", PosterShape.LANDSCAPE), meta("b", PosterShape.LANDSCAPE)))
        assertEquals(GridForm.LANDSCAPE, catalogGrid(wide, "k", landscapeSetting = false).form)
    }

    @Test
    fun `card keys follow the row key and stay distinct for repeated ids`() {
        val repeated = row(2).let { it.copy(items = it.items + it.items.first()) }
        val keys = catalogGrid(repeated, "tab|1", landscapeSetting = false).items.map { it.key }
        assertEquals(3, keys.distinct().size)
        assertTrue(keys.all { it.contains("tab|1") })
    }

    @Test
    fun `auto-load needs an active band, more pages, none in flight and a first page`() {
        assertTrue(catalogGridCanAutoLoad(row(5), active = true))
        assertFalse(catalogGridCanAutoLoad(row(5), active = false))
        assertFalse(catalogGridCanAutoLoad(row(5, isLoading = true), active = true))
        assertFalse(catalogGridCanAutoLoad(row(5, hasMore = false), active = true))
        assertFalse(catalogGridCanAutoLoad(row(0), active = true))
        assertFalse(catalogGridCanAutoLoad(null, active = true))
    }

    @Test
    fun `a restore clamps to the cards shown`() {
        assertEquals(3, catalogGridRestoreCell(savedCell = 3, itemCount = 10))
        assertEquals(9, catalogGridRestoreCell(savedCell = 40, itemCount = 10)) // a shorter list after process death
        assertEquals(0, catalogGridRestoreCell(savedCell = -1, itemCount = 10))
        assertNull(catalogGridRestoreCell(savedCell = 3, itemCount = 0))
    }

    @Test
    fun `grid geometry stacks tab row, lead and cards, and the hero fits above the row title`() {
        val marquee = NuvioMarquee.tokens
        val screenHeight = 540.dp
        val listTop = marquee.topBar.contentTop
        val rowTitleHeight = 20.dp
        for (form in GridForm.entries) {
            val g = gridBandGeometryOf(screenHeight, listTop, rowTitleHeight, form, marquee)
            val tabRowBottom = listTop + marquee.library.tabRowTop + marquee.search.filterPillHeight
            assertDp(discoverCardsTop(screenHeight, form, marquee), g.cardsTop)
            assertDp(g.cardsTop, tabRowBottom + g.itemGap + g.bandLeadHeight + g.itemGap)
            assertDp(g.cardsTop - marquee.home.rowTitleGap - rowTitleHeight, g.rowTitleTop)
            assertDp(screenHeight - g.heroBottomInset, g.rowTitleTop - marquee.home.heroAboveRow)
            assertDp(screenHeight - g.heroBottomInset, tabRowBottom + marquee.home.heroGap + g.heroMaxHeight)
            assertEquals(marquee.home.focusedCardBottomInset, g.bandBottomInset)
            assertEquals(marquee.search.gridGap, g.itemGap)
        }
    }

    @Test
    fun `without a tab row the lead is the list's first item`() {
        val marquee = NuvioMarquee.tokens
        val listTop = marquee.topBar.contentTop
        val g = gridBandGeometryOf(540.dp, listTop, 20.dp, GridForm.OWN, marquee, tabRow = false)
        assertDp(listTop, g.tabRowBottom)
        assertDp(g.cardsTop, listTop + g.bandLeadHeight + g.itemGap)
        assertDp(540.dp - g.heroBottomInset, listTop + marquee.home.heroGap + g.heroMaxHeight)
    }

    @Test
    fun `a window too short for the lead or the hero clamps them to zero`() {
        val g = gridBandGeometryOf(100.dp, NuvioMarquee.tokens.topBar.contentTop, 20.dp, GridForm.OWN, NuvioMarquee.tokens)
        assertEquals(0.dp, g.bandLeadHeight)
        assertEquals(0.dp, g.heroMaxHeight)
    }

    private fun assertDp(expected: Dp, actual: Dp) = assertEquals(expected.value, actual.value, 0.01f)

    private fun row(count: Int, isLoading: Boolean = false, hasMore: Boolean = true) = CatalogRow(
        addonId = "addon",
        addonName = "Addon",
        addonBaseUrl = "https://addon",
        catalogId = "popular",
        catalogName = "Popular",
        type = ContentType.MOVIE,
        items = (0 until count).map { meta("m$it") },
        isLoading = isLoading,
        hasMore = hasMore
    )

    private fun meta(id: String, shape: PosterShape = PosterShape.POSTER) = MetaPreview(
        id = id,
        type = ContentType.MOVIE,
        name = id,
        poster = "https://img/$id.jpg",
        posterShape = shape,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList()
    )
}
