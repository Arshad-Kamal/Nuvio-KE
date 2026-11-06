package com.nuvio.tv.ui.screens

import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Wave C S6c: See All reads only the Search row it was opened for. */
class CatalogSeeAllRulesTest {

    private fun row(addonId: String, catalogId: String, type: ContentType = ContentType.MOVIE, hasMore: Boolean = true, isLoading: Boolean = false) =
        CatalogRow(
            addonId = addonId,
            addonName = "Addon",
            addonBaseUrl = "https://$addonId.example",
            catalogId = catalogId,
            catalogName = "Cat",
            type = type,
            items = emptyList(),
            hasMore = hasMore,
            isLoading = isLoading
        )

    @Test
    fun `row lookup matches addon, type and catalog`() {
        val movie = row("a", "top")
        val series = row("a", "top", type = ContentType.SERIES)
        val rows = listOf(row("b", "top"), movie, series)
        assertEquals(movie, seeAllRow(rows, "a", "movie", "top"))
        assertEquals(series, seeAllRow(rows, "a", "series", "top"))
        assertNull(seeAllRow(rows, "a", "movie", "new"))
    }
}
