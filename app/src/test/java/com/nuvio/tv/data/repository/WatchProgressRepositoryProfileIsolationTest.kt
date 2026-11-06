package com.nuvio.tv.data.repository

import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ContinueWatchingSettingsDataStore
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchedItemsPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Spec 48 P2 (upstream NM 198acd375): poster-menu watched toggles stay with the profile that
 * made them; a revert after a profile switch still clears the toggle.
 */
class WatchProgressRepositoryProfileIsolationTest {

    private val profileFlow = MutableStateFlow(1)
    private val profileManager = mockk<ProfileManager> {
        every { activeProfileId } returns profileFlow
    }
    private val progressPreferences = mockk<WatchProgressPreferences>(relaxed = true) {
        every { allProgress } returns flowOf(emptyList())
    }
    private val watchedPreferences = mockk<WatchedItemsPreferences>(relaxed = true) {
        every { allItems } returns flowOf(emptyList())
    }
    private val continueWatchingSettings = mockk<ContinueWatchingSettingsDataStore>(relaxed = true)

    init {
        listOf(progressPreferences, watchedPreferences, continueWatchingSettings)
            .forEach { it.injectProfileManager(profileManager) }
    }

    private val repository = WatchProgressRepositoryImpl(
        watchProgressPreferences = progressPreferences,
        continueWatchingSettings = continueWatchingSettings,
        layoutPreferenceDataStore = mockk(relaxed = true),
        watchProgressSyncService = mockk(relaxed = true),
        watchedItemsPreferences = watchedPreferences,
        watchedItemsSyncService = mockk(relaxed = true),
        authManager = mockk<AuthManager> { every { isAuthenticated } returns false },
        metaRepository = mockk(relaxed = true),
        tmdbService = mockk(relaxed = true),
        profileManager = profileManager,
        tvRecommendationManager = mockk(relaxed = true)
    )

    @Test
    fun `optimistic watched toggle only applies to the profile that made it`() = runTest {
        repository.applyOptimisticWatchedMovie(setOf(MOVIE_ID), add = true)
        assertEquals(setOf(MOVIE_ID), repository.observeWatchedMovieIds().first())

        profileFlow.value = 2
        assertEquals(emptySet<String>(), repository.observeWatchedMovieIds().first())

        profileFlow.value = 1
        assertEquals(setOf(MOVIE_ID), repository.observeWatchedMovieIds().first())
    }

    @Test
    fun `a running collector follows the profile switch`() = runTest {
        val seen = mutableListOf<Set<String>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.observeWatchedMovieIds().toList(seen)
        }
        repository.applyOptimisticWatchedMovie(setOf(MOVIE_ID), add = true)
        advanceUntilIdle()
        assertEquals(setOf(MOVIE_ID), seen.last())

        profileFlow.value = 2
        advanceUntilIdle()
        assertEquals(emptySet<String>(), seen.last())
    }

    @Test
    fun `reverting an optimistic toggle after a profile switch clears it`() = runTest {
        repository.applyOptimisticWatchedMovie(setOf(MOVIE_ID), add = true)
        profileFlow.value = 2
        repository.revertOptimisticWatchedMovie(setOf(MOVIE_ID), add = true)

        profileFlow.value = 1
        assertEquals(emptySet<String>(), repository.observeWatchedMovieIds().first())
    }

    @Test
    fun `an optimistic removal stays with its profile`() = runTest {
        repository.applyOptimisticWatchedMovie(setOf(MOVIE_ID), add = false)
        repository.applyOptimisticWatchedMovie(setOf(MOVIE_ID), add = true)
        profileFlow.value = 2
        repository.applyOptimisticWatchedMovie(setOf(MOVIE_ID), add = false)

        profileFlow.value = 1
        assertEquals(setOf(MOVIE_ID), repository.observeWatchedMovieIds().first())
    }

    private companion object {
        const val MOVIE_ID = "tt0000001"
    }
}
