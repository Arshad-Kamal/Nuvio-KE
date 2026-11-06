package com.nuvio.tv.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

/** B25: the single cached next-up mapping keeps every field, contentLanguage included. */
class CachedNextUpItemMappingTest {

    private val cached = CachedNextUpItem(
        contentId = "tt0944947",
        contentType = "series",
        name = "Show",
        poster = "p",
        backdrop = "b",
        logo = "l",
        videoId = "tt0944947:3:9",
        season = 3,
        episode = 9,
        episodeTitle = "Ep",
        episodeDescription = "Desc",
        thumbnail = "t",
        released = "2024-01-01",
        hasAired = false,
        airDateLabel = "Soon",
        lastWatched = 100L,
        imdbRating = 8.5f,
        genres = listOf("Drama"),
        releaseInfo = "2011",
        sortTimestamp = 200L,
        releaseTimestamp = 300L,
        isReleaseAlert = true,
        isNewSeasonRelease = true,
        seedSeason = 3,
        seedEpisode = 8,
        contentLanguage = "en"
    )

    @Test
    fun `default mapping copies every field`() {
        val info = cached.toNextUpInfo()
        assertEquals("en", info.contentLanguage)
        assertEquals(cached.videoId, info.videoId)
        assertEquals(false, info.hasAired)
        assertEquals(200L, info.sortTimestamp)
        assertEquals(true, info.isReleaseAlert)
        assertEquals(8, info.seedEpisode)
        assertEquals(300L, info.releaseTimestamp)
    }

    @Test
    fun `recalculated badge flags and sort timestamp override the cached ones`() {
        val info = cached.toNextUpInfo(
            hasAired = true,
            isReleaseAlert = false,
            isNewSeasonRelease = false,
            sortTimestamp = 100L
        )
        assertEquals(true, info.hasAired)
        assertEquals(false, info.isReleaseAlert)
        assertEquals(false, info.isNewSeasonRelease)
        assertEquals(100L, info.sortTimestamp)
        assertEquals("en", info.contentLanguage)
    }
}
