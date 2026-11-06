package com.nuvio.tv.ui.screens.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-12/19: the one negative-TTL liveness rule shared by both next-up cache readers. */
class NextUpResolutionLivenessTest {

    @Test
    fun `positive resolution is live regardless of age`() {
        val resolutions = mapOf<String, NextUpResolution?>(KEY to resolution())

        assertTrue(isNextUpResolutionLive(resolutions, emptyMap(), KEY, nowMs = NOW_MS))
    }

    @Test
    fun `negative resolution inside the five-minute TTL is live`() {
        val resolutions = mapOf<String, NextUpResolution?>(KEY to null)
        val negativeAt = mapOf(KEY to NOW_MS - 4 * 60_000L)

        assertTrue(isNextUpResolutionLive(resolutions, negativeAt, KEY, nowMs = NOW_MS))
    }

    @Test
    fun `negative resolution at or past the five-minute TTL is not live`() {
        val resolutions = mapOf<String, NextUpResolution?>(KEY to null)

        assertFalse(isNextUpResolutionLive(resolutions, mapOf(KEY to NOW_MS - 5 * 60_000L), KEY, NOW_MS))
        assertFalse(isNextUpResolutionLive(resolutions, mapOf(KEY to NOW_MS - 6 * 60_000L), KEY, NOW_MS))
    }

    @Test
    fun `negative resolution without a timestamp is not live`() {
        val resolutions = mapOf<String, NextUpResolution?>(KEY to null)

        assertFalse(isNextUpResolutionLive(resolutions, emptyMap(), KEY, nowMs = NOW_MS))
    }

    @Test
    fun `absent key is not live even with a stray timestamp`() {
        assertFalse(isNextUpResolutionLive(emptyMap(), mapOf(KEY to NOW_MS), KEY, nowMs = NOW_MS))
    }

    @Test
    fun `cached card at or before the concluded seed is covered, a later seed is not`() {
        // FR-01: a series concluded with no next episode from seed S2E10.
        val concluded = 2 to 10

        assertTrue(isSeededAtOrBefore(concluded, seedSeason = 2, seedEpisode = 10))
        assertTrue(isSeededAtOrBefore(concluded, seedSeason = 2, seedEpisode = 9))
        assertTrue(isSeededAtOrBefore(concluded, seedSeason = 1, seedEpisode = 12))
        assertTrue(isSeededAtOrBefore(concluded, seedSeason = null, seedEpisode = null))
        assertFalse(isSeededAtOrBefore(concluded, seedSeason = 2, seedEpisode = 11))
        assertFalse(isSeededAtOrBefore(concluded, seedSeason = 3, seedEpisode = 1))
        assertFalse(isSeededAtOrBefore(null, seedSeason = 2, seedEpisode = 10))
    }

    private fun resolution() = NextUpResolution(
        season = 1,
        episode = 2,
        videoId = "tt1:1:2",
        episodeTitle = null,
        released = null,
        hasAired = true,
        airDateLabel = null,
        lastWatched = 0L
    )

    private companion object {
        const val KEY = "tt1|1|1|unaired=false"
        const val NOW_MS = 10 * 60_000L
    }
}
