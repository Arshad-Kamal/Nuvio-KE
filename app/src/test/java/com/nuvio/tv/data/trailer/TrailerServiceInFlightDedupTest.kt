package com.nuvio.tv.data.trailer

import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.local.TrailerSettings
import com.nuvio.tv.data.local.TrailerSettingsDataStore
import com.nuvio.tv.data.local.TrailerSource
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbVideoResult
import com.nuvio.tv.data.remote.api.TmdbVideosResponse
import com.nuvio.tv.domain.model.TmdbSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response

/** Concurrent resolves of the same trailer key share one resolve (one WebView / extraction). */
class TrailerServiceInFlightDedupTest {

    private val gate = CompletableDeferred<Unit>()
    private val extractor = mockk<InAppYouTubeExtractor> {
        coEvery { extractPlaybackSource(any()) } coAnswers {
            gate.await()
            TrailerPlaybackSource(videoUrl = "https://www.youtube.com/watch?v=" + firstArg<String>())
        }
    }
    private val tmdbApi = mockk<TmdbApi> {
        coEvery { getMovieVideos(movieId = 123, apiKey = "tmdb-key", language = "en-US") } returns
            Response.success(
                TmdbVideosResponse(
                    id = 123,
                    results = listOf(TmdbVideoResult(key = YT_KEY, site = "YouTube", type = "Trailer"))
                )
            )
    }
    private val service = TrailerService(
        tmdbApi = tmdbApi,
        inAppYouTubeExtractor = extractor,
        tmdbSettingsDataStore = mockk<TmdbSettingsDataStore> {
            every { settings } returns MutableStateFlow(TmdbSettings(language = "en", useTrailers = true))
        },
        tmdbService = mockk<TmdbService> { coEvery { apiKey() } returns "tmdb-key" },
        imdbTrailerResolver = mockk<ImdbTrailerResolver>(relaxed = true),
        trailerSettingsDataStore = mockk<TrailerSettingsDataStore> {
            every { settings } returns MutableStateFlow(TrailerSettings(source = TrailerSource.YOUTUBE))
        }
    )

    @Test
    fun `concurrent resolves of the same key run one extraction`() = runTest {
        val first = async(Dispatchers.IO) { service.getTrailerPlaybackSource("Title", tmdbId = "123", type = "movie") }
        coVerify(timeout = TIMEOUT_MS, exactly = 1) { extractor.extractPlaybackSource(any()) }
        val second = async(Dispatchers.IO) { service.getTrailerPlaybackSource("Title", tmdbId = "123", type = "movie") }
        Thread.sleep(SETTLE_MS) // let the second caller reach the in-flight map before releasing the first
        gate.complete(Unit)

        val expected = "https://www.youtube.com/watch?v=$YT_KEY"
        assertEquals(expected, first.await()?.videoUrl)
        assertEquals(expected, second.await()?.videoUrl)
        coVerify(exactly = 1) { extractor.extractPlaybackSource(any()) }
        coVerify(exactly = 1) { tmdbApi.getMovieVideos(any(), any(), any()) }
    }

    private companion object {
        const val YT_KEY = "dQw4w9WgXcQ"
        const val TIMEOUT_MS = 3_000L
        const val SETTLE_MS = 300L
    }
}
