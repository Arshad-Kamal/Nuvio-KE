package com.nuvio.tv.data.trailer

import android.util.Log
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.domain.model.TmdbSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression coverage for issue #1647: the Trailers toggle in TMDB settings
 * must short-circuit trailer lookups.
 *
 * Before the fix, [TrailerService.getTrailerPlaybackSource] reached out to TMDB
 * unconditionally, so disabling the toggle had no observable effect.
 */
class TrailerServiceUseTrailersGateTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `getTrailerPlaybackSource returns null and does not call TMDB when useTrailers is false`() = runTest {
        val service = newServiceWithUseTrailers(enabled = false)
        val (tmdbApi, extractor) = service.collaborators

        val result = service.target.getTrailerPlaybackSource(
            title = "Some Movie",
            year = "2024",
            tmdbId = "12345",
            type = "movie"
        )

        assertNull("Trailer lookup must return null when useTrailers is disabled", result)
        // No TMDB calls of any kind should fire when the user has opted out.
        coVerify(exactly = 0) { tmdbApi.getMovieVideos(any(), any(), any()) }
        coVerify(exactly = 0) { tmdbApi.getTvVideos(any(), any(), any()) }
        coVerify(exactly = 0) { extractor.extractPlaybackSource(any()) }
    }

    @Test
    fun `item entry treats a failed TMDB id lookup as no id and still searches`() = runTest {
        val tmdbService = mockk<TmdbService> {
            coEvery { ensureTmdbId("tt1", "movie") } throws java.io.IOException("offline")
        }
        val service = newServiceWithUseTrailers(enabled = false, tmdbService = tmdbService)

        val result = service.target.getTrailerPlaybackSourceForItem(
            itemId = "tt1",
            title = "Some Movie",
            releaseInfo = "2024",
            apiType = "movie"
        )

        assertNull(result)
        coVerify(exactly = 1) { tmdbService.ensureTmdbId("tt1", "movie") }
    }

    @Test
    fun `item entry rethrows cancellation from the TMDB id lookup`() = runTest {
        val tmdbService = mockk<TmdbService> {
            coEvery { ensureTmdbId(any(), any()) } throws CancellationException("superseded")
        }
        val service = newServiceWithUseTrailers(enabled = true, tmdbService = tmdbService)

        val thrown = runCatching {
            service.target.getTrailerPlaybackSourceForItem(
                itemId = "tt1",
                title = "Some Movie",
                releaseInfo = "2024",
                apiType = "movie"
            )
        }.exceptionOrNull()

        assertTrue("cancellation must propagate, got $thrown", thrown is CancellationException)
    }

    private data class HarnessedService(
        val target: TrailerService,
        val collaborators: Collaborators
    )

    private data class Collaborators(
        val tmdbApi: TmdbApi,
        val extractor: InAppYouTubeExtractor
    )

    private fun newServiceWithUseTrailers(
        enabled: Boolean,
        tmdbService: TmdbService = mockk { coEvery { apiKey() } returns "tmdb-key" }
    ): HarnessedService {
        val tmdbApi = mockk<TmdbApi>(relaxed = true)
        val extractor = mockk<InAppYouTubeExtractor>(relaxed = true)
        val tmdbSettingsDataStore = mockk<TmdbSettingsDataStore> {
            every { settings } returns MutableStateFlow(TmdbSettings(language = "en", useTrailers = enabled))
        }
        val service = TrailerService(
            tmdbApi = tmdbApi,
            inAppYouTubeExtractor = extractor,
            tmdbSettingsDataStore = tmdbSettingsDataStore,
            tmdbService = tmdbService,
            imdbTrailerResolver = mockk(relaxed = true),
            trailerSettingsDataStore = mockk(relaxed = true)
        )
        return HarnessedService(
            target = service,
            collaborators = Collaborators(tmdbApi, extractor)
        )
    }
}
