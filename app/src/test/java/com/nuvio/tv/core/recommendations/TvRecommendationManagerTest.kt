package com.nuvio.tv.core.recommendations

import android.content.Context
import android.content.pm.PackageManager
import com.nuvio.tv.domain.model.WatchProgress
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Watch Next row bookkeeping: per-episode upserts prune the title's other rows, removals
 * are episode-scoped or title-wide, and provider rows and fingerprints drop together.
 */
class TvRecommendationManagerTest {

    private val context = mockk<Context> {
        every { packageManager } returns mockk {
            every { hasSystemFeature(PackageManager.FEATURE_LEANBACK) } returns true
        }
    }
    private val removals = mutableListOf<(String) -> Boolean>()
    private val programBuilder = mockk<ProgramBuilder> {
        every { removeWatchNextRows(capture(removals)) } just Runs
        every { buildWatchNextProgram(any()) } returns mockk()
        every { upsertWatchNextProgram(any(), any()) } just Runs
    }
    private val manager = TvRecommendationManager(context, programBuilder)

    @Test
    fun `a new episode prunes the title's other rows before its upsert`() = runTest {
        manager.updateSingleWatchNextProgram(progress(season = 1, episode = 2))

        verifyOrder {
            programBuilder.removeWatchNextRows(any())
            programBuilder.upsertWatchNextProgram(any(), "wn_tt1_s1e2")
        }
        val prune = removals.single()
        assertTrue(prune("wn_tt1_s1e1"))
        assertTrue(prune("wn_tt1"))
        assertFalse(prune("wn_tt1_s1e2"))
        assertFalse(prune("wn_tt12_s1e1"))
    }

    @Test
    fun `the sibling prune also drops the pruned episode's fingerprint`() = runTest {
        manager.updateSingleWatchNextProgram(progress(season = 1, episode = 1))
        manager.updateSingleWatchNextProgram(progress(season = 1, episode = 2))
        // Same position as the first save: only a dropped fingerprint lets it upsert again.
        manager.updateSingleWatchNextProgram(progress(season = 1, episode = 1))

        verify(exactly = 2) { programBuilder.upsertWatchNextProgram(any(), "wn_tt1_s1e1") }
    }

    @Test
    fun `an episode removal drops only that episode and the bare title row`() = runTest {
        manager.onProgressRemoved("tt1", season = 1, episode = 3)

        val prune = removals.single()
        assertTrue(prune("wn_tt1_s1e3"))
        assertTrue(prune("wn_tt1"))
        assertFalse(prune("wn_tt1_s2e5"))
        assertFalse(prune("wn_tt12_s1e3"))
        assertFalse(prune("wn_tt12"))
    }

    @Test
    fun `an episode removal keeps other episodes' fingerprints`() = runTest {
        manager.updateSingleWatchNextProgram(progress(season = 2, episode = 5))
        manager.onProgressRemoved("tt1", season = 1, episode = 3)
        manager.updateSingleWatchNextProgram(progress(season = 2, episode = 5))

        verify(exactly = 1) { programBuilder.upsertWatchNextProgram(any(), "wn_tt1_s2e5") }
    }

    @Test
    fun `an episode removal drops that episode's fingerprint`() = runTest {
        manager.updateSingleWatchNextProgram(progress(season = 2, episode = 5))
        manager.onProgressRemoved("tt1", season = 2, episode = 5)
        manager.updateSingleWatchNextProgram(progress(season = 2, episode = 5))

        verify(exactly = 2) { programBuilder.upsertWatchNextProgram(any(), "wn_tt1_s2e5") }
    }

    @Test
    fun `a removal without season and episode drops every row of the title`() = runTest {
        manager.onProgressRemoved("tt1")

        val prune = removals.single()
        assertTrue(prune("wn_tt1"))
        assertTrue(prune("wn_tt1_s1e3"))
        assertTrue(prune("wn_tt1_s2e5"))
        assertFalse(prune("wn_tt12"))
        assertFalse(prune("wn_tt12_s1e3"))
    }

    @Test
    fun `a batch removal is one provider scan covering every item`() = runTest {
        manager.onProgressRemoved(
            listOf(WatchNextRemoval("tt1", 1, 1), WatchNextRemoval("tt1", 1, 2), WatchNextRemoval("tt9"))
        )

        val prune = removals.single()
        assertTrue(prune("wn_tt1_s1e1"))
        assertTrue(prune("wn_tt1_s1e2"))
        assertTrue(prune("wn_tt9_s3e4"))
        assertFalse(prune("wn_tt1_s2e5"))
    }

    @Test
    fun `an empty batch touches nothing`() = runTest {
        manager.onProgressRemoved(emptyList())

        verify(exactly = 0) { programBuilder.removeWatchNextRows(any()) }
    }

    @Test
    fun `a repeat save of the same episode within 2 s is skipped`() = runTest {
        manager.updateSingleWatchNextProgram(progress(season = 1, episode = 2, position = 10_000L))
        manager.updateSingleWatchNextProgram(progress(season = 1, episode = 2, position = 11_500L))

        verify(exactly = 1) { programBuilder.upsertWatchNextProgram(any(), any()) }
        verify(exactly = 1) { programBuilder.removeWatchNextRows(any()) }
    }

    private fun progress(season: Int?, episode: Int?, position: Long = 10_000L) = WatchProgress(
        contentId = "tt1",
        contentType = "series",
        name = "Title",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = "tt1:$season:$episode",
        season = season,
        episode = episode,
        episodeTitle = null,
        position = position,
        duration = 60_000L,
        lastWatched = 1_000L
    )
}
