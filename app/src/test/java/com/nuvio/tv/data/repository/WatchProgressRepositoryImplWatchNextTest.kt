package com.nuvio.tv.data.repository

import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.recommendations.TvRecommendationManager
import com.nuvio.tv.core.recommendations.WatchNextRemoval
import com.nuvio.tv.core.sync.WatchProgressSyncService
import com.nuvio.tv.core.sync.WatchedItemsSyncService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.ContinueWatchingSettingsDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.MetaRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L10: every local removal/completion prunes the launcher Watch Next row: episode-scoped
 * operations only that episode's row, title-wide ones the whole title. The prune runs last.
 */
class WatchProgressRepositoryImplWatchNextTest {

    private val profileManager = mockk<ProfileManager> {
        every { activeProfileId } returns MutableStateFlow(PROFILE_ID)
    }
    private val prefs = mockk<WatchProgressPreferences>(relaxed = true) {
        coEvery { getAllRawEntries(any()) } returns emptyMap()
    }
    private val watchedItemsPreferences = mockk<WatchedItemsPreferences>(relaxed = true)
    private val continueWatchingSettings = mockk<ContinueWatchingSettingsDataStore>(relaxed = true)
    private val progressSync = mockk<WatchProgressSyncService>(relaxed = true) {
        coEvery { deleteFromRemote(any(), any()) } returns Result.success(Unit)
    }
    private val watchedSync = mockk<WatchedItemsSyncService>(relaxed = true) {
        coEvery { deleteFromRemote(any(), any(), any(), any()) } returns Result.success(Unit)
    }
    // Unauthenticated by default: no remote calls, so only the local path and the prune run.
    private val authManager = mockk<AuthManager> { every { isAuthenticated } returns false }
    private val tvRecommendationManager = mockk<TvRecommendationManager>(relaxed = true)

    init {
        listOf(prefs, watchedItemsPreferences, continueWatchingSettings, progressSync, watchedSync)
            .forEach { it.injectProfileManager(profileManager) }
    }

    private val repository = WatchProgressRepositoryImpl(
        watchProgressPreferences = prefs,
        continueWatchingSettings = continueWatchingSettings,
        layoutPreferenceDataStore = mockk<LayoutPreferenceDataStore>(relaxed = true),
        watchProgressSyncService = progressSync,
        watchedItemsPreferences = watchedItemsPreferences,
        watchedItemsSyncService = watchedSync,
        authManager = authManager,
        metaRepository = mockk<MetaRepository>(relaxed = true),
        tmdbService = mockk<TmdbService>(relaxed = true),
        profileManager = profileManager,
        tvRecommendationManager = tvRecommendationManager
    )

    @Test
    fun `removeProgress of an episode prunes only that episode after the local removal`() = runTest {
        repository.removeProgress(SERIES_ID, season = 1, episode = 2)

        coVerifyOrder {
            prefs.removeProgress(SERIES_ID, 1, 2, PROFILE_ID)
            tvRecommendationManager.onProgressRemoved(listOf(WatchNextRemoval(SERIES_ID, 1, 2)))
        }
        coVerify(exactly = 1) { tvRecommendationManager.onProgressRemoved(any<Collection<WatchNextRemoval>>()) }
    }

    @Test
    fun `markAsCompleted of a movie prunes the whole title after the local write`() = runTest {
        val progress = progress(MOVIE_ID, season = null, episode = null)

        repository.markAsCompleted(progress)

        coVerifyOrder {
            prefs.markAsCompleted(progress, PROFILE_ID)
            tvRecommendationManager.onProgressRemoved(listOf(WatchNextRemoval(MOVIE_ID, null, null)))
        }
        coVerify(exactly = 1) { tvRecommendationManager.onProgressRemoved(any<Collection<WatchNextRemoval>>()) }
    }

    @Test
    fun `removeFromHistory of a title prunes the whole title`() = runTest {
        repository.removeFromHistory(MOVIE_ID)

        coVerify(exactly = 1) { tvRecommendationManager.onProgressRemoved(listOf(WatchNextRemoval(MOVIE_ID, null, null))) }
        coVerify(exactly = 1) { tvRecommendationManager.onProgressRemoved(any<Collection<WatchNextRemoval>>()) }
    }

    @Test
    fun `removeFromHistoryBatch prunes each unmarked episode only, in one call`() = runTest {
        val episodes = listOf(1 to 1, 1 to 2, 1 to 2)

        repository.removeFromHistoryBatch(SERIES_ID, episodes)

        coVerifyOrder {
            watchedItemsPreferences.unmarkAsWatchedBatch(SERIES_ID, episodes, PROFILE_ID)
            tvRecommendationManager.onProgressRemoved(
                listOf(WatchNextRemoval(SERIES_ID, 1, 1), WatchNextRemoval(SERIES_ID, 1, 2))
            )
        }
        coVerify(exactly = 1) { tvRecommendationManager.onProgressRemoved(any<Collection<WatchNextRemoval>>()) }
    }

    @Test
    fun `markAsCompletedBatch prunes each completed episode once, in one call`() = runTest {
        val batch = listOf(
            progress(SERIES_ID, season = 1, episode = 1),
            progress(SERIES_ID, season = 1, episode = 2),
            progress(SERIES_ID, season = 1, episode = 2)
        )

        repository.markAsCompletedBatch(batch)

        coVerify(exactly = 1) {
            tvRecommendationManager.onProgressRemoved(
                listOf(WatchNextRemoval(SERIES_ID, 1, 1), WatchNextRemoval(SERIES_ID, 1, 2))
            )
        }
        coVerify(exactly = 1) { tvRecommendationManager.onProgressRemoved(any<Collection<WatchNextRemoval>>()) }
    }

    @Test
    fun `a Watch Next failure does not fail the repository operation`() = runTest {
        coEvery { tvRecommendationManager.onProgressRemoved(any<Collection<WatchNextRemoval>>()) } throws
            IllegalStateException("provider")

        repository.removeProgress(MOVIE_ID, season = null, episode = null)

        coVerify(exactly = 1) { prefs.removeProgress(MOVIE_ID, null, null, PROFILE_ID) }
    }

    @Test
    fun `a cancellation during the prune cannot skip the remote deletes`() = runTest {
        every { authManager.isAuthenticated } returns true
        coEvery { tvRecommendationManager.onProgressRemoved(any<Collection<WatchNextRemoval>>()) } throws
            CancellationException("left the screen")

        val cancelled = try {
            repository.removeFromHistory(SERIES_ID, season = 1, episode = 2)
            false
        } catch (e: CancellationException) {
            true
        }

        assertTrue(cancelled)
        coVerifyOrder {
            progressSync.deleteFromRemote(any(), PROFILE_ID)
            watchedSync.deleteFromRemote(SERIES_ID, 1, 2, PROFILE_ID)
            tvRecommendationManager.onProgressRemoved(listOf(WatchNextRemoval(SERIES_ID, 1, 2)))
        }
    }

    private fun progress(contentId: String, season: Int?, episode: Int?) = WatchProgress(
        contentId = contentId,
        contentType = if (season == null) "movie" else "series",
        name = contentId,
        poster = null,
        backdrop = null,
        logo = null,
        videoId = contentId,
        season = season,
        episode = episode,
        episodeTitle = null,
        position = 1L,
        duration = 1L,
        lastWatched = 1_000L,
        progressPercent = 100f
    )

    private companion object {
        const val PROFILE_ID = 1
        const val MOVIE_ID = "tt0000021"
        const val SERIES_ID = "tt0000022"
    }
}
