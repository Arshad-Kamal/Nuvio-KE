package com.nuvio.tv.ui.screens.search

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import androidx.compose.ui.unit.dp
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.theme.NuvioMarquee
import com.nuvio.tv.ui.util.asStable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Discover's Marquee band (cards packed into lines, Spec 41): grid index math, auto-load, row subtitle, card keys. */
class DiscoverBandTest {

    private val marquee = NuvioMarquee.tokens

    @Test
    fun `plain cells pack 7 posters, 4 landscape or 5 square cards a line`() {
        assertEquals(7, gridCellsPerLine(GridForm.OWN, marquee))
        assertEquals(4, gridCellsPerLine(GridForm.LANDSCAPE, marquee))
        assertEquals(5, gridCellsPerLine(GridForm.SQUARE, marquee))
        val posters = grid(List(7) { PosterShape.POSTER }, DiscoverGridAction.LoadMore)
        assertEquals(2, posters.rowCount) // 7 results + Load more tile
        assertEquals(0, posters.rowOf(6))
        assertEquals(1, posters.rowOf(7))
        assertEquals(0..6, posters.cellsOf(0))
        assertEquals(IntRange.EMPTY, posters.cellsOf(2))
        val wide = grid(List(8) { PosterShape.LANDSCAPE })
        assertEquals(GridForm.LANDSCAPE, wide.form)
        assertEquals(2, wide.rowCount)
        assertEquals(1, wide.rowOf(4))
    }

    @Test
    fun `a mixed grid packs each card in its own shape`() {
        // 112 + 299 + 168 + 112 + 112 + 4 gaps = 851; the sixth card (+ 124) passes 864.
        val shapes = listOf(PosterShape.POSTER, PosterShape.LANDSCAPE, PosterShape.SQUARE, PosterShape.POSTER, PosterShape.POSTER, PosterShape.POSTER)
        val mixed = grid(shapes)
        assertEquals(GridForm.OWN, mixed.form)
        assertEquals(0..4, mixed.cellsOf(0))
        assertEquals(5..5, mixed.cellsOf(1))
        assertEquals(1, mixed.rowOf(5))
        assertEquals(0, mixed.columnOf(5))
        assertEquals(GridForm.SQUARE, discoverItems(List(3) { PosterShape.SQUARE }).gridForm(false))
        assertEquals(GridForm.LANDSCAPE, discoverItems(shapes).gridForm(true))
    }

    @Test
    fun `a card wider than the line still gets one`() {
        assertEquals(listOf(0, 1, 2), packGridLines(listOf(900.dp, 900.dp, 10.dp), 864.dp, 12.dp).toList())
        assertEquals(0, packGridLines(emptyList(), 864.dp, 12.dp).size)
    }

    @Test
    fun `landing keeps the column and clamps to the row's last result`() {
        val starts = intArrayOf(0, 7, 14)
        assertEquals(10, gridLandingCell(starts, row = 1, column = 3, contentCount = 20))
        assertEquals(16, gridLandingCell(starts, row = 2, column = 5, contentCount = 17)) // short last row
        assertEquals(0, gridLandingCell(starts, row = 0, column = -1, contentCount = 20))
        assertNull(gridLandingCell(intArrayOf(0, 7), row = 1, column = 0, contentCount = 7)) // tile-only row
        assertNull(gridLandingCell(starts, row = -1, column = 0, contentCount = 7))
        assertNull(gridLandingCell(starts, row = 3, column = 0, contentCount = 30))
        // Uneven lines (a mixed grid): the column clamps to its own line, not the next one's start.
        val uneven = intArrayOf(0, 5, 6)
        assertEquals(4, gridLandingCell(uneven, row = 0, column = 6, contentCount = 9))
        assertEquals(5, gridLandingCell(uneven, row = 1, column = 3, contentCount = 9))
    }

    @Test
    fun `an empty grid has no rows, and a square grid's tile takes the square cell`() {
        val empty = grid(emptyList(), DiscoverGridAction.LoadMore)
        assertEquals(0, empty.cellCount)
        assertEquals(0, empty.rowCount)
        assertEquals(0, empty.rowOf(3))
        assertEquals(IntRange.EMPTY, empty.cellsOf(0))
        assertNull(empty.landingCell(0, 0))
        val squares = grid(List(5) { PosterShape.SQUARE }, DiscoverGridAction.LoadMore)
        assertEquals(GridForm.SQUARE, squares.form)
        assertEquals(0..4, squares.cellsOf(0))
        assertEquals(5..5, squares.cellsOf(1)) // the tile opens a line
        assertEquals(marquee.search.squareGridCardSize, gridCellSize(null, GridForm.SQUARE, marquee).width)
    }

    private fun discoverItems(shapes: List<PosterShape>) =
        discoverCarouselItems(shapes.mapIndexed { i, shape -> shapedMeta("s$i", shape) }, "k", "https://addon")

    private fun grid(shapes: List<PosterShape>, action: DiscoverGridAction = DiscoverGridAction.None): DiscoverGrid {
        val items = discoverItems(shapes)
        return DiscoverGrid(items.asStable(), action, "https://addon", items.gridForm(false), marquee)
    }

    private fun shapedMeta(id: String, shape: PosterShape) = MetaPreview(
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

    @Test
    fun `auto-load starts within two rows of the end`() {
        assertFalse(discoverShouldAutoLoad(lastLaidOutRow = 1, rowCount = 15))
        assertTrue(discoverShouldAutoLoad(lastLaidOutRow = 13, rowCount = 15))
        assertTrue(discoverShouldAutoLoad(lastLaidOutRow = 0, rowCount = 2))
        assertFalse(discoverShouldAutoLoad(lastLaidOutRow = -1, rowCount = 0))
    }

    @Test
    fun `row subtitle joins addon, type and genre, skipping absent parts`() {
        assertEquals("Cinemeta · Movie · Animation", discoverRowSubtitle("Cinemeta", "Movie", "Animation"))
        assertEquals("Cinemeta · Animation", discoverRowSubtitle("Cinemeta", null, "Animation"))
        assertEquals("Cinemeta", discoverRowSubtitle("Cinemeta", "", null))
    }

    @Test
    fun `compact hero context is catalog, addon, genre`() {
        assertEquals("Popular · Cinemeta · Animation", discoverHeroContext("Popular", "Cinemeta", "Animation"))
        assertEquals("Popular · Cinemeta", discoverHeroContext("Popular", "Cinemeta", null))
    }

    @Test
    fun `compact band anchor puts the focused row's top at 214 dp`() {
        val marquee = NuvioMarquee.tokens
        // Q4 B: a 168 dp poster row from y 214 ends at 382, 158 above a 540 dp screen's bottom.
        assertEquals(158f, compactBandBottomInset(540.dp, 168.dp, marquee).value, 0.01f)
        // A result row's title (20) and gap (12) sit above its cards.
        assertEquals(126f, compactBandBottomInset(540.dp, 168.dp, marquee, rowHeadHeight = 32.dp).value, 0.01f)
        assertEquals(0f, compactBandBottomInset(300.dp, 168.dp, marquee).value, 0.01f)
    }

    @Test
    fun `repeated ids get distinct card keys`() {
        val items = discoverCarouselItems(listOf(meta("a"), meta("b"), meta("a")), "discover|k", "https://addon")
        assertEquals(3, items.map { it.key }.distinct().size)
        assertEquals(listOf("a", "b", "a"), items.map { it.metaPreview?.id })
    }

    private fun meta(id: String) = MetaPreview(
        id = id,
        type = ContentType.MOVIE,
        name = id,
        poster = "https://img/$id.jpg",
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList()
    )
}
