package com.nuvio.tv.data.repository

import com.nuvio.tv.data.remote.api.ImdbTapframeApi
import com.nuvio.tv.data.remote.api.SeriesGraphApi
import com.nuvio.tv.data.remote.api.SeriesGraphEpisodeRatingDto
import com.nuvio.tv.data.remote.api.SeriesGraphSeasonRatingsDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException

/** A failed fetch must not be cached as "no ratings" for the 30-minute TTL. */
class ImdbEpisodeRatingsCacheTest {

    private val tapframe = mockk<ImdbTapframeApi>()
    private val seriesGraph = mockk<SeriesGraphApi>()
    private val repository = ImdbEpisodeRatingsRepository(tapframe, seriesGraph)

    private val ratings = listOf(
        SeriesGraphSeasonRatingsDto(
            episodes = listOf(SeriesGraphEpisodeRatingDto(seasonNumber = 1, episodeNumber = 2, voteAverage = 8.5))
        )
    )

    @Test
    fun `transport failure is not cached`() = runTest {
        coEvery { tapframe.getSeasonRatings(IMDB_ID) } throws IOException("offline") andThen Response.success(ratings)

        assertTrue(repository.getEpisodeRatings(IMDB_ID, tmdbId = null).isEmpty())
        assertEquals(mapOf((1 to 2) to 8.5), repository.getEpisodeRatings(IMDB_ID, tmdbId = null))
        coVerify(exactly = 2) { tapframe.getSeasonRatings(IMDB_ID) }
    }

    @Test
    fun `non-2xx is not cached`() = runTest {
        coEvery { tapframe.getSeasonRatings(IMDB_ID) } returns
            Response.error(503, "".toResponseBody()) andThen Response.success(ratings)

        assertTrue(repository.getEpisodeRatings(IMDB_ID, tmdbId = null).isEmpty())
        assertEquals(1, repository.getEpisodeRatings(IMDB_ID, tmdbId = null).size)
    }

    @Test
    fun `genuine empty result is cached`() = runTest {
        coEvery { tapframe.getSeasonRatings(IMDB_ID) } returns Response.success(emptyList())

        repository.getEpisodeRatings(IMDB_ID, tmdbId = null)
        repository.getEpisodeRatings(IMDB_ID, tmdbId = null)

        coVerify(exactly = 1) { tapframe.getSeasonRatings(IMDB_ID) }
    }

    private companion object {
        const val IMDB_ID = "tt0000003"
    }
}
