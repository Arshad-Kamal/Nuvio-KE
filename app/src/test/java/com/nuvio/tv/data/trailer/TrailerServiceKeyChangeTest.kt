package com.nuvio.tv.data.trailer

import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.local.TrailerSettings
import com.nuvio.tv.data.local.TrailerSettingsDataStore
import com.nuvio.tv.data.local.TrailerSource
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbVideosResponse
import com.nuvio.tv.domain.model.TmdbSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Response

/**
 * Spec 52 S4: the IMDb path takes the item's own IMDb id (no TMDB key needed to convert one), and a
 * clearCache (a TMDB key or language change) wins over a resolve already in flight (S2 review).
 */
class TrailerServiceKeyChangeTest {

    @Test
    fun `imdbIdOf takes the item's base tt id only`() {
        assertEquals("tt0903747", imdbIdOf("tt0903747"))
        assertEquals("tt0903747", imdbIdOf("tt0903747:2:3"))
        assertNull(imdbIdOf("tmdb:1396"))
        assertNull(imdbIdOf("1396"))
        assertNull(imdbIdOf("tt"))
        assertNull(imdbIdOf("ttabc"))
    }

    @Test
    fun `the IMDb path uses the item's own id, with no TMDB conversion`() = runTest {
        val imdb = mockk<ImdbTrailerResolver> {
            coEvery { resolve("tt0903747") } returns TrailerPlaybackSource(videoUrl = IMDB_URL)
        }
        // No key: the TMDB id lookup finds nothing.
        val tmdbService = mockk<TmdbService> { coEvery { ensureTmdbId(any(), any()) } returns null }
        val service = service(tmdbService = tmdbService, imdb = imdb, source = TrailerSource.IMDB)

        val result = service.getTrailerPlaybackSourceForItem("tt0903747:2:3", "Breaking Bad", "2008", "series")

        assertEquals(IMDB_URL, result?.videoUrl)
        coVerify(exactly = 0) { tmdbService.tmdbToImdb(any(), any()) }
    }

    @Test
    fun `a miss resolved across clearCache is not kept`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val tmdbApi = blockingFirstCall(gate, calls)
        val service = service(tmdbApi = tmdbApi)

        val stale = async(Dispatchers.IO) { service.getTrailerPlaybackSource("Title", tmdbId = "123", type = "movie") }
        coVerify(timeout = TIMEOUT_MS, exactly = 1) { tmdbApi.getMovieVideos(any(), any(), any()) }
        service.clearCache()
        gate.complete(Unit)
        assertNull(stale.await())

        // Asks TMDB again: the old 3 h negative entry would have answered this.
        assertNull(service.getTrailerPlaybackSource("Title", tmdbId = "123", type = "movie"))
        assertEquals(2, calls.get())
    }

    @Test
    fun `a request after clearCache does not join a resolve started before it, nor lose its entry to it`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val tmdbApi = blockingFirstCall(gate, calls)
        val service = service(tmdbApi = tmdbApi)

        val stale = async(Dispatchers.IO) { service.getTrailerPlaybackSource("Title", tmdbId = "123", type = "movie") }
        coVerify(timeout = TIMEOUT_MS, exactly = 1) { tmdbApi.getMovieVideos(any(), any(), any()) }
        service.clearCache()

        // Its own resolve runs while the first is still held at the gate.
        val fresh = async(Dispatchers.IO) { service.getTrailerPlaybackSource("Title", tmdbId = "123", type = "movie") }
        coVerify(timeout = TIMEOUT_MS, exactly = 2) { tmdbApi.getMovieVideos(any(), any(), any()) }
        assertNull(fresh.await())

        gate.complete(Unit)
        assertNull(stale.await())

        // The stale finish left the fresh resolve's entry alone: this one is a cache hit.
        assertNull(service.getTrailerPlaybackSource("Title", tmdbId = "123", type = "movie"))
        assertEquals(2, calls.get())
    }

    // The first videos call waits for [gate]; every call answers with no videos (a miss).
    private fun blockingFirstCall(gate: CompletableDeferred<Unit>, calls: AtomicInteger) = mockk<TmdbApi> {
        coEvery { getMovieVideos(123, any(), any()) } coAnswers {
            if (calls.incrementAndGet() == 1) gate.await()
            Response.success(TmdbVideosResponse(id = 123))
        }
    }

    private fun service(
        tmdbApi: TmdbApi = mockk(relaxed = true),
        tmdbService: TmdbService = mockk { coEvery { apiKey() } returns "tmdb-key" },
        imdb: ImdbTrailerResolver = mockk(relaxed = true),
        source: TrailerSource = TrailerSource.YOUTUBE
    ) = TrailerService(
        tmdbApi = tmdbApi,
        inAppYouTubeExtractor = mockk(relaxed = true),
        tmdbSettingsDataStore = mockk<TmdbSettingsDataStore> {
            every { settings } returns MutableStateFlow(TmdbSettings(language = "en", useTrailers = source == TrailerSource.YOUTUBE))
        },
        tmdbService = tmdbService,
        imdbTrailerResolver = imdb,
        trailerSettingsDataStore = mockk<TrailerSettingsDataStore> {
            every { settings } returns MutableStateFlow(TrailerSettings(source = source))
        }
    )

    private companion object {
        const val IMDB_URL = "https://imdb.example/trailer.mp4"
        const val TIMEOUT_MS = 3_000L
    }
}
