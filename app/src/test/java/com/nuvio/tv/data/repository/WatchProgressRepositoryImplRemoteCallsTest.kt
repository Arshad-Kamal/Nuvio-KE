package com.nuvio.tv.data.repository

import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.recommendations.TvRecommendationManager
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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Pins how many Supabase calls each WatchProgressRepositoryImpl write makes today
 * (authenticated session, fresh repository). A change in these counts changes the
 * remote traffic/payloads, so it must be deliberate.
 */
class WatchProgressRepositoryImplRemoteCallsTest {

    private val profileManager = mockk<ProfileManager> {
        every { activeProfileId } returns MutableStateFlow(PROFILE_ID)
    }
    private val prefs = mockk<WatchProgressPreferences>(relaxed = true) {
        coEvery { getAllRawEntries(any()) } returns emptyMap()
    }
    private val watchedItemsPreferences = mockk<WatchedItemsPreferences>(relaxed = true)
    private val progressSync = mockk<WatchProgressSyncService>(relaxed = true) {
        coEvery { pushSingleToRemote(any(), any(), any()) } returns Result.success(Unit)
        coEvery { pushToRemote(any()) } returns Result.success(Unit)
        coEvery { deleteFromRemote(any(), any()) } returns Result.success(Unit)
    }
    private val watchedSync = mockk<WatchedItemsSyncService>(relaxed = true) {
        coEvery { pushItemsToRemote(any(), any()) } returns Result.success(Unit)
        coEvery { deleteFromRemote(any(), any(), any(), any()) } returns Result.success(Unit)
        coEvery { deleteFromRemoteBatch(any(), any(), any()) } returns Result.success(Unit)
    }
    private val authManager = mockk<AuthManager> {
        every { isAuthenticated } returns true
    }
    private val continueWatchingSettings = mockk<ContinueWatchingSettingsDataStore>(relaxed = true)

    init {
        // Production code calls some of these with a defaulted profileId whose default
        // reads the class's own profileManager field; a mock has that field unset.
        listOf(prefs, watchedItemsPreferences, progressSync, watchedSync, continueWatchingSettings)
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
        tvRecommendationManager = mockk<TvRecommendationManager>(relaxed = true)
    )

    @Test
    fun `saveProgress pushes the single entry once and nothing else`() = runTest {
        val progress = movieProgress(position = 5_000L)

        repository.saveProgress(progress, syncRemote = true)

        coVerify(timeout = ASYNC_TIMEOUT_MS, exactly = 1) {
            progressSync.pushSingleToRemote(MOVIE_ID, progress, PROFILE_ID)
        }
        // Settle past the push debounce so the negative checks below are meaningful.
        Thread.sleep(DEBOUNCE_SETTLE_MS)
        coVerify(exactly = 1) { progressSync.pushSingleToRemote(any(), any(), any()) }
        coVerify(exactly = 1) { prefs.saveProgress(progress, PROFILE_ID) }
        coVerify(exactly = 0) { progressSync.pushToRemote(any()) }
        coVerify(exactly = 0) { progressSync.deleteFromRemote(any(), any()) }
        coVerify(exactly = 0) { watchedSync.pushItemsToRemote(any(), any()) }
    }

    @Test
    fun `identical saveProgress within the dedup window pushes once`() = runTest {
        val progress = movieProgress(position = 5_000L)

        repository.saveProgress(progress, syncRemote = true)
        repository.saveProgress(progress.copy(lastWatched = progress.lastWatched + 1), syncRemote = true)

        coVerify(timeout = ASYNC_TIMEOUT_MS, exactly = 1) {
            progressSync.pushSingleToRemote(MOVIE_ID, any(), PROFILE_ID)
        }
        Thread.sleep(SETTLE_MS)
        coVerify(exactly = 1) { progressSync.pushSingleToRemote(any(), any(), any()) }
        coVerify(exactly = 2) { prefs.saveProgress(any(), PROFILE_ID) }
    }

    @Test
    fun `saveProgress with syncRemote false stays local`() = runTest {
        val progress = movieProgress(position = 5_000L)

        repository.saveProgress(progress, syncRemote = false)

        Thread.sleep(DEBOUNCE_SETTLE_MS)
        coVerify(exactly = 1) { prefs.saveProgress(progress, PROFILE_ID) }
        coVerify(exactly = 0) { progressSync.pushSingleToRemote(any(), any(), any()) }
        coVerify(exactly = 0) { progressSync.pushToRemote(any()) }
    }

    @Test
    fun `removeProgress makes exactly one remote progress delete`() = runTest {
        repository.removeProgress(SERIES_ID, season = 1, episode = 2)

        coVerify(exactly = 1) { progressSync.deleteFromRemote(any(), any()) }
        coVerify(exactly = 1) {
            progressSync.deleteFromRemote(listOf("${SERIES_ID}_s1e2", SERIES_ID), PROFILE_ID)
        }
        coVerify(exactly = 0) { watchedSync.deleteFromRemote(any(), any(), any(), any()) }
        // hasCompletedInitialPull is false on a fresh repository: no full push is scheduled.
        Thread.sleep(DEBOUNCE_SETTLE_MS)
        coVerify(exactly = 0) { progressSync.pushToRemote(any()) }
    }

