package com.nuvio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the progress key byte-for-byte: it is the local store's map key and Supabase's
 * progress_key, so any change orphans stored progress and forks synced rows.
 */
class WatchProgressKeyTest {

    @Test
    fun `episode key is contentId_sSeasonEEpisode`() {
        assertEquals("tt0944947_s1e2", watchProgressKey("tt0944947", 1, 2))
        assertEquals("tmdb:1399_s10e105", watchProgressKey("tmdb:1399", 10, 105))
        assertEquals("tt0944947_s0e1", watchProgressKey("tt0944947", 0, 1))
    }

    @Test
    fun `movie or partial episode key is the bare contentId`() {
        assertEquals("tt0111161", watchProgressKey("tt0111161", null, null))
        assertEquals("tt0111161", watchProgressKey("tt0111161", 1, null))
        assertEquals("tt0111161", watchProgressKey("tt0111161", null, 3))
    }

    @Test
    fun `entry key matches the function`() {
        val episode = WatchProgress(
            contentId = "tt0944947",
            contentType = "series",
            name = "Show",
            poster = null,
            backdrop = null,
            logo = null,
            videoId = "tt0944947:3:4",
            season = 3,
            episode = 4,
            episodeTitle = null,
            position = 1L,
            duration = 2L,
            lastWatched = 0L
        )

        assertEquals("tt0944947_s3e4", episode.progressKey)
        assertEquals("tt0944947", episode.copy(season = null, episode = null).progressKey)
    }
}
