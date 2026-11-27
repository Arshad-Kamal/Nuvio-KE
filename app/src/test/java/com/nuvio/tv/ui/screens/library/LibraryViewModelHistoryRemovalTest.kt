package com.nuvio.tv.ui.screens.library

import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.data.local.ContinueWatchingSettingsDataStore
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.domain.repository.WatchProgressRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Spec 104 S2 (red team c902f7db): History › Remove passes the row's type, so a film's removal reaches the trackers.
 * Main is unconfined, as Main.immediate is on device: the History pipeline drops the row inside the hide, so the type
 * must be read before it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelHistoryRemovalTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `removing a film from History passes its type`() = runTest {
        val repository = mockk<WatchProgressRepository>(relaxed = true) {
            every { allProgress } returns flowOf(emptyList())
            every { watchedItems } returns MutableStateFlow(
                listOf(WatchedItem(contentId = "tt0133093", contentType = "movie", title = "The Matrix", watchedAt = 5L))
            )
            every { observeWatchedMovieIds() } returns flowOf(emptySet())
        }
        val viewModel = LibraryViewModel(
            libraryRepository = mockk(relaxed = true) {
                every { isSyncing } returns flowOf(false)
                every { libraryItems } returns flowOf(emptyList())
            },
            metaPrefetcher = mockk(relaxed = true),
            layoutPreferenceDataStore = mockk(relaxed = true) {
                every { modernLandscapePostersEnabled } returns flowOf(false)
            },
            libraryPreferences = mockk(relaxed = true) { every { lastSelectedType } returns flowOf(null) },
            authManager = mockk(relaxed = true) { every { authState } returns MutableStateFlow(AuthState.SignedOut) },
            watchProgressRepository = repository,
            watchedSeriesStateHolder = mockk(relaxed = true) { every { fullyWatchedSeriesIds } returns MutableStateFlow(emptySet()) },
            continueWatchingSettings = mockk<ContinueWatchingSettingsDataStore>(relaxed = true) {
                every { dismissedNextUpKeys } returns flowOf(emptySet())
            },
            watchCountDataStore = mockk(relaxed = true) { every { counts } returns flowOf(emptyMap()) },
            trackingSourceController = mockk { every { activeLibraryTracker } returns flowOf(null) },
            posterOptions = mockk(relaxed = true)
        )

        viewModel.removeFromHistory("tt0133093")

        coVerify(exactly = 1) { repository.removeFromHistory("tt0133093", null, null, "movie") }
    }
}
