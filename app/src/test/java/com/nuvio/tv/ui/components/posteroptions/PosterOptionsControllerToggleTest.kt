package com.nuvio.tv.ui.components.posteroptions

import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.WatchedSeriesStateHolder
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PosterOptionsControllerToggleTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `series mark with failed meta fetch reverts the optimistic badge and writes nothing`() = runTest {
        val holderUpdates = mutableListOf<Set<String>>()
        val seriesIds = MutableStateFlow(emptySet<String>())
        val watchedSeriesStateHolder = holderMock(seriesIds, holderUpdates)
        val watchProgressRepository = mockk<WatchProgressRepository>(relaxed = true)
        val controller = newController(
            watchProgressRepository = watchProgressRepository,
            watchedSeriesStateHolder = watchedSeriesStateHolder,
            metaResult = flowOf(NetworkResult.Error("addon down"))
        )
        val bindScope = CoroutineScope(coroutineContext + Job())
        controller.bind(bindScope)

        controller.show(seriesPreview(), addonBaseUrl = null)
        runCurrent()
        controller.toggleSeriesWatched()
        runCurrent()

        // Optimistic add was applied, then reverted: the badge never persists the lie.
        assertEquals(listOf(setOf(SERIES_ID), emptySet<String>()), holderUpdates)
        coVerify(exactly = 0) { watchProgressRepository.markAsCompletedBatch(any()) }
        coVerify(exactly = 0) { watchProgressRepository.markAsCompleted(any()) }
        assertEquals(false, controller.state.value.isWatchedPending)
        bindScope.cancel()
    }

    @Test
    fun `failed series mark revert keeps a badge change made while the fetch ran`() = runTest {
        val holderUpdates = mutableListOf<Set<String>>()
        val seriesIds = MutableStateFlow(emptySet<String>())
        val watchedSeriesStateHolder = holderMock(seriesIds, holderUpdates)
        val controller = newController(
            watchedSeriesStateHolder = watchedSeriesStateHolder,
            metaResult = flowOf(NetworkResult.Error("addon down"))
        )
        val bindScope = CoroutineScope(coroutineContext + Job())
        controller.bind(bindScope)

        controller.show(seriesPreview(), addonBaseUrl = null)
        runCurrent()
        controller.toggleSeriesWatched()
        // The CW badge pass marks another series before the toggle's fetch completes.
        seriesIds.value = seriesIds.value + OTHER_SERIES_ID
        runCurrent()

        // Only the toggled id is reverted; the concurrent badge survives.
        assertEquals(setOf(OTHER_SERIES_ID), seriesIds.value)
        bindScope.cancel()
    }

    @Test
    fun `series mark with successful meta keeps the badge and writes episode progress`() = runTest {
        val holderUpdates = mutableListOf<Set<String>>()
        val seriesIds = MutableStateFlow(emptySet<String>())
        val watchedSeriesStateHolder = holderMock(seriesIds, holderUpdates)
        val watchProgressRepository = mockk<WatchProgressRepository>(relaxed = true)
        val controller = newController(
            watchProgressRepository = watchProgressRepository,
            watchedSeriesStateHolder = watchedSeriesStateHolder,
            metaResult = flowOf(NetworkResult.Success(metaWithTwoEpisodes()))
        )
        val bindScope = CoroutineScope(coroutineContext + Job())
        controller.bind(bindScope)

        controller.show(seriesPreview(), addonBaseUrl = null)
        runCurrent()
        controller.toggleSeriesWatched()
        runCurrent()

        assertEquals(listOf(setOf(SERIES_ID)), holderUpdates)
        coVerify(exactly = 1) {
            watchProgressRepository.markAsCompletedBatch(withArg { batch ->
                assertEquals(listOf(1 to 1, 1 to 2), batch.map { it.season to it.episode })
            })
        }
        bindScope.cancel()
    }

    @Test
    fun `series unmark with failed meta fetch removes whole-series history`() = runTest {
        val holderUpdates = mutableListOf<Set<String>>()
        val seriesIds = MutableStateFlow(setOf(SERIES_ID))
        val watchedSeriesStateHolder = holderMock(seriesIds, holderUpdates)
        val watchProgressRepository = mockk<WatchProgressRepository>(relaxed = true)
        val controller = newController(
            watchProgressRepository = watchProgressRepository,
            watchedSeriesStateHolder = watchedSeriesStateHolder,
            metaResult = flowOf(NetworkResult.Error("addon down"))
        )
        val bindScope = CoroutineScope(coroutineContext + Job())
        controller.bind(bindScope)

        controller.show(seriesPreview(), addonBaseUrl = null)
        runCurrent()
        assertEquals(true, controller.state.value.isWatched)
        controller.toggleSeriesWatched()
        runCurrent()

        // Fetch failed but the removal still applies (series-level key), so no revert:
        // the badge ends unwatched and the fallback removal ran.
        assertEquals(listOf(emptySet<String>()), holderUpdates)
        coVerify(exactly = 1) {
            watchProgressRepository.removeFromHistory(SERIES_ID, season = null, episode = null)
        }
        bindScope.cancel()
    }

    @Test
    fun `library toggle writes through the local library and clears pending`() = runTest {
        val libraryRepository = baseLibraryMock()
        val controller = newController(libraryRepository = libraryRepository)
        val bindScope = CoroutineScope(coroutineContext + Job())
        controller.bind(bindScope)

        controller.show(moviePreview(), addonBaseUrl = null)
        runCurrent()
        controller.toggleLibrary()
        runCurrent()

        assertEquals(false, controller.state.value.isLibraryPending)
        coVerify(exactly = 1) {
            libraryRepository.toggleDefault(match { it.itemId == MOVIE_ID && it.itemType == "movie" })
        }
        bindScope.cancel()
    }

    @Test
    fun `library toggle on a landscape item saves the portrait poster and shape`() = runTest {
        // B2 (Home poster options): an addon row with posterShape=landscape carries the
        // backdrop in `poster`; the library entry must store the portrait rawPosterUrl.
        val libraryRepository = baseLibraryMock()
        val controller = newController(libraryRepository = libraryRepository)
        val bindScope = CoroutineScope(coroutineContext + Job())
        controller.bind(bindScope)

        controller.show(
            moviePreview().copy(
                poster = LANDSCAPE_URL,
                posterShape = PosterShape.LANDSCAPE,
                rawPosterUrl = PORTRAIT_URL
            ),
            addonBaseUrl = "https://addon.example"
        )
        runCurrent()
        controller.toggleLibrary()
        runCurrent()

        coVerify(exactly = 1) {
            libraryRepository.toggleDefault(match {
                it.poster == PORTRAIT_URL &&
                    it.posterShape == PosterShape.POSTER &&
                    it.addonBaseUrl == "https://addon.example"
            })
        }
        bindScope.cancel()
    }

    @Test
    fun `anime mark watched writes episode progress instead of no-op`() = runTest {
        // Home's old copy offered "mark watched" for anime but rejected it silently.
        val holderUpdates = mutableListOf<Set<String>>()
        val seriesIds = MutableStateFlow(emptySet<String>())
        val watchedSeriesStateHolder = holderMock(seriesIds, holderUpdates)
        val watchProgressRepository = mockk<WatchProgressRepository>(relaxed = true)
        val controller = newController(
            watchProgressRepository = watchProgressRepository,
            watchedSeriesStateHolder = watchedSeriesStateHolder,
            metaResult = flowOf(NetworkResult.Success(metaWithTwoEpisodes()))
        )
        val bindScope = CoroutineScope(coroutineContext + Job())
        controller.bind(bindScope)

        controller.show(animePreview(), addonBaseUrl = null)
        runCurrent()
        controller.toggleSeriesWatched()
        runCurrent()

        assertEquals(listOf(setOf(ANIME_ID)), holderUpdates)
        coVerify(exactly = 1) {
            watchProgressRepository.markAsCompletedBatch(withArg { batch ->
                assertTrue(batch.all { it.contentId == ANIME_ID && it.contentType == "anime" })
                assertEquals(listOf(1 to 1, 1 to 2), batch.map { it.season to it.episode })
            })
        }
        assertEquals(false, controller.state.value.isWatchedPending)
        bindScope.cancel()
    }

    @Test
    fun `library toggle failure resets pending without leaving state behind`() = runTest {
        val libraryRepository = baseLibraryMock().apply {
            coEvery { toggleDefault(any()) } throws IllegalStateException("library write failed")
        }
        val controller = newController(libraryRepository = libraryRepository)
        val bindScope = CoroutineScope(coroutineContext + Job())
        controller.bind(bindScope)

        controller.show(moviePreview(), addonBaseUrl = null)
        runCurrent()
        controller.toggleLibrary()
        runCurrent()

        assertEquals(false, controller.state.value.isLibraryPending)
        bindScope.cancel()
    }

    @Test
    fun `movie watched toggle failure reverts the optimistic overlay`() = runTest {
        val watchProgressRepository = mockk<WatchProgressRepository>(relaxed = true) {
            every { isWatched(any(), any(), any()) } returns flowOf(false)
            coEvery { markAsCompleted(any()) } throws IllegalStateException("disk full")
        }
        val controller = newController(watchProgressRepository = watchProgressRepository)
        val bindScope = CoroutineScope(coroutineContext + Job())
        controller.bind(bindScope)

        controller.show(moviePreview(), addonBaseUrl = null)
        runCurrent()
        controller.toggleMovieWatched()
        runCurrent()

        coVerify(exactly = 1) {
            watchProgressRepository.revertOptimisticWatchedMovie(setOf(MOVIE_ID), add = true)
        }
        assertEquals(false, controller.state.value.isWatchedPending)
        bindScope.cancel()
    }

    private fun holderMock(
        seriesIds: MutableStateFlow<Set<String>>,
        updates: MutableList<Set<String>>
    ): WatchedSeriesStateHolder = mockk(relaxed = true) {
        every { fullyWatchedSeriesIds } returns seriesIds
        // Applies the controller's transform to the current ids, as the holder does under its lock.
        every { update(any()) } answers {
            val transform = firstArg<(Set<String>) -> Set<String>>()
            seriesIds.value = transform(seriesIds.value)
            updates += seriesIds.value
        }
    }

    private fun baseLibraryMock(): LibraryRepository = mockk(relaxed = true) {
        every { isInLibrary(any(), any()) } returns flowOf(false)
    }

    private fun newController(
        libraryRepository: LibraryRepository = baseLibraryMock(),
        watchProgressRepository: WatchProgressRepository = mockk(relaxed = true) {
            every { isWatched(any(), any(), any()) } returns flowOf(false)
        },
        watchedSeriesStateHolder: WatchedSeriesStateHolder = mockk(relaxed = true) {
            every { fullyWatchedSeriesIds } returns MutableStateFlow(emptySet<String>())
        },
        metaResult: kotlinx.coroutines.flow.Flow<NetworkResult<Meta>> = flowOf(NetworkResult.Error("no meta"))
    ): PosterOptionsController {
        val metaRepository = mockk<MetaRepository>(relaxed = true) {
            every { getMetaFromPrimaryAddon(any(), any()) } returns metaResult
        }
        return PosterOptionsController(
            libraryRepository = libraryRepository,
            watchProgressRepository = watchProgressRepository,
            metaRepository = metaRepository,
            watchedSeriesStateHolder = watchedSeriesStateHolder,
            tmdbService = mockk<TmdbService>(relaxed = true)
        )
    }

    private fun moviePreview(): MetaPreview = MetaPreview(
        id = MOVIE_ID,
        type = ContentType.MOVIE,
        name = "Sample Movie",
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList()
    )

    private fun seriesPreview(): MetaPreview = MetaPreview(
        id = SERIES_ID,
        type = ContentType.SERIES,
        name = "Sample Series",
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList()
    )

    private fun animePreview(): MetaPreview = MetaPreview(
        id = ANIME_ID,
        type = ContentType.UNKNOWN,
        rawType = "anime",
        name = "Sample Anime",
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList()
    )

    private fun metaWithTwoEpisodes(): Meta = Meta(
        id = SERIES_ID,
        type = ContentType.SERIES,
        name = "Sample Series",
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList(),
        runtime = null,
        director = emptyList(),
        cast = emptyList(),
        videos = listOf(
            Video(id = "e1", title = "Episode 1", released = null, thumbnail = null, season = 1, episode = 1, overview = null),
            Video(id = "e2", title = "Episode 2", released = null, thumbnail = null, season = 1, episode = 2, overview = null)
        ),
        links = emptyList(),
        country = null,
        awards = null,
        language = null
    )

    private companion object {
        const val MOVIE_ID = "tt1234567"
        const val SERIES_ID = "tt7654321"
        const val OTHER_SERIES_ID = "tt7650000"
        const val ANIME_ID = "tt5550001"
        const val LANDSCAPE_URL = "https://img.example/backdrop.jpg"
        const val PORTRAIT_URL = "https://img.example/portrait.jpg"
    }
}
