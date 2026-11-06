package com.nuvio.tv.ui.screens.home

import androidx.compose.ui.unit.Dp
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.CollectionFolder
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PLACEHOLDER_ITEM_ID_PREFIX
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.theme.NuvioMarquee
import com.nuvio.tv.ui.util.asStable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Card shape rule (Spec 41, u41-addon-card-shapes v2): each card its addon's shape, Landscape overrides. */
class CardShapeRuleTest {

    private val home = NuvioMarquee.tokens.home
    private val catalog = CatalogRow(
        addonId = "a",
        addonName = "A",
        addonBaseUrl = "https://a.example",
        catalogId = "top",
        catalogName = "Top",
        type = ContentType.MOVIE,
        items = emptyList()
    )

    @Test
    fun `a row is landscape when the setting says so or every card is landscape`() {
        val mixed = row(card("1", PosterShape.POSTER), card("2", PosterShape.LANDSCAPE))
        assertFalse(mixed.usesLandscapeCards(useLandscapePosters = false))
        assertTrue(mixed.usesLandscapeCards(useLandscapePosters = true))
        val wide = row(card("1", PosterShape.LANDSCAPE), card("2", PosterShape.LANDSCAPE))
        assertTrue(wide.usesLandscapeCards(useLandscapePosters = false))
        assertFalse(row().usesLandscapeCards(useLandscapePosters = false))
    }

    @Test
    fun `placeholder cards don't decide the row`() {
        val placeholder = card("${PLACEHOLDER_ITEM_ID_PREFIX}x_0", PosterShape.POSTER)
        assertTrue(row(placeholder, card("2", PosterShape.LANDSCAPE)).usesLandscapeCards(useLandscapePosters = false))
        assertFalse(row(placeholder).usesLandscapeCards(useLandscapePosters = false))
    }

    @Test
    fun `outside a landscape row each card keeps its own shape at the poster height`() {
        assertEquals(size(home.portraitCardWidth), card("1", PosterShape.POSTER).marqueeCardSize(false, false, home))
        assertEquals(size(home.squareCardWidth), card("1", PosterShape.SQUARE).marqueeCardSize(false, false, home))
        assertEquals(size(home.wideCardWidth), card("1", PosterShape.LANDSCAPE).marqueeCardSize(false, false, home))
        val landscape = ModernCatalogCardMetrics(home.landscapeCardWidth, home.landscapeCardHeight)
        assertEquals(landscape, card("1", PosterShape.SQUARE).marqueeCardSize(true, false, home))
        val mixed = row(card("1", PosterShape.LANDSCAPE), card("2", PosterShape.POSTER))
        assertEquals(home.portraitCardHeight, mixed.marqueeCardHeight(false, false, home))
        assertEquals(home.landscapeCardHeight, row(card("1", PosterShape.POSTER)).marqueeCardHeight(true, false, home))
        // The trailing tile takes the plain slot, not a wide first card's width.
        assertEquals(size(home.portraitCardWidth), mixed.marqueeRowSlotSize(false, false, home))
        assertEquals(landscape, mixed.marqueeRowSlotSize(true, false, home))
    }

    @Test
    fun `a landscape item shows its own poster, others the backdrop in a landscape row`() {
        val wide = card("1", PosterShape.LANDSCAPE, background = "https://img/bg.jpg")
        assertEquals("https://img/1.jpg", spec(wide, landscape = true).url)
        assertEquals("https://img/1.jpg", spec(wide, landscape = false).url)
        // Override: a poster item shows its backdrop, or its poster (cropped) without one.
        assertEquals("https://img/bg.jpg", spec(card("1", PosterShape.POSTER, background = "https://img/bg.jpg"), landscape = true).url)
        assertEquals("https://img/1.jpg", spec(card("1", PosterShape.POSTER), landscape = true).url)
        assertEquals("https://img/1.jpg", spec(card("1", PosterShape.SQUARE, background = "https://img/bg.jpg"), landscape = false).url)
        // A landscape item without a poster falls back to its backdrop.
        val posterless = card("1", PosterShape.LANDSCAPE, background = "https://img/bg.jpg", poster = null)
        assertEquals("https://img/bg.jpg", spec(posterless, landscape = false).url)
    }

    @Test
    fun `folder tiles take their own Tile shape unless the setting is Landscape`() {
        val folder = buildCollectionFolderItem(
            Collection(id = "c", title = "C", folders = emptyList()),
            CollectionFolder(id = "f", title = "F", coverImageUrl = "https://img/cover.jpg", tileShape = PosterShape.SQUARE)
        )
        assertEquals(PosterShape.SQUARE, folder.cardShape())
        assertEquals(size(home.squareCardWidth), folder.marqueeCardSize(false, false, home))
        assertTrue(row(folder).usesLandscapeCards(useLandscapePosters = true))
        assertEquals("https://img/cover.jpg", spec(folder, landscape = false).url)
    }

    private fun size(width: Dp) = ModernCatalogCardMetrics(width, home.portraitCardHeight)

    private fun spec(item: ModernCarouselItem, landscape: Boolean) =
        item.marqueeImageRequestSpec(landscape, false, false, false, home)!!

    private fun row(vararg items: ModernCarouselItem) =
        HeroCarouselRow(key = "r", title = "R", globalRowIndex = 0, items = items.toList().asStable())

    private fun card(
        id: String,
        shape: PosterShape,
        background: String? = null,
        poster: String? = "https://img/$id.jpg"
    ) = buildCatalogItem(
        MetaPreview(
            id = id,
            type = ContentType.MOVIE,
            name = "Title $id",
            poster = poster,
            posterShape = shape,
            background = background,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList()
        ),
        catalog,
        useLandscapePosters = false,
        occurrence = 0
    )
}
