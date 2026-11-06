package com.nuvio.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P4 pins for subtitle addon id routing (resolveSubtitleRequestId): content
 * keyed tmdb:/mal:/kitsu: must still reach subtitle addons that declare only
 * `tt`, via the playing video id or the meta IMDb id.
 */
class SubtitleRequestIdRoutingTest {

    private val ttOnly = listOf("tt")

    @Test
    fun `matching content id keeps the playing video id as before`() {
        assertEquals(
            "tt0944947:1:2",
            resolveSubtitleRequestId(ttOnly, "tt0944947", "tt0944947:1:2", "tt9999999", 1, 2)
        )
        assertEquals(
            "tt1375666",
            resolveSubtitleRequestId(ttOnly, "tt1375666", null, null, null, null)
        )
    }

    @Test
    fun `no declared prefixes accepts the content route unchanged`() {
        assertEquals(
            "tmdb:1399:1:2",
            resolveSubtitleRequestId(null, "tmdb:1399", "tmdb:1399:1:2", "tt0944947", 1, 2)
        )
    }

    @Test
    fun `tmdb movie falls back to the imdb id for a tt-only addon`() {
        assertEquals(
            "tt1375666",
            resolveSubtitleRequestId(ttOnly, "tmdb:27205", "tmdb:27205", "tt1375666", null, null)
        )
    }

    @Test
    fun `series imdb fallback carries season and episode in stremio form`() {
        assertEquals(
            "tt0944947:3:9",
            resolveSubtitleRequestId(ttOnly, "tmdb:1399", "tmdb:1399:3:9", "tt0944947", 3, 9)
        )
    }

    @Test
    fun `playing tt video id is used when only the content id is foreign`() {
        assertEquals(
            "tt0944947:1:2",
            resolveSubtitleRequestId(ttOnly, "kitsu:1376", "tt0944947:1:2", null, 1, 2)
        )
    }

    @Test
    fun `content id is preferred over the imdb id when both match`() {
        assertEquals(
            "kitsu:1376:5",
            resolveSubtitleRequestId(
                listOf("kitsu:", "tt"), "kitsu:1376", "kitsu:1376:5", "tt0409591", 1, 5
            )
        )
    }

    @Test
    fun `no matching candidate skips the addon`() {
        assertNull(resolveSubtitleRequestId(ttOnly, "mal:20", "mal:20:1", null, 1, 1))
        assertNull(resolveSubtitleRequestId(listOf("kitsu:"), "tmdb:1399", null, "tt0944947", null, null))
        assertNull(resolveSubtitleRequestId(ttOnly, "tmdb:1399", null, "  ", null, null))
    }
}
