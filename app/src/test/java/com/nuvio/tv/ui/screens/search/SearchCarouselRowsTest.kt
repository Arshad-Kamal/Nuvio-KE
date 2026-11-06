package com.nuvio.tv.ui.screens.search

import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PLACEHOLDER_ITEM_ID_PREFIX
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.stableKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** U4e: Search's result rows on the Marquee band (sandbox `u4-search-discover-marquee` stages 5, 6). */
class SearchCarouselRowsTest {

    private fun meta(id: String) = MetaPreview(
        id = id, type = ContentType.MOVIE, name = "Title $id", poster = "https://img/$id.jpg",
        posterShape = PosterShape.POSTER, background = null, logo = null,
        description = null, releaseInfo = null, imdbRating = null, genres = emptyList()
    )

    private fun row(
        ids: List<String>,
        catalogName: String = "popular",
        addonName: String = "Cinemeta",
        isLoading: Boolean = false
    ) = CatalogRow(
        addonId = "cinemeta", addonName = addonName, addonBaseUrl = "https://cinemeta", catalogId = "top",
        catalogName = catalogName, type = ContentType.MOVIE, items = ids.map(::meta), isLoading = isLoading
    )

    private fun build(rows: List<CatalogRow>, addonName: Boolean = true, typeSuffix: Boolean = true) =
        searchCarouselRows(rows, showAddonName = addonName, showTypeSuffix = typeSuffix, typeLabel = { "Movie" })

    @Test
    fun `rows are keyed by the catalog and a repeated id gets its own card key`() {
        val source = row(listOf("tt1", "tt2", "tt1"))
        val result = build(listOf(source)).single()

        assertEquals(source.stableKey(), result.row.key)
        val keys = result.row.items.list.map { it.key }
        assertEquals(3, keys.toSet().size)
        assertEquals(listOf("tt1", "tt2", "tt1"), result.row.items.list.map { it.metaPreview?.id })
    }

    @Test
    fun `a placeholder card and the real card at its index share a key`() {
        val placeholder = row(listOf("${PLACEHOLDER_ITEM_ID_PREFIX}0"), isLoading = true)
        val real = row(listOf("tt9"))

        assertEquals(
            build(listOf(placeholder)).single().row.items.list.single().key,
            build(listOf(real)).single().row.items.list.single().key
        )
    }

    @Test
    fun `titles keep the as-built catalog name, type suffix and addon name`() {
        val on = build(listOf(row(listOf("tt1")))).single()
        assertEquals("Popular - Movie", on.row.title)
        assertEquals("Cinemeta", on.row.titleSuffix)

        val off = build(listOf(row(listOf("tt1"))), addonName = false, typeSuffix = false).single()
        assertEquals("Popular", off.row.title)
        assertNull(off.row.titleSuffix)

        // A nameless catalog draws no title at all, as built.
        val nameless = build(listOf(row(listOf("tt1"), catalogName = ""))).single()
        assertEquals("", nameless.row.title)
        assertNull(nameless.row.titleSuffix)
    }

    @Test
    fun `an unchanged row is reused and a changed one converts again`() {
        val first = build(listOf(row(listOf("tt1")), row(listOf("tt2"), catalogName = "series").copy(catalogId = "s")))
        val previous = first.associateBy { it.row.key }
        val again = searchCarouselRows(
            listOf(row(listOf("tt1")), row(listOf("tt3"), catalogName = "series").copy(catalogId = "s")),
            showAddonName = true, showTypeSuffix = true, typeLabel = { "Movie" }, previous = previous
        )

        assertSame(first[0], again[0])
        assertNotSame(first[1], again[1])
        assertEquals("tt3", again[1].row.items.list.single().metaPreview?.id)
    }

    @Test
    fun `see all follows the full-page rule and rows never load more`() {
        val full = build(listOf(row(List(15) { "tt$it" }))).single()
        assertTrue(full.showSeeAll)
        assertFalse(full.row.hasMore)
        assertFalse(full.row.supportsSkip)

        assertFalse(build(listOf(row(List(14) { "tt$it" }))).single().showSeeAll)
    }
}
