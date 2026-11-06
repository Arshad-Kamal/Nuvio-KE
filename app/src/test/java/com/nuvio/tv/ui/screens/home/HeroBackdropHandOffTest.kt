package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.ContinueWatchingItem
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PLACEHOLDER_IMAGE_URL
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.ui.util.asStable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** P32: Details opens on the clicked title's art, never the debounced hero's. */
class HeroBackdropHandOffTest {

    @After
    fun clear() {
        HeroBackdropState.consumeAndClear()
    }

    @Test
    fun `consumeForTitle returns the art written for that title and clears it`() {
        HeroBackdropState.updateForTitle("tt1", "movie", "https://img/a.jpg")
        assertEquals("https://img/a.jpg", HeroBackdropState.consumeForTitle("tt1", "movie"))
        assertNull(HeroBackdropState.consumeForTitle("tt1", "movie"))
    }

    @Test
    fun `another title, or the same id with another type, gets nothing and still clears`() {
        HeroBackdropState.updateForTitle("tt1", "movie", "https://img/a.jpg")
        assertNull(HeroBackdropState.consumeForTitle("tt2", "movie"))
        HeroBackdropState.updateForTitle("tt1", "movie", "https://img/a.jpg")
        assertNull(HeroBackdropState.consumeForTitle("tt1", "series"))
        assertNull(HeroBackdropState.consumeAndClear())
    }

    @Test
    fun `an untitled write is never handed to a title-checked route`() {
        HeroBackdropState.updateForTitle("tt1", "movie", "https://img/a.jpg")
        HeroBackdropState.update("https://img/hero.jpg")
        assertNull(HeroBackdropState.consumeForTitle("tt1", "movie"))
    }

    @Test
    fun `clicked catalog card hands off its enriched backdrop when known`() {
        val rows = listOf(row(catalogCard("tt1", backdrop = "https://img/addon.jpg")))
        assertEquals("https://img/addon.jpg", clickedTitleBackdrop(rows, "tt1", emptyMap()))
        val enriched = mapOf("tt1" to meta("tt1", background = "https://img/tmdb.jpg"))
        assertEquals("https://img/tmdb.jpg", clickedTitleBackdrop(rows, "tt1", enriched))
    }

    @Test
    fun `the clicked card is found in any row, never a neighbour's art`() {
        val rows = listOf(
            row(catalogCard("tt1", backdrop = "https://img/one.jpg")),
            row(catalogCard("tt2", backdrop = "https://img/two.jpg"))
        )
        assertEquals("https://img/two.jpg", clickedTitleBackdrop(rows, "tt2", emptyMap()))
        assertNull(clickedTitleBackdrop(rows, "tt9", emptyMap()))
    }

    @Test
    fun `placeholder art is skipped for the card image, then nothing`() {
        val card = catalogCard("tt1", backdrop = PLACEHOLDER_IMAGE_URL, imageUrl = "https://img/card.jpg")
        assertEquals("https://img/card.jpg", clickedTitleBackdrop(listOf(row(card)), "tt1", emptyMap()))
        val bare = catalogCard("tt2", backdrop = PLACEHOLDER_IMAGE_URL, imageUrl = PLACEHOLDER_IMAGE_URL)
        assertNull(clickedTitleBackdrop(listOf(row(bare)), "tt2", emptyMap()))
    }

    @Test
    fun `enrichment without art falls back to the card image`() {
        val rows = listOf(row(catalogCard("tt1", backdrop = "https://img/addon.jpg", imageUrl = "https://img/card.jpg")))
        val enriched = mapOf("tt1" to meta("tt1", background = null))
        assertEquals("https://img/card.jpg", clickedTitleBackdrop(rows, "tt1", enriched))
    }

    @Test
    fun `withEnrichment copies the enriched fields and keeps year and runtime when it has none`() {
        val base = preview(backdrop = "https://img/addon.jpg").copy(yearText = "2001", runtimeText = "1h 30m")
        val enriched = meta("tt1", background = "https://img/tmdb.jpg").copy(
            type = ContentType.SERIES,
            name = "Enriched",
            logo = "https://img/logo.png",
            description = "About",
            imdbRating = 7.0f,
            genres = listOf("A", "B", "C", "D"),
            poster = "https://img/poster.jpg"
        )
        val merged = base.withEnrichment(enriched)
        assertEquals("Enriched", merged.title)
        assertEquals("https://img/logo.png", merged.logo)
        assertEquals("About", merged.description)
        assertEquals(true, merged.isSeries)
        assertEquals("2001", merged.yearText)
        assertEquals("1h 30m", merged.runtimeText)
        assertEquals("7.0", merged.imdbText)
        assertEquals(listOf("A", "B", "C"), merged.genres.list)
        assertEquals("https://img/poster.jpg", merged.poster)
        assertEquals("https://img/tmdb.jpg", merged.backdrop)
        assertEquals(base, base.withEnrichment(null))
    }

    @Test
    fun `continue watching card is matched by its content id, a catalog card for the title wins`() {
        val cwItem = ContinueWatchingItem.InProgress(
            WatchProgress(
                contentId = "tt5",
                contentType = "series",
                name = "tt5",
                poster = null,
                backdrop = null,
                logo = null,
                videoId = "tt5:1:1",
                season = 1,
                episode = 1,
                episodeTitle = null,
                position = 40L,
                duration = 100L,
                lastWatched = 100L
            )
        )
        val card = ModernCarouselItem(
            key = "cw_tt5",
            title = "CW",
            subtitle = null,
            imageUrl = null,
            heroPreview = preview(backdrop = "https://img/cw.jpg"),
            payload = ModernPayload.ContinueWatching(cwItem)
        )
        assertEquals("https://img/cw.jpg", clickedTitleBackdrop(listOf(row(card)), "tt5", emptyMap()))
        // Continue Watching leads Home; a catalog card for the same title still hands off its own art.
        val rows = listOf(row(card), row(catalogCard("tt5", backdrop = "https://img/show.jpg")))
        assertEquals("https://img/show.jpg", clickedTitleBackdrop(rows, "tt5", emptyMap()))
    }

    private fun preview(backdrop: String?, imageUrl: String? = null) = HeroPreview(
        title = "T",
        logo = null,
        description = null,
        episodeLabel = null,
        yearText = null,
        imdbText = null,
        genres = emptyList<String>().asStable(),
        poster = null,
        backdrop = backdrop,
        imageUrl = imageUrl
    )

    private fun catalogCard(id: String, backdrop: String?, imageUrl: String? = null) = ModernCarouselItem(
        key = "catalog_$id",
        title = id,
        subtitle = null,
        imageUrl = imageUrl,
        heroPreview = preview(backdrop, imageUrl),
        payload = ModernPayload.Catalog(
            focusKey = "f_$id",
            itemId = id,
            itemType = "movie",
            addonBaseUrl = "",
            trailerTitle = id,
            trailerReleaseInfo = null,
            trailerApiType = "movie"
        ),
        metaPreview = meta(id, background = null)
    )

    private fun row(vararg cards: ModernCarouselItem) = HeroCarouselRow(
        key = "row_${cards.first().key}",
        title = "Row",
        globalRowIndex = 0,
        items = cards.toList().asStable()
    )

    private fun meta(id: String, background: String?) = MetaPreview(
        id = id,
        type = ContentType.MOVIE,
        name = id,
        poster = null,
        posterShape = PosterShape.POSTER,
        background = background,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList()
    )
}
