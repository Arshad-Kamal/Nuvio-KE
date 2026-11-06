package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.player.NextEpisodeInfo
import com.nuvio.tv.core.player.playingRuntimeMinutes
import com.nuvio.tv.core.player.runtimeMismatchCheckMinutes
import com.nuvio.tv.domain.model.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Spec 26h: the post-play card's facts line and growth, and the runtime the player shows. */
class PostPlayFactsTest {

    @Test
    fun `facts line shows the air date in place of the status, except while searching`() {
        assertEquals("13m \u00b7 aired 25 Sep 2012", postPlayFactsLine("13m", "aired 25 Sep 2012", "Playing via X", searching = false))
        assertEquals("13m \u00b7 Finding source", postPlayFactsLine("13m", "aired 25 Sep 2012", "Finding source", searching = true))
        assertEquals("Not aired yet", postPlayFactsLine(null, null, "Not aired yet", searching = false))
        assertNull(postPlayFactsLine(null, null, null, searching = false))
    }

    @Test
    fun `card grows only when focused with a non-blank overview`() {
        val next = episode(overview = "  The robots reach the Dam.  ")
        assertEquals("The robots reach the Dam.", postPlayOverview(next, focused = true))
        assertNull(postPlayOverview(next, focused = false))
        assertNull(postPlayOverview(episode(overview = " "), focused = true))
        assertNull(postPlayOverview(null, focused = true))
    }

    @Test
    fun `runtime is the playing item's own, never the series runtime for an episode`() {
        val videos = listOf(
            Video(id = "s1e1", title = "E1", released = null, thumbnail = null, season = 1, episode = 1, overview = null, runtime = 13),
            Video(id = "s1e2", title = "E2", released = null, thumbnail = null, season = 1, episode = 2, overview = null),
            Video(id = "item9", title = "Item", released = null, thumbnail = null, season = null, episode = null, overview = null, runtime = 7)
        )
        assertEquals(13, playingRuntimeMinutes(videos, null, 1, 1, "45 min"))
        assertNull(playingRuntimeMinutes(videos, null, 1, 2, "45 min"))
        assertEquals(7, playingRuntimeMinutes(videos, "item9", null, null, "45 min"))
        assertEquals(92, playingRuntimeMinutes(emptyList(), "tt1", null, null, "1h 32m"))
        assertNull(playingRuntimeMinutes(emptyList(), "tt1", null, null, "0"))
    }

    @Test
    fun `runtime mismatch check uses the episode's own runtime, else the series runtime`() {
        val videos = listOf(
            Video(id = "s1e1", title = "E1", released = null, thumbnail = null, season = 1, episode = 1, overview = null, runtime = 22),
            Video(id = "s1e8", title = "E8", released = null, thumbnail = null, season = 1, episode = 8, overview = null)
        )
        assertEquals(22, runtimeMismatchCheckMinutes(videos, null, 1, 1, "23 min"))
        // Spec 35 Franklin: no episode runtime, so the check borrows the series text (the meta line does not).
        assertEquals(23, runtimeMismatchCheckMinutes(videos, null, 1, 8, "23 min"))
        assertNull(runtimeMismatchCheckMinutes(videos, null, 1, 8, null))
        assertNull(runtimeMismatchCheckMinutes(videos, null, 1, 8, "0"))
        assertEquals(92, runtimeMismatchCheckMinutes(emptyList(), "tt1", null, null, "1h 32m"))
    }

    private fun episode(overview: String?) = NextEpisodeInfo(
        videoId = "s1e4", season = 1, episode = 4, title = "Robots", thumbnail = null,
        overview = overview, released = null, hasAired = true, unairedMessage = null
    )
}
