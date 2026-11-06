package com.nuvio.tv.ui.screens.search

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.SearchHistoryDataStore
import com.nuvio.tv.data.local.WatchedSeriesStateHolder
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.DiscoverLocation
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import com.nuvio.tv.ui.components.posteroptions.PosterOptionsController
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Search's side of the Discover split: Search itself never loads Discover (DiscoverViewModelTest owns Discover). */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelDiscoverTest {

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Wave C S6a: Search never renders Discover, so it never loads it (the Discover route does). */
    @Test
    fun `emptying the query never loads discover from search`() = runTest {
        for (location in listOf(DiscoverLocation.IN_SEARCH, DiscoverLocation.IN_SIDEBAR)) {
            val catalogRepository = RecordingCatalogRepository(pageSize = 5)
            val viewModel = newViewModel(
                testAddon(testCatalog(id = "top", extra = emptyList())),
                catalogRepository,
                location = location
            )
            advanceUntilIdle()

            viewModel.onEvent(SearchEvent.QueryChanged(""))
            advanceUntilIdle()

            assertTrue("$location", catalogRepository.requests.isEmpty())
        }
    }

    private fun newViewModel(
        addon: Addon,
        catalogRepository: CatalogRepository,
        location: DiscoverLocation
    ): SearchViewModel {
        val layoutPreferences = mockk<LayoutPreferenceDataStore>()
        every { layoutPreferences.discoverLocation } returns flowOf(location)
        every { layoutPreferences.catalogAddonNameEnabled } returns flowOf(true)
        every { layoutPreferences.catalogTypeSuffixEnabled } returns flowOf(true)
        every { layoutPreferences.modernLandscapePostersEnabled } returns flowOf(false)
        every { layoutPreferences.hideUnreleasedContent } returns flowOf(false)

        val history = mockk<SearchHistoryDataStore>(relaxed = true)
        every { history.recentSearches } returns flowOf(emptyList())

        val watchProgress = mockk<WatchProgressRepository>()
        every { watchProgress.observeWatchedMovieIds() } returns flowOf(emptySet())

        val watchedSeries = mockk<WatchedSeriesStateHolder>()
        every { watchedSeries.fullyWatchedSeriesIds } returns MutableStateFlow(emptySet())

        return SearchViewModel(
            addonRepository = SingleAddonRepository(addon),
            catalogRepository = catalogRepository,
            metaPrefetcher = mockk(relaxed = true),
            layoutPreferenceDataStore = layoutPreferences,
            searchHistoryDataStore = history,
            watchProgressRepository = watchProgress,
            watchedSeriesStateHolder = watchedSeries,
            posterOptions = mockk<PosterOptionsController>(relaxed = true),
            context = mockk<Context>(relaxed = true),
            savedStateHandle = SavedStateHandle()
        )
    }
}
