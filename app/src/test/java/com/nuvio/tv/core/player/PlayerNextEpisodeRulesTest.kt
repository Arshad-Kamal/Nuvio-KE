package com.nuvio.tv.core.player

import com.nuvio.tv.data.local.NextEpisodeThresholdMode
import com.nuvio.tv.data.repository.SkipInterval
import com.nuvio.tv.domain.model.Video
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerNextEpisodeRulesTest {

    private fun ep(season: Int?, episode: Int?, id: String = "s${season}e${episode}") =
        Video(
            id = id,
            title = id,
            released = null,
            thumbnail = null,
            season = season,
            episode = episode,
            overview = null
        )

    @Test
    fun `advances to next episode in same season`() {
        val videos = listOf(ep(1, 1), ep(1, 2), ep(1, 3))
        val next = PlayerNextEpisodeRules.resolveNextEpisode(videos, currentSeason = 1, currentEpisode = 2)
        assertEquals("s1e3", next?.id)
    }

    @Test
    fun `crosses into next season after the season finale`() {
        val videos = listOf(ep(1, 1), ep(1, 2), ep(2, 1))
        val next = PlayerNextEpisodeRules.resolveNextEpisode(videos, currentSeason = 1, currentEpisode = 2)
        assertEquals("s2e1", next?.id)
    }

    @Test
    fun `returns null after the very last episode`() {
        val videos = listOf(ep(1, 1), ep(1, 2))
        val next = PlayerNextEpisodeRules.resolveNextEpisode(videos, currentSeason = 1, currentEpisode = 2)
        assertNull(next)
    }

    @Test
    fun `returns null when the current episode is not in the list`() {
        val videos = listOf(ep(1, 1), ep(1, 2))
        val next = PlayerNextEpisodeRules.resolveNextEpisode(videos, currentSeason = 3, currentEpisode = 9)
        assertNull(next)
    }

    @Test
    fun `absolute numbering advances by episode when the caller has no season`() {
        // Season-less anime: the caller has no season but the meta videos still carry one.
        val videos = listOf(ep(1, 5), ep(1, 6), ep(1, 7))
        val next = PlayerNextEpisodeRules.resolveNextEpisode(videos, currentSeason = null, currentEpisode = 6)
        assertEquals("s1e7", next?.id)
    }

    @Test
    fun `absolute numbering advances when the meta videos also lack a season`() {
        val videos = listOf(ep(null, 5, "e5"), ep(null, 6, "e6"), ep(null, 7, "e7"))
        val next = PlayerNextEpisodeRules.resolveNextEpisode(videos, currentSeason = null, currentEpisode = 6)
        assertEquals("e7", next?.id)
    }

    @Test
    fun `previous steps back within a season`() {
        val videos = listOf(ep(1, 1), ep(1, 2), ep(1, 3))
        val previous = PlayerNextEpisodeRules.resolvePreviousEpisode(videos, currentSeason = 1, currentEpisode = 3)
        assertEquals("s1e2", previous?.id)
    }

    @Test
    fun `previous crosses back to the last episode of the season before`() {
        val videos = listOf(ep(2, 1), ep(1, 2), ep(1, 1), ep(2, 2))
        val previous = PlayerNextEpisodeRules.resolvePreviousEpisode(videos, currentSeason = 2, currentEpisode = 1)
        assertEquals("s1e2", previous?.id)
    }

    @Test
    fun `previous is null on the first episode and never lands on a special`() {
        val videos = listOf(ep(0, 1), ep(0, 2), ep(1, 1), ep(1, 2))
        assertNull(PlayerNextEpisodeRules.resolvePreviousEpisode(videos, currentSeason = 1, currentEpisode = 1))
        assertNull(PlayerNextEpisodeRules.resolvePreviousEpisode(videos, currentSeason = 0, currentEpisode = 2))
    }

    @Test
    fun `previous is null when the current episode is not in the list`() {
        val videos = listOf(ep(1, 1), ep(1, 2))
        assertNull(PlayerNextEpisodeRules.resolvePreviousEpisode(videos, currentSeason = 3, currentEpisode = 9))
    }

    @Test
    fun `absolute numbering returns null after the last episode`() {
        val videos = listOf(ep(null, 5, "e5"), ep(null, 6, "e6"))
        val next = PlayerNextEpisodeRules.resolveNextEpisode(videos, currentSeason = null, currentEpisode = 6)
        assertNull(next)
    }

    @Test
    fun `timestamped episode does not air early on its local release day`() {
        val eastern = ZoneId.of("America/Detroit")
        val before = Clock.fixed(Instant.parse("2026-07-15T14:59:59Z"), eastern)
        val exact = Clock.fixed(Instant.parse("2026-07-15T15:00:00Z"), eastern)

        assertFalse(PlayerNextEpisodeRules.hasEpisodeAired("2026-07-15T15:00:00Z", before))
        assertTrue(PlayerNextEpisodeRules.hasEpisodeAired("2026-07-15T15:00:00Z", exact))
    }

    private fun showCard(
        positionMs: Long,
        durationMs: Long,
        mode: NextEpisodeThresholdMode,
        skipIntervals: List<SkipInterval> = emptyList(),
        percent: Float = 97f,
        minutes: Float = 2f,
    ) = PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
        positionMs = positionMs,
        durationMs = durationMs,
        skipIntervals = skipIntervals,
        thresholdMode = mode,
        thresholdPercent = percent,
        thresholdMinutesBeforeEnd = minutes,
    )

    private fun outro(startSec: Double, endSec: Double) =
        SkipInterval(startTime = startSec, endTime = endSec, type = "outro", provider = "test")

    @Test
    fun `next episode card never shows without a duration`() {
        assertFalse(showCard(10_000L, 0L, NextEpisodeThresholdMode.PERCENTAGE))
        assertFalse(showCard(10_000L, 0L, NextEpisodeThresholdMode.MINUTES_BEFORE_END, listOf(outro(1.0, 2.0))))
    }

    @Test
    fun `next episode card uses the percentage threshold without outro data`() {
        assertFalse(showCard(96_999L, 100_000L, NextEpisodeThresholdMode.PERCENTAGE))
        assertTrue(showCard(97_000L, 100_000L, NextEpisodeThresholdMode.PERCENTAGE))
        // Percent is clamped to [97, 100].
        assertFalse(showCard(60_000L, 100_000L, NextEpisodeThresholdMode.PERCENTAGE, percent = 50f))
    }

    @Test
    fun `next episode card uses the minutes threshold without outro data`() {
        assertFalse(showCard(479_999L, 600_000L, NextEpisodeThresholdMode.MINUTES_BEFORE_END))
        assertTrue(showCard(480_000L, 600_000L, NextEpisodeThresholdMode.MINUTES_BEFORE_END))
        // Minutes are clamped to [0, 3.5].
        assertFalse(showCard(389_999L, 600_000L, NextEpisodeThresholdMode.MINUTES_BEFORE_END, minutes = 10f))
        assertTrue(showCard(390_000L, 600_000L, NextEpisodeThresholdMode.MINUTES_BEFORE_END, minutes = 10f))
    }

    @Test
    fun `next episode card fires at the outro start when the outro ends near the file end`() {
        val intervals = listOf(outro(500.0, 590.0))
        assertFalse(showCard(499_999L, 600_000L, NextEpisodeThresholdMode.MINUTES_BEFORE_END, intervals))
        assertTrue(showCard(500_000L, 600_000L, NextEpisodeThresholdMode.MINUTES_BEFORE_END, intervals))
    }

    @Test
    fun `next episode card falls back to the user threshold when content follows the outro`() {
        val intervals = listOf(outro(300.0, 400.0))
        assertFalse(showCard(300_000L, 600_000L, NextEpisodeThresholdMode.MINUTES_BEFORE_END, intervals))
        assertFalse(showCard(479_999L, 600_000L, NextEpisodeThresholdMode.MINUTES_BEFORE_END, intervals))
        assertTrue(showCard(480_000L, 600_000L, NextEpisodeThresholdMode.MINUTES_BEFORE_END, intervals))
        assertFalse(showCard(581_999L, 600_000L, NextEpisodeThresholdMode.PERCENTAGE, intervals))
        assertTrue(showCard(582_000L, 600_000L, NextEpisodeThresholdMode.PERCENTAGE, intervals))
    }
}
