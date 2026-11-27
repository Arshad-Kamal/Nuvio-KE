package com.nuvio.tv.core.tracking

import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec 104 S2a: what a tracker source shows, merged with the local entries it cannot hold. */
class TrackingProgressProjectionTest {

    private fun progress(id: String, season: Int? = null, episode: Int? = null, at: Long) = WatchProgress(
        contentId = id, contentType = if (season == null) "movie" else "series", name = id, poster = null,
        backdrop = null, logo = null, videoId = id, season = season, episode = episode, episodeTitle = null,
        position = 10L, duration = 100L, lastWatched = at
    )

    private fun watched(id: String, season: Int? = null, episode: Int? = null) =
        WatchedItem(contentId = id, contentType = "series", title = id, season = season, episode = episode, watchedAt = 1L)

    @Test
    fun `progress - the tracker picks the titles, a newer local entry of one wins, retained local titles are added`() {
        val merged = mergeProgressProjection(
            providerEntries = listOf(progress("tt1", 1, 4, at = 5L), progress("tt3", at = 6L)),
            localEntries = listOf(progress("tt1", 1, 5, at = 9L), progress("tt3", at = 2L), progress("kitsu:7", at = 7L), progress("tt2", at = 8L)),
            retainsLocal = { it.contentId.startsWith("kitsu:") }
        )
        assertEquals(
            listOf(Triple("tt1", 5, 9L), Triple("kitsu:7", null, 7L), Triple("tt3", null, 6L)),
            merged.map { Triple(it.contentId, it.episode, it.lastWatched) }
        )
    }

    @Test
    fun `episode progress - newer wins per episode, a local-only episode only when retained`() {
        val tracker = mapOf((1 to 1) to progress("tt1", 1, 1, at = 5L), (1 to 2) to progress("tt1", 1, 2, at = 9L))
        val local = mapOf((1 to 1) to progress("tt1", 1, 1, at = 8L), (1 to 2) to progress("tt1", 1, 2, at = 3L), (1 to 3) to progress("tt1", 1, 3, at = 4L))
        assertEquals(mapOf((1 to 1) to 8L, (1 to 2) to 9L), mergeEpisodeProgressProjection(tracker, local) { false }.mapValues { it.value.lastWatched })
        assertEquals(setOf(1 to 1, 1 to 2, 1 to 3), mergeEpisodeProgressProjection(tracker, local) { true }.keys)
    }

    @Test
    fun `episode progress - the keep check is asked per local episode`() {
        val local = mapOf((1 to 3) to progress("tt1", 1, 3, at = 4L), (1 to 4) to progress("tt1", 1, 4, at = 6L))
        assertEquals(setOf(1 to 4), mergeEpisodeProgressProjection(emptyMap(), local) { it.episode == 4 }.keys)
    }

    @Test
    fun `seeds are one per show, a retained local seed counts only when newer than the tracker's`() {
        val merged = mergeNextUpSeedProjection(
            providerSeeds = listOf(progress("tt1", 1, 2, at = 3L), progress("tt3", 2, 2, at = 7L)),
            localSeeds = listOf(
                progress("tt1", 1, 5, at = 9L), progress("kitsu:7", 1, 1, at = 4L), progress("tt2", 1, 1, at = 8L), progress("tt3", 2, 1, at = 5L)
            ),
            retainsLocal = { it.contentId != "tt2" }
        )
        assertEquals(
            listOf(Triple("tt1", 1, 5), Triple("tt3", 2, 2), Triple("kitsu:7", 1, 1)),
            merged.map { Triple(it.contentId, it.season, it.episode) }
        )
    }

    @Test
    fun `watched marks add only retained local marks`() {
        val local = listOf(watched("tt1", 1, 3), watched("tt2", 1, 1), watched("tt1"))
        val merged = mergeWatchedItemProjection(listOf(watched("tt1", 1, 1)), local) { it.contentId == "tt1" }

        assertEquals(
            listOf(Triple("tt1", 1, 1), Triple("tt1", 1, 3), Triple("tt1", null, null)),
            merged.map { Triple(it.contentId, it.season, it.episode) }
        )
        assertEquals(mapOf("tt1" to setOf(1 to 1, 1 to 3)), merged.watchedEpisodesByShow())
    }

    @Test
    fun `a provider without a per-episode check keeps a local entry exactly when it keeps its title`() {
        val provider = object : TrackingProgressProvider {
            override val providerId = TrackingProviderId.SIMKL
            override val isAuthenticated: Flow<Boolean> = emptyFlow()
            override val allProgress: Flow<List<WatchProgress>> = emptyFlow()
            override val nextUpSeeds: Flow<List<WatchProgress>> = emptyFlow()
            override val watchedMovieIds: Flow<Set<String>> = emptyFlow()
            override val watchedItems: Flow<List<WatchedItem>> = emptyFlow()
            override fun episodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>> = emptyFlow()
            override fun isWatched(contentId: String, season: Int?, episode: Int?): Flow<Boolean> = emptyFlow()
            override suspend fun refresh(intent: TrackingRefreshIntent) = Unit
            override suspend fun removeProgress(profileId: Int, contentId: String, season: Int?, episode: Int?) = Unit
            override fun applyOptimisticProgress(progress: WatchProgress) = Unit
            override fun applyOptimisticRemoval(contentId: String, season: Int?, episode: Int?) = Unit
            override fun retainsLocal(contentId: String) = contentId.startsWith("kitsu:")
        }
        assertTrue(provider.retainsLocalEpisode("kitsu:1", 1, 2, 5L))
        assertFalse(provider.retainsLocalEpisode("tt1", 1, 2, 5L))
    }
}
