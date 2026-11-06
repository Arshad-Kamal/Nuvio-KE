package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.data.local.CachedNextUpItem
import com.nuvio.tv.domain.model.NextUpInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Pins the one release-alert rule (L4-19/51) and its two cached-state adapters. */
class NextUpReleaseRuleTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_000L * day

    // --- nextUpReleaseState ---

    @Test
    fun `aired episode released after last watch within 60 days is a new-season release alert`() {
        val state = nextUpReleaseState(
            hasAired = true,
            releaseTimestamp = now - 10 * day,
            lastWatched = now - 20 * day,
            nextSeason = 2,
            seedSeason = 1,
            nowMs = now
        )

        assertTrue(state.isReleaseAlert)
        assertTrue(state.isNewSeasonRelease)
        assertEquals(now - 10 * day, state.sortTimestamp)
        assertEquals(now - 10 * day, state.releaseTimestamp)
    }

    @Test
    fun `same-season release alert is not a new-season release`() {
        val state = nextUpReleaseState(true, now - 10 * day, now - 20 * day, 2, 2, now)

        assertTrue(state.isReleaseAlert)
        assertFalse(state.isNewSeasonRelease)
    }

    @Test
    fun `unknown seed season never marks a new-season release`() {
        val state = nextUpReleaseState(true, now - 10 * day, now - 20 * day, 2, null, now)

        assertTrue(state.isReleaseAlert)
        assertFalse(state.isNewSeasonRelease)
    }

    @Test
    fun `release exactly 60 days ago no longer alerts and sorts by last watch`() {
        val state = nextUpReleaseState(true, now - 60 * day, now - 90 * day, 2, 1, now)

        assertFalse(state.isReleaseAlert)
        assertFalse(state.isNewSeasonRelease)
        assertEquals(now - 90 * day, state.sortTimestamp)
    }

    @Test
    fun `release before the last watch does not alert`() {
        val state = nextUpReleaseState(true, now - 20 * day, now - 10 * day, 2, 1, now)

        assertFalse(state.isReleaseAlert)
        assertEquals(now - 10 * day, state.sortTimestamp)
    }

    @Test
    fun `unaired or undated episodes do not alert`() {
        val unaired = nextUpReleaseState(false, now + day, now - 10 * day, 2, 1, now)
        val undated = nextUpReleaseState(true, null, now - 10 * day, 2, 1, now)

        assertFalse(unaired.isReleaseAlert)
        assertEquals(now - 10 * day, unaired.sortTimestamp)
        assertFalse(undated.isReleaseAlert)
        assertNull(undated.releaseTimestamp)
        assertEquals(now - 10 * day, undated.sortTimestamp)
    }

    // --- CachedNextUpItem.toFreshNextUpInfo (pipeline + cold-start restore) ---

    @Test
    fun `cached row written before the premiere gets the new-season badge once it aired`() {
        val cached = cached(
            hasAired = false,
            releaseTimestamp = now - 2 * day,
            lastWatched = now - 30 * day,
            season = 3,
            seedSeason = 2
        )

        val info = cached.toFreshNextUpInfo(now)

        assertTrue(info.hasAired)
        assertTrue(info.isReleaseAlert)
        assertTrue(info.isNewSeasonRelease)
        assertEquals(now - 2 * day, info.sortTimestamp)
    }

    @Test
    fun `cached row with a future release stays unaired and sorts by last watch`() {
        val info = cached(hasAired = true, releaseTimestamp = now + day, lastWatched = now - 30 * day)
            .toFreshNextUpInfo(now)

        assertFalse(info.hasAired)
        assertFalse(info.isReleaseAlert)
        assertEquals(now - 30 * day, info.sortTimestamp)
    }

    @Test
    fun `cached row without a release timestamp keeps its stored aired flag`() {
        val info = cached(hasAired = false, releaseTimestamp = null, lastWatched = now - 30 * day)
            .toFreshNextUpInfo(now)

        assertFalse(info.hasAired)
        assertFalse(info.isReleaseAlert)
    }

    // --- NextUpInfo.withRefreshedAiring (enriched overlays) ---

    @Test
    fun `overlay cached while unaired flips to aired with release flags after the air date`() {
        val release = Instant.parse("2026-07-10T00:00:00Z")
        val clock = Clock.fixed(Instant.parse("2026-07-12T12:00:00Z"), ZoneOffset.UTC)
        val overlay = info(
            released = "2026-07-10T00:00:00Z",
            hasAired = false,
            airDateLabel = "10 Jul",
            lastWatched = Instant.parse("2026-06-01T00:00:00Z").toEpochMilli(),
            season = 2,
            seedSeason = 1
        )

        val refreshed = overlay.withRefreshedAiring(clock)

        assertTrue(refreshed.hasAired)
        assertNull(refreshed.airDateLabel)
        assertTrue(refreshed.isReleaseAlert)
        assertTrue(refreshed.isNewSeasonRelease)
        assertEquals(release.toEpochMilli(), refreshed.releaseTimestamp)
        assertEquals(release.toEpochMilli(), refreshed.sortTimestamp)
    }

    @Test
    fun `overlay whose aired state still holds is returned unchanged`() {
        val clock = Clock.fixed(Instant.parse("2026-07-12T12:00:00Z"), ZoneOffset.UTC)
        val overlay = info(
            released = "2026-07-10T00:00:00Z",
            hasAired = true,
            airDateLabel = null,
            lastWatched = 0L,
            season = 2,
            seedSeason = 1
        )

        assertSame(overlay, overlay.withRefreshedAiring(clock))
    }

    private fun cached(
        hasAired: Boolean,
        releaseTimestamp: Long?,
        lastWatched: Long,
        season: Int = 1,
        seedSeason: Int? = 1
    ) = CachedNextUpItem(
        contentId = "tt1",
        contentType = "series",
        name = "Show",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = "tt1:$season:1",
        season = season,
        episode = 1,
        episodeTitle = null,
        thumbnail = null,
        hasAired = hasAired,
        lastWatched = lastWatched,
        sortTimestamp = lastWatched,
        releaseTimestamp = releaseTimestamp,
        seedSeason = seedSeason,
        seedEpisode = 8
    )

    private fun info(
        released: String,
        hasAired: Boolean,
        airDateLabel: String?,
        lastWatched: Long,
        season: Int,
        seedSeason: Int?
    ) = NextUpInfo(
        contentId = "tt1",
        contentType = "series",
        name = "Show",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = "tt1:$season:1",
        season = season,
        episode = 1,
        episodeTitle = null,
        thumbnail = null,
        released = released,
        hasAired = hasAired,
        airDateLabel = airDateLabel,
        lastWatched = lastWatched,
        sortTimestamp = lastWatched,
        seedSeason = seedSeason,
        seedEpisode = 8
    )
}
