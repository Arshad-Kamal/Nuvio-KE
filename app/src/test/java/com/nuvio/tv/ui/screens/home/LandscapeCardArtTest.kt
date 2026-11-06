package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** T31 (P40): a landscape card freezes real landscape art only, never the poster fallback. */
class LandscapeCardArtTest {

    private val row = CatalogRow(
        addonId = "a",
        addonName = "A",
        addonBaseUrl = "https://a.example",
        catalogId = "top",
        catalogName = "Top",
        type = ContentType.MOVIE,
        items = emptyList()
    )

    @Test
    fun `a poster-only item freezes nothing, and the first real backdrop then sticks`() {
        val posterOnly = item(background = null)
        val first = buildCatalogItem(posterOnly, row, useLandscapePosters = true, occurrence = 0)
        assertNull(first.heroPreview.frozenBackdropUrl)

        val enriched = buildCatalogItem(
            item(background = "https://img/bg.jpg"), row, true, 0, previousCachedItem = first
        )
        assertEquals("https://img/bg.jpg", enriched.heroPreview.frozenBackdropUrl)

        val later = buildCatalogItem(
            item(background = "https://img/other.jpg"), row, true, 0, previousCachedItem = enriched
        )
        assertEquals("https://img/bg.jpg", later.heroPreview.frozenBackdropUrl)
    }

    @Test
    fun `a landscape poster counts as landscape art`() {
        val built = buildCatalogItem(
            item(background = null, landscapePoster = "https://img/land.jpg"), row, true, 0
        )
        assertEquals("https://img/land.jpg", built.heroPreview.frozenBackdropUrl)
    }

    @Test
    fun `enrichment keeps landscape art only, never a poster fallback`() {
        val current = item(background = "https://img/current.jpg")
        assertEquals("https://img/current.jpg", enrichedBackground(meta(background = null), current))
        assertEquals(
            "https://img/land.jpg",
            enrichedBackground(meta(background = null, landscapePoster = "https://img/land.jpg"), current)
        )
        assertEquals("https://img/new.jpg", enrichedBackground(meta(background = "https://img/new.jpg"), current))
        assertNull(enrichedBackground(meta(background = "  "), item(background = null)))
    }

    private fun meta(background: String?, landscapePoster: String? = null) = Meta(
        id = "tt1",
        type = ContentType.MOVIE,
        name = "Movie",
        poster = "https://img/poster.jpg",
        posterShape = PosterShape.POSTER,
        background = background,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList(),
        runtime = null,
        director = emptyList(),
        cast = emptyList(),
        videos = emptyList(),
        links = emptyList(),
        country = null,
        awards = null,
        language = null,
        landscapePoster = landscapePoster
    )

    private fun item(background: String?, landscapePoster: String? = null) = MetaPreview(
        id = "tt1",
        type = ContentType.MOVIE,
        name = "Movie",
        poster = "https://img/poster.jpg",
        posterShape = PosterShape.POSTER,
        background = background,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList(),
        landscapePoster = landscapePoster
    )
}