    @Test
    fun `removeFromHistory deletes progress and the watched item remotely once each`() = runTest {
        repository.removeFromHistory(MOVIE_ID)

        coVerify(exactly = 1) { progressSync.deleteFromRemote(any(), any()) }
        coVerify(exactly = 1) { progressSync.deleteFromRemote(listOf(MOVIE_ID), PROFILE_ID) }
        coVerify(exactly = 1) { watchedSync.deleteFromRemote(any(), any(), any(), any()) }
        coVerify(exactly = 1) { watchedSync.deleteFromRemote(MOVIE_ID, null, null, PROFILE_ID) }
        Thread.sleep(DEBOUNCE_SETTLE_MS)
        coVerify(exactly = 0) { progressSync.pushToRemote(any()) }
    }

    @Test
    fun `markAsCompleted before the initial pulls makes no remote call`() = runTest {
        repository.markAsCompleted(movieProgress(position = 5_000L))

        Thread.sleep(DEBOUNCE_SETTLE_MS)
        coVerify(exactly = 0) { progressSync.pushToRemote(any()) }
        coVerify(exactly = 0) { progressSync.pushSingleToRemote(any(), any(), any()) }
        coVerify(exactly = 0) { watchedSync.pushItemsToRemote(any(), any()) }
    }

    @Test
    fun `markAsCompleted after the initial pulls pushes progress and the watched item once`() = runTest {
        repository.hasCompletedInitialPull = true
        repository.hasCompletedInitialWatchedItemsPull = true

        repository.markAsCompleted(movieProgress(position = 5_000L))

        coVerify(timeout = DEBOUNCED_TIMEOUT_MS, exactly = 1) { progressSync.pushToRemote(PROFILE_ID) }
        coVerify(timeout = DEBOUNCED_TIMEOUT_MS, exactly = 1) {
            watchedSync.pushItemsToRemote(match { it.size == 1 && it.single().contentId == MOVIE_ID }, PROFILE_ID)
        }
        // Timed verifies return at the first match; settle past the debounce to catch a second push.
        Thread.sleep(DEBOUNCE_SETTLE_MS)
        coVerify(exactly = 1) { progressSync.pushToRemote(any()) }
        coVerify(exactly = 1) { watchedSync.pushItemsToRemote(any(), any()) }
        coVerify(exactly = 0) { progressSync.pushSingleToRemote(any(), any(), any()) }
    }

    @Test
    fun `markAsCompletedBatch pushes progress once and all watched items in one batch`() = runTest {
        repository.hasCompletedInitialPull = true
        repository.hasCompletedInitialWatchedItemsPull = true
        val batch = listOf(episodeProgress(1), episodeProgress(2), episodeProgress(3))

        repository.markAsCompletedBatch(batch)

        coVerify(timeout = DEBOUNCED_TIMEOUT_MS, exactly = 1) { progressSync.pushToRemote(PROFILE_ID) }
        coVerify(timeout = DEBOUNCED_TIMEOUT_MS, exactly = 1) {
            watchedSync.pushItemsToRemote(match { it.size == 3 }, PROFILE_ID)
        }
        Thread.sleep(DEBOUNCE_SETTLE_MS)
        coVerify(exactly = 1) { progressSync.pushToRemote(any()) }
        coVerify(exactly = 1) { watchedSync.pushItemsToRemote(any(), any()) }
        coVerify(exactly = 1) { prefs.markAsCompletedBatch(batch, PROFILE_ID) }
    }

    private fun movieProgress(position: Long) = WatchProgress(
        contentId = MOVIE_ID,
        contentType = "movie",
        name = "Movie",
        poster = "poster",
        backdrop = null,
        logo = null,
        videoId = MOVIE_ID,
        season = null,
        episode = null,
        episodeTitle = null,
        position = position,
        duration = 100_000L,
        lastWatched = 1_000L
    )

    private fun episodeProgress(episode: Int) = WatchProgress(
        contentId = SERIES_ID,
        contentType = "series",
        name = "Series",
        poster = "poster",
        backdrop = null,
        logo = null,
        videoId = "$SERIES_ID:1:$episode",
        season = 1,
        episode = episode,
        episodeTitle = null,
        position = 1L,
        duration = 1L,
        lastWatched = 1_000L,
        progressPercent = 100f
    )

    private companion object {
        const val PROFILE_ID = 1
        const val MOVIE_ID = "tt0000001"
        const val SERIES_ID = "tt0000002"
        const val ASYNC_TIMEOUT_MS = 2_000L
        // Full-push and watched-item syncs are debounced by 2 s in the repository.
        const val DEBOUNCED_TIMEOUT_MS = 5_000L
        // Debounce + 500 ms: long enough for a debounced push (or a duplicate) to land.
        const val DEBOUNCE_SETTLE_MS = 2_500L
        const val SETTLE_MS = 300L
    }
}

/** Sets the mocked class's `profileManager` field so defaulted profileId arguments resolve. */
internal fun Any.injectProfileManager(profileManager: ProfileManager) {
    var type: Class<*>? = javaClass
    while (type != null) {
        val field = type.declaredFields.firstOrNull { it.name == "profileManager" }
        if (field != null) {
            field.isAccessible = true
            field.set(this, profileManager)
            return
        }
        type = type.superclass
    }
    error("No profileManager field on ${javaClass.name}")
}
