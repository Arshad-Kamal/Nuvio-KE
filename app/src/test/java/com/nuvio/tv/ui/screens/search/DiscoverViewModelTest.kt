package com.nuvio.tv.ui.screens.search

import androidx.lifecycle.SavedStateHandle
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.WatchedSeriesStateHolder
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogExtra
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.DiscoverLocation
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import com.nuvio.tv.ui.components.posteroptions.PosterOptionsController
import com.nuvio.tv.ui.navigation.Screen
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Discover catalog list, request arguments, paging, and the OFF switch (wave C S6b). */
@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverViewModelTest {

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `discover hides unsatisfiable catalogs and sends the required default unless None is picked`() = runTest {
        val addon = testAddon(
            testCatalog(
                id = "calendar",
                extra = listOf(CatalogExtra(name = "calendarvideosids", isRequired = true))
            ),
            testCatalog(
                id = "bygenre",
                extra = listOf(
                    CatalogExtra(name = "genre", isRequired = true, options = listOf("Action", "None", "Drama"))
                )
            )
        )
        val catalogRepository = RecordingCatalogRepository(pageSize = 5)
        val viewModel = newViewModel(addon, catalogRepository)

        viewModel.ensureDiscoverLoaded()
        advanceUntilIdle()
        assertEquals(listOf("addon_movie_bygenre"), viewModel.uiState.value.discoverCatalogs.map { it.key })

        viewModel.selectDiscoverGenre("None")
        advanceUntilIdle()
        viewModel.selectDiscoverGenre("Drama")
        advanceUntilIdle()

        assertEquals(
            listOf(mapOf("genre" to "Action"), emptyMap(), mapOf("genre" to "Drama")),
            catalogRepository.requests.map { it.extraArgs }
        )
        assertEquals(listOf("bygenre", "bygenre", "bygenre"), catalogRepository.requests.map { it.catalogId })
    }

    @Test
    fun `discover next page skips by the received item count not skipStep`() = runTest {
        val addon = testAddon(testCatalog(id = "top", extra = listOf(CatalogExtra(name = "skip"))))
        val catalogRepository = RecordingCatalogRepository(pageSize = 20)
        val viewModel = newViewModel(addon, catalogRepository)

        viewModel.ensureDiscoverLoaded()
        advanceUntilIdle()
        viewModel.loadNextDiscoverResults()
        advanceUntilIdle()
        viewModel.loadNextDiscoverResults()
        advanceUntilIdle()

        // skipStep is the default 100; the addon serves 20 per page.
        assertEquals(listOf(0, 20, 40), catalogRepository.requests.map { it.skip })
        assertEquals(60, viewModel.uiState.value.discoverResults.size)
    }

    @Test
    fun `reveal shows 100 then 50 per step from pending without a request, then fetches`() = runTest {
        val addon = testAddon(testCatalog(id = "top", extra = listOf(CatalogExtra(name = "skip"))))
        val catalogRepository = RecordingCatalogRepository(pageSize = 200)
        val viewModel = newViewModel(addon, catalogRepository)

        viewModel.ensureDiscoverLoaded()
        advanceUntilIdle()
        assertEquals(100, viewModel.uiState.value.discoverResults.size)
        assertEquals(100, viewModel.uiState.value.pendingDiscoverResults.size)

        viewModel.loadNextDiscoverResults()
        advanceUntilIdle()
        assertEquals(150, viewModel.uiState.value.discoverResults.size)
        viewModel.loadNextDiscoverResults()
        advanceUntilIdle()
        assertEquals(200, viewModel.uiState.value.discoverResults.size)
        assertTrue(viewModel.uiState.value.pendingDiscoverResults.isEmpty())
        assertEquals(listOf(0), catalogRepository.requests.map { it.skip })

        // Pending is empty: the next step fetches and reveals one batch past what was visible.
        viewModel.loadNextDiscoverResults()
        advanceUntilIdle()
        assertEquals(listOf(0, 200), catalogRepository.requests.map { it.skip })
        assertEquals(250, viewModel.uiState.value.discoverResults.size)
        assertEquals(150, viewModel.uiState.value.pendingDiscoverResults.size)
    }

    @Test
    fun `selecting a type keeps the current catalog when it has that type, else the type's first`() = runTest {
        val addon = testAddon(
            testCatalog(id = "top", extra = emptyList()),
            testCatalog(id = "popular", extra = emptyList()),
            testCatalog(id = "top", extra = emptyList(), type = ContentType.SERIES)
        )
        val catalogRepository = RecordingCatalogRepository(pageSize = 5)
        val viewModel = newViewModel(addon, catalogRepository)

        viewModel.ensureDiscoverLoaded()
        advanceUntilIdle()
        assertEquals("addon_movie_top", viewModel.uiState.value.selectedDiscoverCatalogKey)

        viewModel.selectDiscoverCatalog("addon_movie_popular")
        advanceUntilIdle()
        viewModel.selectDiscoverType("movie")
        advanceUntilIdle()
        assertEquals("addon_movie_popular", viewModel.uiState.value.selectedDiscoverCatalogKey)

        viewModel.selectDiscoverType("series")
        advanceUntilIdle()
        assertEquals("addon_series_top", viewModel.uiState.value.selectedDiscoverCatalogKey)
        assertEquals("series", viewModel.uiState.value.selectedDiscoverType)
        assertEquals(
            listOf("top", "popular", "popular", "top"),
            catalogRepository.requests.map { it.catalogId }
        )
    }

    @Test
    fun `switching discover OFF resets state and cancels the in-flight fetch`() = runTest {
        val addon = testAddon(testCatalog(id = "top", extra = emptyList()))
        val catalogRepository = RecordingCatalogRepository(pageSize = 5)
        val gate = CompletableDeferred<Unit>()
        catalogRepository.gate = gate
        val location = MutableStateFlow(DiscoverLocation.IN_SIDEBAR)
        val viewModel = newViewModel(addon, catalogRepository, location)
        advanceUntilIdle()

        viewModel.ensureDiscoverLoaded()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.discoverLoading)
        assertEquals(1, catalogRepository.requests.size)

        location.value = DiscoverLocation.OFF
        advanceUntilIdle()
        with(viewModel.uiState.value) {
            assertFalse(discoverInitialized)
            assertFalse(discoverLoading)
            assertTrue(discoverCatalogs.isEmpty())
            assertEquals(null, selectedDiscoverCatalogKey)
        }

        // Back ON before the held response lands: a live job would now publish it.
        location.value = DiscoverLocation.IN_SIDEBAR
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.discoverResults.isEmpty())
    }

    @Test
    fun `switching discover OFF during the addon read cancels the catalog load`() = runTest {
        val addon = testAddon(testCatalog(id = "top", extra = emptyList()))
        val catalogRepository = RecordingCatalogRepository(pageSize = 5)
        val addonRepository = SingleAddonRepository(addon)
        val addonsGate = CompletableDeferred<Unit>()
        addonRepository.addonsGate = addonsGate
        val location = MutableStateFlow(DiscoverLocation.IN_SIDEBAR)
        val viewModel = newViewModel(addon, catalogRepository, location, addonRepository = addonRepository)
        advanceUntilIdle()

        viewModel.ensureDiscoverLoaded()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.discoverLoading)

        location.value = DiscoverLocation.OFF
        advanceUntilIdle()
        addonsGate.complete(Unit)
        advanceUntilIdle()

        // C6-R1: the suspended load used to resume here, mark Discover initialised in the
        // OFF-reset state and fetch.
        assertFalse(viewModel.uiState.value.discoverInitialized)
        assertTrue(catalogRepository.requests.isEmpty())
    }

    @Test
    fun `OFF then ON during the addon read loads the catalogs once`() = runTest {
        val addon = testAddon(testCatalog(id = "top", extra = emptyList()))
        val catalogRepository = RecordingCatalogRepository(pageSize = 5)
        val addonRepository = SingleAddonRepository(addon)
        val firstReadGate = CompletableDeferred<Unit>()
        val secondReadGate = CompletableDeferred<Unit>()
        val location = MutableStateFlow(DiscoverLocation.IN_SIDEBAR)
        val viewModel = newViewModel(addon, catalogRepository, location, addonRepository = addonRepository)
        advanceUntilIdle()

        addonRepository.addonsGate = firstReadGate
        viewModel.ensureDiscoverLoaded()
        advanceUntilIdle()
        location.value = DiscoverLocation.OFF
        advanceUntilIdle()
        location.value = DiscoverLocation.IN_SIDEBAR
        advanceUntilIdle()
        addonRepository.addonsGate = secondReadGate
        viewModel.ensureDiscoverLoaded()
        advanceUntilIdle()

        // The new load finishes first; the load from before OFF must not resume afterwards and
        // reset the shown results with a second fetch.
        secondReadGate.complete(Unit)
        advanceUntilIdle()
        firstReadGate.complete(Unit)
        advanceUntilIdle()

        with(viewModel.uiState.value) {
            assertTrue(discoverInitialized)
            assertFalse(discoverLoading)
            assertEquals(5, discoverResults.size)
        }
        assertEquals(1, catalogRepository.requests.size)
    }

    @Test
    fun `hide unreleased drops future titles from discover results`() = runTest {
        val addon = testAddon(testCatalog(id = "top", extra = emptyList()))
        val catalogRepository = RecordingCatalogRepository(pageSize = 3, unreleasedIds = setOf("m1"))
        val viewModel = newViewModel(addon, catalogRepository, hideUnreleased = true)
        advanceUntilIdle()

        viewModel.ensureDiscoverLoaded()
        advanceUntilIdle()

        assertEquals(listOf("m0", "m2"), viewModel.uiState.value.discoverResults.map { it.id })
    }

    @Test
    fun `genre route opens the type's first catalog listing the genre with it selected`() = runTest {
        val addon = testAddon(
            testCatalog(id = "top", extra = emptyList()),
            testCatalog(
                id = "bygenre",
                extra = listOf(CatalogExtra(name = "genre", options = listOf("Action", "Sci-Fi")))
            ),
            testCatalog(
                id = "seriesgenre",
                extra = listOf(CatalogExtra(name = "genre", options = listOf("Sci-Fi"))),
                type = ContentType.SERIES
            )
        )
        val catalogRepository = RecordingCatalogRepository(pageSize = 5)
        val route = SavedStateHandle(
            mapOf(Screen.Discover.TYPE_ARG to "series", Screen.Discover.GENRE_ARG to "sci-fi")
        )
        val viewModel = newViewModel(addon, catalogRepository, savedStateHandle = route)

        viewModel.ensureDiscoverLoaded()
        advanceUntilIdle()

        with(viewModel.uiState.value) {
            assertEquals("series", selectedDiscoverType)
            assertEquals("addon_series_seriesgenre", selectedDiscoverCatalogKey)
            assertEquals("Sci-Fi", selectedDiscoverGenre)
        }
        assertEquals(listOf(mapOf("genre" to "Sci-Fi")), catalogRepository.requests.map { it.extraArgs })
    }

    private fun newViewModel(
        addon: Addon,
        catalogRepository: CatalogRepository,
        location: Flow<DiscoverLocation> = flowOf(DiscoverLocation.IN_SEARCH),
        hideUnreleased: Boolean = false,
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
        addonRepository: SingleAddonRepository = SingleAddonRepository(addon)
    ): DiscoverViewModel {
        val layoutPreferences = mockk<LayoutPreferenceDataStore>()
        every { layoutPreferences.discoverLocation } returns location
        every { layoutPreferences.catalogTypeSuffixEnabled } returns flowOf(true)
        every { layoutPreferences.modernLandscapePostersEnabled } returns flowOf(false)
        every { layoutPreferences.hideUnreleasedContent } returns flowOf(hideUnreleased)

        val watchProgress = mockk<WatchProgressRepository>()
        every { watchProgress.observeWatchedMovieIds() } returns flowOf(emptySet())

        val watchedSeries = mockk<WatchedSeriesStateHolder>()
        every { watchedSeries.fullyWatchedSeriesIds } returns MutableStateFlow(emptySet())

        return DiscoverViewModel(
            addonRepository = addonRepository,
            catalogRepository = catalogRepository,
            metaPrefetcher = mockk(relaxed = true),
            layoutPreferenceDataStore = layoutPreferences,
            watchProgressRepository = watchProgress,
            watchedSeriesStateHolder = watchedSeries,
            posterOptions = mockk<PosterOptionsController>(relaxed = true),
            savedStateHandle = savedStateHandle
        )
    }
}
