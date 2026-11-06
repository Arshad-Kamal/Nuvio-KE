package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Spec 26 slice 26a (sandbox data-additions-marquee st1): a catalog title's hero carries the facts
 * the eyebrow, meta row and credit line draw (B1 season count, T13 status + credits, X3 age rating),
 * enrichment fills them without blanking known ones, and B6's row suffix follows the setting.
 */
class HeroTitleFactsTest {

    private val row = CatalogRow(
        addonId = "a",
        addonName = "Cinemeta",
        addonBaseUrl = "https://a.example",
        catalogId = "top",
        catalogName = "Top",
        type = ContentType.SERIES,
        items = emptyList()
    )

    @Test
    fun `a catalog item's hero carries the title facts`() {
        val hero = buildCatalogItem(
            item(seasonCount = 2, status = "Ended", ageRating = "14A", director = listOf("Colin Levy")),
            row, useLandscapePosters = false, occurrence = 0
        ).heroPreview

        assertEquals("series", hero.apiType)
        assertEquals(true, hero.isSeries)
        assertEquals(2, hero.seasonCount)
        assertEquals("Ended", hero.status)
        assertEquals("14A", hero.ageRating)
        assertEquals(listOf("Colin Levy"), hero.directors.list)
        assertEquals(emptyList<String>(), hero.writers.list)
    }

    @Test
    fun `enrichment fills missing facts and keeps known ones it lacks`() {
        val hero = buildCatalogItem(item(seasonCount = 2, writer = listOf("Esther Wouda")), row, false, 0).heroPreview

        val enriched = hero.withEnrichment(
            item(status = "Ended", ageRating = "14A", director = listOf("Colin Levy"))
        )

        assertEquals(2, enriched.seasonCount)
        assertEquals("Ended", enriched.status)
        assertEquals("14A", enriched.ageRating)
        assertEquals(listOf("Colin Levy"), enriched.directors.list)
        assertEquals(listOf("Esther Wouda"), enriched.writers.list)
    }

    @Test
    fun `the row suffix is the addon name only when the setting is on`() {
        assertEquals("Cinemeta", catalogRowTitleSuffix("Cinemeta", showAddonName = true))
        assertNull(catalogRowTitleSuffix("Cinemeta", showAddonName = false))
        assertNull(catalogRowTitleSuffix("  ", showAddonName = true))
    }

    private fun item(
        seasonCount: Int? = null,
        status: String? = null,
        ageRating: String? = null,
        director: List<String> = emptyList(),
        writer: List<String> = emptyList()
    ) = MetaPreview(
        id = "tt1",
        type = ContentType.SERIES,
        name = "Sintel",
        poster = "https://img/p.jpg",
        posterShape = PosterShape.POSTER,
        background = "https://img/bg.jpg",
        logo = null,
        description = "A lonely young woman befriends a baby dragon.",
        releaseInfo = "2010",
        imdbRating = 7.4f,
        genres = listOf("Fantasy"),
        status = status,
        ageRating = ageRating,
        director = director,
        writer = writer,
        seasonCount = seasonCount
    )
}
