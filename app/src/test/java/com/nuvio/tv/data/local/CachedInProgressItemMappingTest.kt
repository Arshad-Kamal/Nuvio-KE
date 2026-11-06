package com.nuvio.tv.data.local

import com.nuvio.tv.domain.model.ContinueWatchingItem
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.ui.components.ExpiringUrlBlacklist
import com.nuvio.tv.ui.screens.home.toCachedInProgressSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** L4-18/30: the single in-progress cache mapping, both directions, every persisted field. */
class CachedInProgressItemMappingTest {

    private val item = ContinueWatchingItem.InProgress(
        progress = WatchProgress(
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
            position = 1_000L,
            duration = 10_000L,
            lastWatched = 100L,
            progressPercent = 10f
        ),
        episodeDescription = "Desc",
        episodeThumbnail = "t",
        episodeImdbRating = 8.5f,
        genres = listOf("Drama"),
        releaseInfo = "2011",
        contentLanguage = "en"
    )

    private val cached = CachedInProgressItem(
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
        position = 1_000L,
        duration = 10_000L,
        lastWatched = 100L,
        progressPercent = 10f,
        episodeThumbnail = "t",
        episodeDescription = "Desc",
        episodeImdbRating = 8.5f,
        genres = listOf("Drama"),
        releaseInfo = "2011",
        contentLanguage = "en"
    )

    @Test
    fun `snapshot maps every field and skips next-up rows`() {
        val nextUp = ContinueWatchingItem.NextUp(CachedNextUpItemFixture.info)

        val snapshot = listOf(item, nextUp).toCachedInProgressSnapshot(noBrokenUrls())

        assertEquals(listOf(cached), snapshot)
    }

    @Test
    fun `snapshot drops a thumbnail known to be broken`() {
        val broken = noBrokenUrls().apply { add("t") }

        val snapshot = listOf(item).toCachedInProgressSnapshot(broken)

        assertNull(snapshot.single().episodeThumbnail)
    }

    @Test
    fun `cached row restores the in-progress item`() {
        assertEquals(item, cached.toInProgressItem())
    }

    private fun noBrokenUrls() = ExpiringUrlBlacklist(ttlMs = 60_000L, maxEntries = 10)
}

private object CachedNextUpItemFixture {
    val info = CachedNextUpItem(
        contentId = "tt1",
        contentType = "series",
        name = "Other",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = "tt1:1:2",
        season = 1,
        episode = 2,
        episodeTitle = null,
        thumbnail = null,
        lastWatched = 1L,
        sortTimestamp = 1L
    ).toNextUpInfo()
}
