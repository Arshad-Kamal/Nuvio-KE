package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-26: which episodes each batch watched action marks or unmarks, and the L5-79 guard. */
class BatchWatchedTargetsTest {

    private fun ep(season: Int?, episode: Int?, id: String = "s${season}e$episode") = Video(
        id = id, title = id, released = null, thumbnail = null,
        season = season, episode = episode, overview = null
    )

    private fun completed(season: Int, episode: Int) = WatchProgress(
        contentId = "tt1", contentType = "series", name = "",
        poster = null, backdrop = null, logo = null,
        videoId = "s${season}e$episode", season = season, episode = episode, episodeTitle = null,
        position = 1L, duration = 1L, lastWatched = 0L, progressPercent = 100f
    )

    private fun targets(
        request: BatchWatchedRequest,
        videos: List<Video>,
        state: MetaDetailsUiState = MetaDetailsUiState(),
        apiType: String = "series"
    ): List<String> = batchWatchedTargets(request, apiType, videos, state).map { it.id }

    @Test
    fun `mark season picks the season's unwatched episodes only`() {
        val videos = listOf(ep(1, 1), ep(1, 2), ep(1, 3), ep(2, 1))
        val state = MetaDetailsUiState(
            watchedEpisodes = setOf(1 to 1),
            episodeProgressMap = mapOf((1 to 3) to completed(1, 3))
        )

        assertEquals(listOf("s1e2"), targets(BatchWatchedRequest.MarkSeason(1), videos, state))
    }

    @Test
    fun `a partially watched episode counts as unwatched`() {
        val videos = listOf(ep(1, 1), ep(1, 2))
        val partial = completed(1, 2).copy(position = 1L, duration = 2L, progressPercent = 50f)
        val state = MetaDetailsUiState(
            watchedEpisodes = setOf(1 to 1),
            episodeProgressMap = mapOf((1 to 2) to partial)
        )

        assertEquals(listOf("s1e2"), targets(BatchWatchedRequest.MarkSeason(1), videos, state))
        assertEquals(listOf("s1e1"), targets(BatchWatchedRequest.UnmarkSeason(1), videos, state))
    }

    @Test
    fun `unmark season picks the season's watched episodes only`() {
        val videos = listOf(ep(1, 1), ep(1, 2), ep(1, 3), ep(2, 1))
        val state = MetaDetailsUiState(
            watchedEpisodes = setOf(1 to 1, 2 to 1),
            episodeProgressMap = mapOf((1 to 3) to completed(1, 3))
        )

        assertEquals(listOf("s1e1", "s1e3"), targets(BatchWatchedRequest.UnmarkSeason(1), videos, state))
    }

    @Test
    fun `mark previous episodes picks earlier unwatched episodes of the same season`() {
        val videos = listOf(ep(1, 1), ep(1, 2), ep(1, 3), ep(1, 4), ep(2, 1))
        val state = MetaDetailsUiState(watchedEpisodes = setOf(1 to 2))

        assertEquals(
            listOf("s1e1"),
            targets(BatchWatchedRequest.MarkPreviousEpisodes(season = 1, episode = 3), videos, state)
        )
    }

    @Test
    fun `mark previous seasons skips specials, the target season and later ones`() {
        val videos = listOf(ep(0, 1), ep(1, 1), ep(1, 2), ep(2, 1), ep(3, 1), ep(4, 1))
        val state = MetaDetailsUiState(watchedEpisodes = setOf(1 to 2))

        assertEquals(listOf("s1e1", "s2e1"), targets(BatchWatchedRequest.MarkPreviousSeasons(3), videos, state))
    }

    @Test
    fun `episodes without season or episode numbers are never targeted`() {
        val videos = listOf(ep(1, null, id = "noEpisode"), ep(null, 1, id = "noSeason"), ep(1, 1))

        assertEquals(listOf("s1e1"), targets(BatchWatchedRequest.MarkSeason(1), videos))
    }

    @Test
    fun `type other picks from the synthetic-numbered episodesForSeason`() {
        // "other" metas carry no season numbers; the screen numbers them 1..n in season 1.
        val rawVideos = listOf(ep(null, null, id = "a"), ep(null, null, id = "b"))
        val state = MetaDetailsUiState(
            episodesForSeason = listOf(ep(1, 1, id = "a"), ep(1, 2, id = "b")),
            watchedEpisodes = setOf(1 to 2)
        )

        assertEquals(listOf("a"), targets(BatchWatchedRequest.MarkSeason(1), rawVideos, state, apiType = "other"))
    }

    @Test
    fun `series ignore episodesForSeason so any season can be addressed`() {
        val videos = listOf(ep(1, 1), ep(2, 1))
        val state = MetaDetailsUiState(episodesForSeason = listOf(ep(1, 1)))

        assertEquals(listOf("s2e1"), targets(BatchWatchedRequest.MarkSeason(2), videos, state))
    }

    @Test
    fun `only unmark season unmarks`() {
        assertFalse(BatchWatchedRequest.UnmarkSeason(1).markWatched)
        assertTrue(BatchWatchedRequest.MarkSeason(1).markWatched)
        assertTrue(BatchWatchedRequest.MarkPreviousEpisodes(1, 2).markWatched)
        assertTrue(BatchWatchedRequest.MarkPreviousSeasons(2).markWatched)
    }

    @Test
    fun `precomputed next-to-watch is kept only while the refine runs over empty sets`() {
        assertTrue(
            shouldKeepPrecomputedNextToWatch(
                refineInFlight = true, progressMap = emptyMap(), watchedEpisodes = emptySet(), hasNextToWatch = true
            )
        )
    }

    @Test
    fun `empty sets after the refine finished recompute next-to-watch`() {
        // L5-79: unmarking the only watched episode must update the Next button.
        assertFalse(
            shouldKeepPrecomputedNextToWatch(
                refineInFlight = false, progressMap = emptyMap(), watchedEpisodes = emptySet(), hasNextToWatch = true
            )
        )
    }

    @Test
    fun `loaded progress, watched marks or no precomputed value recompute during the refine`() {
        assertFalse(
            shouldKeepPrecomputedNextToWatch(
                refineInFlight = true,
                progressMap = mapOf((1 to 1) to completed(1, 1)),
                watchedEpisodes = emptySet(),
                hasNextToWatch = true
            )
        )
        assertFalse(
            shouldKeepPrecomputedNextToWatch(
                refineInFlight = true, progressMap = emptyMap(), watchedEpisodes = setOf(1 to 1), hasNextToWatch = true
            )
        )
        assertFalse(
            shouldKeepPrecomputedNextToWatch(
                refineInFlight = true, progressMap = emptyMap(), watchedEpisodes = emptySet(), hasNextToWatch = false
            )
        )
    }
}
