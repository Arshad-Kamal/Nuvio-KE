package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.ui.util.asStable
import org.junit.Assert.assertEquals
import org.junit.Test

/** P49: returning to Home gives every row its saved card back, not only the focused row. */
class SavedRowFocusIndicesTest {

    @Test
    fun `every saved row is seeded by its item key`() {
        val rows = mapOf(
            "cw" to row("cw", "a", "b", "c"),
            "popular" to row("popular", "p1", "p2", "p3", "p4")
        )
        val saved = mapOf("cw" to "b", "popular" to "p4")
        assertEquals(mapOf("cw" to 1, "popular" to 3), savedRowFocusIndices(rows, saved))
    }

    @Test
    fun `a card that moved is found by key, not by its old index`() {
        val rows = mapOf("popular" to row("popular", "new", "p1", "p2"))
        assertEquals(mapOf("popular" to 2), savedRowFocusIndices(rows, mapOf("popular" to "p2")))
    }

    @Test
    fun `blank keys, gone cards and gone rows are left out`() {
        val rows = mapOf("cw" to row("cw", "a"), "popular" to row("popular", "p1"))
        val saved = mapOf("cw" to "", "popular" to "gone", "removed" to "x")
        assertEquals(emptyMap<String, Int>(), savedRowFocusIndices(rows, saved))
    }

    private fun row(key: String, vararg itemKeys: String) = HeroCarouselRow(
        key = key,
        title = key,
        globalRowIndex = 0,
        items = itemKeys.map(::item).asStable()
    )

    private fun item(key: String) = ModernCarouselItem(
        key = key,
        title = key,
        subtitle = null,
        imageUrl = null,
        heroPreview = HeroPreview(
            title = key,
            logo = null,
            description = null,
            episodeLabel = null,
            yearText = null,
            imdbText = null,
            genres = emptyList<String>().asStable(),
            poster = null,
            backdrop = null,
            imageUrl = null
        ),
        payload = ModernPayload.Catalog(
            focusKey = "f_$key",
            itemId = key,
            itemType = "movie",
            addonBaseUrl = "",
            trailerTitle = key,
            trailerReleaseInfo = null,
            trailerApiType = "movie"
        )
    )
}
