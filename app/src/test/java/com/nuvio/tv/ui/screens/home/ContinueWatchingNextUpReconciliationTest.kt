package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.ContinueWatchingItem
import com.nuvio.tv.domain.model.NextUpInfo
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.ui.components.ExpiringUrlBlacklist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueWatchingNextUpReconciliationTest {
    @Test
    fun `transient resolution failure retains the existing next up`() {
        val existing = nextUp("series", season = 1, episode = 2, seedSeason = 1, seedEpisode = 1)

        val result = reconcileConclusiveNextUpItems(
            currentItems = listOf(existing),
            resolvedItems = emptyList(),
            conclusivelyProcessedContentIds = emptySet(),
            dismissedNextUpKeys = emptySet()
        )

        assertEquals(listOf(existing), result)
    }

    @Test
    fun `conclusive completion removes stale next up and preserves unrelated items`() {
        val stale = nextUp("completed", season = 3, episode = 2, seedSeason = 3, seedEpisode = 1)
        val unrelated = nextUp("watching", season = 2, episode = 4, seedSeason = 2, seedEpisode = 3)
        val inProgress = inProgress("movie")

        val result = reconcileConclusiveNextUpItems(
            currentItems = listOf(stale, unrelated, inProgress),
            resolvedItems = emptyList(),
            conclusivelyProcessedContentIds = setOf("completed"),
            dismissedNextUpKeys = emptySet()
        )

        assertFalse(result.contains(stale))
        assertTrue(result.contains(unrelated))
        assertTrue(result.contains(inProgress))
    }

    @Test
    fun `conclusive resolution replaces stale episode for the same series`() {
        val stale = nextUp("series", season = 1, episode = 2, seedSeason = 1, seedEpisode = 1)
        val resolved = nextUp("series", season = 5, episode = 17, seedSeason = 5, seedEpisode = 16)

        val result = reconcileConclusiveNextUpItems(
            currentItems = listOf(stale),
            resolvedItems = listOf(resolved),
            conclusivelyProcessedContentIds = setOf("series"),
            dismissedNextUpKeys = emptySet()
        )

        assertEquals(listOf(resolved), result)
    }

    @Test
    fun `in progress episode continues to suppress resolved next up`() {
        val inProgress = inProgress("series")
        val resolved = nextUp("series", season = 2, episode = 4, seedSeason = 2, seedEpisode = 3)

        val result = reconcileConclusiveNextUpItems(
            currentItems = listOf(inProgress),
            resolvedItems = listOf(resolved),
            conclusivelyProcessedContentIds = setOf("series"),
            dismissedNextUpKeys = emptySet()
        )

        assertEquals(listOf(inProgress), result)
    }

    @Test
    fun `dismissed resolved next up removes the existing card without adding another`() {
        val stale = nextUp("series", season = 1, episode = 2, seedSeason = 1, seedEpisode = 1)
        val resolved = nextUp("series", season = 1, episode = 3, seedSeason = 1, seedEpisode = 2)

        val result = reconcileConclusiveNextUpItems(
            currentItems = listOf(stale),
            resolvedItems = listOf(resolved),
            conclusivelyProcessedContentIds = setOf("series"),
            dismissedNextUpKeys = setOf(nextUpDismissKey("series", 1, 2))
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `late enrichment does not resurrect a removed stale card`() {
        val stale = nextUp("series", season = 1, episode = 2, seedSeason = 1, seedEpisode = 1)
        val enrichedStale = stale.copy(info = stale.info.copy(name = "Enriched stale"))
        val unrelated = nextUp("other", season = 1, episode = 3, seedSeason = 1, seedEpisode = 2)

        val result = mergeConcurrentContinueWatchingEnrichment(
            currentItems = listOf(unrelated),
            originalItems = listOf(stale),
            enrichedItems = listOf(enrichedStale)
        )

        assertEquals(listOf(unrelated), result)
    }

    @Test
    fun `late enrichment preserves a newer replacement for the same series`() {
        val stale = nextUp("series", season = 1, episode = 2, seedSeason = 1, seedEpisode = 1)
        val enrichedStale = stale.copy(info = stale.info.copy(name = "Enriched stale"))
        val replacement = nextUp("series", season = 8, episode = 7, seedSeason = 8, seedEpisode = 6)

        val result = mergeConcurrentContinueWatchingEnrichment(
            currentItems = listOf(replacement),
            originalItems = listOf(stale),
            enrichedItems = listOf(enrichedStale)
        )

        assertEquals(listOf(replacement), result)
    }

    @Test
    fun `dismiss key is per seed episode, not per series (G4-2)`() {
        // Dismissing the card seeded from S2E3 must not kill the S2E5 card
        // that appears after the user watches on.
        assertTrue(nextUpDismissKey("tt1", 2, 3) != nextUpDismissKey("tt1", 2, 4))
        assertEquals(nextUpDismissKey("tt1", 2, 3), nextUpDismissKey(" tt1 ", 2, 3))
        // Season 0 (specials) is a real season, not an unknown seed.
        assertEquals("tt1:s0:e1", nextUpDismissKey("tt1", 0, 1))
    }

    @Test
    fun `unknown seed keeps the legacy contentId-level dismiss key (G4-2)`() {
        // Movies and legacy cache entries have no seed episode: the key stays
        // the bare contentId so dismissals written before the change keep
        // matching. A null episode alone must not form a partial key.
        assertEquals("tt1", nextUpDismissKey("tt1", null, null))
        assertEquals("tt1", nextUpDismissKey("tt1", 2, null))
        assertEquals("tt1", nextUpDismissKey("tt1", null, 5))
        // A seeded series key must never collide with the bare form.
        assertTrue(nextUpDismissKey("tt1", 2, 3) != "tt1")
    }

    @Test
    fun `dismissing one seed episode does not suppress the next seed (G4-2)`() {
        val resolvedSeed3 = nextUp("series", season = 1, episode = 4, seedSeason = 1, seedEpisode = 3)
        val result = reconcileConclusiveNextUpItems(
            currentItems = emptyList(),
            resolvedItems = listOf(resolvedSeed3),
            conclusivelyProcessedContentIds = setOf("series"),
            dismissedNextUpKeys = setOf(nextUpDismissKey("series", 1, 2))
        )
        // The S1E3-seeded card survives a dismissal recorded for the S1E2 seed —
        // the exact behavior one-dismiss-kills-the-series removed.
        assertEquals(listOf(resolvedSeed3), result)
    }

    @Test
    fun `persisted snapshot excludes rejected items and broken thumbnails`() {
        val stale = nextUp("completed", season = 3, episode = 2, seedSeason = 3, seedEpisode = 1)
        val retained = nextUp(
            contentId = "watching",
            season = 2,
            episode = 4,
            seedSeason = 2,
            seedEpisode = 3,
            thumbnail = "broken"
        )
        val reconciled = reconcileConclusiveNextUpItems(
            currentItems = listOf(stale, retained, inProgress("movie")),
            resolvedItems = emptyList(),
            conclusivelyProcessedContentIds = setOf("completed"),
            dismissedNextUpKeys = emptySet()
        )

        val broken = ExpiringUrlBlacklist(ttlMs = 60_000L, maxEntries = 10)
        broken.add("broken")
        val snapshot = reconciled.toCachedNextUpSnapshot(broken)

        assertEquals(listOf("watching"), snapshot.map { item -> item.contentId })
        assertEquals(null, snapshot.single().thumbnail)
    }

    @Test
    fun `metadata changes pair enriched rows with their originals by identity, not position`() {
        // FR-11: the enriched list arrives re-sorted. Paired by index, the unchanged row "b"
        // would be compared with "a" and reported as changed.
        val a = inProgress("a")
        val b = inProgress("b")
        val enrichedA = a.copy(progress = a.progress.copy(name = "A enriched", poster = "https://img/a.jpg"))

        val changed = changedInProgressMetadata(
            originalItems = listOf(a, b),
            enrichedItems = listOf(b, enrichedA)
        )

        assertEquals(listOf(enrichedA.progress), changed)
    }

    @Test
    fun `metadata changes skip next up rows and rows with no original`() {
        val a = inProgress("a")
        val stray = inProgress("stray")
        val enrichedStray = stray.copy(progress = stray.progress.copy(name = "Stray enriched"))
        val nextUpRow = nextUp("series", season = 1, episode = 2, seedSeason = 1, seedEpisode = 1)

        val changed = changedInProgressMetadata(
            originalItems = listOf(a, nextUpRow),
            enrichedItems = listOf(nextUpRow.copy(info = nextUpRow.info.copy(name = "x")), enrichedStray, a)
        )

        assertTrue(changed.isEmpty())
    }

    private fun nextUp(
        contentId: String,
        season: Int,
        episode: Int,
        seedSeason: Int,
        seedEpisode: Int,
        thumbnail: String? = null
    ) = ContinueWatchingItem.NextUp(
        NextUpInfo(
            contentId = contentId,
            contentType = "series",
            name = contentId,
            poster = null,
            backdrop = null,
            logo = null,
            videoId = "$contentId:$season:$episode",
            season = season,
            episode = episode,
            episodeTitle = null,
            thumbnail = thumbnail,
            lastWatched = 100L,
            sortTimestamp = 100L,
            seedSeason = seedSeason,
            seedEpisode = seedEpisode
        )
    )

    private fun inProgress(contentId: String) = ContinueWatchingItem.InProgress(
        WatchProgress(
            contentId = contentId,
            contentType = "series",
            name = contentId,
            poster = null,
            backdrop = null,
            logo = null,
            videoId = "$contentId:1:1",
            season = 1,
            episode = 1,
            episodeTitle = null,
            position = 40L,
            duration = 100L,
            lastWatched = 100L
        )
    )
}
