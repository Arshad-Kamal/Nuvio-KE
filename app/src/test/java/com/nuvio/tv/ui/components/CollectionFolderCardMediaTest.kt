package com.nuvio.tv.ui.components

import com.nuvio.tv.domain.model.CollectionFolder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the folder-cover fallback contract (Arshad decision 2026-08-31): a
 * load-failed URL is blacklisted and resolves to null so every folder surface
 * renders the designed emoji/initials tile instead of a dead request; the
 * holder records the failure for the immediate in-place fallback.
 */
class CollectionFolderCardMediaTest {

    @After
    fun resetBlacklist() {
        brokenImageUrls.clear()
    }

    private fun folder(url: String?) = CollectionFolder(
        id = "f1",
        title = "Sci-Fi",
        coverImageUrl = url
    )

    @Test
    fun `resolves the cover URL when healthy`() {
        assertEquals(
            "https://example.com/cover.jpg",
            collectionFolderCardImageUrl(folder("https://example.com/cover.jpg"))
        )
    }

    @Test
    fun `blank URL resolves null`() {
        assertEquals(null, collectionFolderCardImageUrl(folder("  ")))
        assertEquals(null, collectionFolderCardImageUrl(folder(null)))
    }

    @Test
    fun `blacklisted URL resolves null so the fallback tile renders`() {
        val url = "https://example.com/dead.jpg"
        brokenImageUrls.add(url)
        assertEquals(null, collectionFolderCardImageUrl(folder(url)))
    }

    @Test
    fun `onCoverError blacklists the URL and flags failure`() {
        val url = "https://example.com/failing.jpg"
        val fallback = FolderCoverFallback()
        assertFalse(fallback.failed)
        assertFalse(url in brokenImageUrls)

        fallback.onCoverError(url)

        assertTrue(fallback.failed)
        assertTrue(url in brokenImageUrls)
        // And the resolver now skips it for every other folder surface.
        assertEquals(null, collectionFolderCardImageUrl(folder(url)))
    }

    @Test
    fun `onCoverError ignores null and blank URLs`() {
        val fallback = FolderCoverFallback()
        fallback.onCoverError(null)
        fallback.onCoverError("  ")
        assertTrue(fallback.failed)
    }
}
