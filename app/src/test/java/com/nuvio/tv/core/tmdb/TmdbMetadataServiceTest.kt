package com.nuvio.tv.core.tmdb

import com.nuvio.tv.data.remote.api.TmdbAggregateCreditsResponse
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbCollectionResponse
import com.nuvio.tv.data.remote.api.TmdbCollectionSummary
import com.nuvio.tv.data.remote.api.TmdbCompany
import com.nuvio.tv.data.remote.api.TmdbCompanyDetailsResponse
import com.nuvio.tv.data.remote.api.TmdbCreditsResponse
import com.nuvio.tv.data.remote.api.TmdbDetailsResponse
import com.nuvio.tv.data.remote.api.TmdbDiscoverResponse
import com.nuvio.tv.data.remote.api.TmdbDiscoverResult
import com.nuvio.tv.data.remote.api.TmdbImagesResponse
import com.nuvio.tv.data.remote.api.TmdbMovieReleaseDatesResponse
import com.nuvio.tv.data.remote.api.TmdbNetwork
import com.nuvio.tv.data.remote.api.TmdbNetworkDetailsResponse
import com.nuvio.tv.data.remote.api.TmdbPersonCreditCast
import com.nuvio.tv.data.remote.api.TmdbPersonCreditCrew
import com.nuvio.tv.data.remote.api.TmdbPersonCreditsResponse
import com.nuvio.tv.data.remote.api.TmdbPersonResponse
import com.nuvio.tv.data.remote.api.TmdbRecommendationsResponse
import com.nuvio.tv.data.remote.api.TmdbTvContentRatingsResponse
import com.nuvio.tv.data.remote.api.TmdbVideosResponse
import com.nuvio.tv.domain.model.ContentType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class TmdbMetadataServiceTest {

    @Test
    fun `fetchEnrichment maps tmdb ids onto production and network companies`() = runTest {
        val api = mockk<TmdbApi>()
        coEvery { api.getMovieDetails(any(), any(), any()) } returns Response.success(
            TmdbDetailsResponse(
                id = 10,
                productionCompanies = listOf(
                    TmdbCompany(id = 55, name = "Acme Pictures", logoPath = "/company.png")
                ),
                networks = listOf(
                    TmdbNetwork(id = 77, name = "Prime TV", logoPath = "/network.png")
                )
            )
        )
        coEvery { api.getMovieCredits(any(), any(), any()) } returns Response.success(TmdbCreditsResponse())
        coEvery { api.getMovieImages(any(), any(), any()) } returns Response.success(TmdbImagesResponse())
        coEvery { api.getMovieReleaseDates(any(), any()) } returns Response.success(TmdbMovieReleaseDatesResponse())
        coEvery { api.getMovieVideos(any(), any(), any()) } returns Response.success(TmdbVideosResponse(id = 10))

        val service = TmdbMetadataService(api, TEST_TMDB_KEYS)

        val enrichment = service.fetchEnrichment(
            tmdbId = "10",
            contentType = ContentType.MOVIE,
            language = "en"
        )

        assertNotNull(enrichment)
        assertEquals(55, enrichment?.productionCompanies?.firstOrNull()?.tmdbId)
        assertEquals(77, enrichment?.networks?.firstOrNull()?.tmdbId)
    }

    @Test
    fun `fetchEnrichment formats ongoing tv release range`() = runTest {
        val api = mockk<TmdbApi>()
        coEvery { api.getTvDetails(any(), any(), any()) } returns Response.success(
            TmdbDetailsResponse(
                id = 20,
                name = "Ongoing Show",
                firstAirDate = "2012-09-20",
                lastAirDate = "2024-03-10",
                status = "Returning Series"
            )
        )
        coEvery { api.getTvAggregateCredits(any(), any(), any()) } returns Response.success(TmdbAggregateCreditsResponse())
        coEvery { api.getTvImages(any(), any(), any()) } returns Response.success(TmdbImagesResponse())
        coEvery { api.getTvContentRatings(any(), any()) } returns Response.success(TmdbTvContentRatingsResponse())
        coEvery { api.getTvVideos(any(), any(), any()) } returns Response.success(TmdbVideosResponse(id = 20))

        val service = TmdbMetadataService(api, TEST_TMDB_KEYS)

        val enrichment = service.fetchEnrichment(
            tmdbId = "20",
            contentType = ContentType.SERIES,
            language = "en"
        )

        assertEquals("2012-", enrichment?.releaseInfo)
    }

    @Test
    fun `fetchEnrichment formats ended tv release range`() = runTest {
        val api = mockk<TmdbApi>()
        coEvery { api.getTvDetails(any(), any(), any()) } returns Response.success(
            TmdbDetailsResponse(
                id = 21,
                name = "Ended Show",
                firstAirDate = "2012-09-20",
                lastAirDate = "2019-05-19",
                status = "Ended"
            )
        )
        coEvery { api.getTvAggregateCredits(any(), any(), any()) } returns Response.success(TmdbAggregateCreditsResponse())
        coEvery { api.getTvImages(any(), any(), any()) } returns Response.success(TmdbImagesResponse())
        coEvery { api.getTvContentRatings(any(), any()) } returns Response.success(TmdbTvContentRatingsResponse())
        coEvery { api.getTvVideos(any(), any(), any()) } returns Response.success(TmdbVideosResponse(id = 21))

        val service = TmdbMetadataService(api, TEST_TMDB_KEYS)

        val enrichment = service.fetchEnrichment(
            tmdbId = "21",
            contentType = ContentType.SERIES,
            language = "en"
        )

        assertEquals("2012-2019", enrichment?.releaseInfo)
    }

    @Test
    fun `fetchEnrichment deduplicates concurrent requests for same key`() = runTest {
        val api = mockk<TmdbApi>()
        val gate = CompletableDeferred<Unit>()
        var detailsCalls = 0
        var creditsCalls = 0
        var imagesCalls = 0
        var releaseCalls = 0

        coEvery { api.getMovieDetails(any(), any(), any()) } coAnswers {
            detailsCalls += 1
            gate.await()
            Response.success(TmdbDetailsResponse(id = 10, title = "Movie", overview = "Synopsis"))
        }
        coEvery { api.getMovieCredits(any(), any(), any()) } coAnswers {
            creditsCalls += 1
            Response.success(TmdbCreditsResponse())
        }
        coEvery { api.getMovieImages(any(), any(), any()) } coAnswers {
            imagesCalls += 1
            Response.success(TmdbImagesResponse())
        }
        coEvery { api.getMovieReleaseDates(any(), any()) } coAnswers {
            releaseCalls += 1
            Response.success(TmdbMovieReleaseDatesResponse())
        }
        coEvery { api.getMovieVideos(any(), any(), any()) } returns Response.success(TmdbVideosResponse(id = 10))

        val service = TmdbMetadataService(api, StandardTestDispatcher(testScheduler), TEST_TMDB_KEYS)

        val first = async { service.fetchEnrichment(tmdbId = "10", contentType = ContentType.MOVIE, language = "en") }
        val second = async { service.fetchEnrichment(tmdbId = "10", contentType = ContentType.MOVIE, language = "en") }

        advanceUntilIdle()
        assertEquals(1, detailsCalls)

        gate.complete(Unit)
        val results = awaitAll(first, second)

        assertEquals(1, detailsCalls)
        assertEquals(1, creditsCalls)
        assertEquals(1, imagesCalls)
        assertEquals(1, releaseCalls)
        assertEquals(results[0], results[1])
    }

    @Test
    fun `company browse requests movie then tv rails when source type is movie`() = runTest {
        val api = mockk<TmdbApi>()
        val movieCalls = mutableListOf<MovieDiscoverCall>()
        val tvCalls = mutableListOf<TvDiscoverCall>()

        coEvery { api.getCompanyDetails(99, any()) } returns Response.success(
            TmdbCompanyDetailsResponse(
                id = 99,
                name = "Acme Pictures",
                originCountry = "US"
            )
        )
        coEvery {
            api.discoverMovies(any(), any(), any(), any(), any(), any(), any())
        } answers {
            movieCalls += MovieDiscoverCall(
                sortBy = arg(3),
                withCompanies = arg(4),
                releaseDateLte = arg(5),
                voteCountGte = arg(6)
            )
            Response.success(
                TmdbDiscoverResponse(
                    results = listOf(
                        TmdbDiscoverResult(
                            id = movieCalls.size,
                            title = "Movie ${movieCalls.size}",
                            posterPath = "/movie-${movieCalls.size}.jpg",
                            backdropPath = "/movie-bg-${movieCalls.size}.jpg",
                            releaseDate = "2024-01-01",
                            voteAverage = 7.4
                        )
                    )
                )
            )
        }
        coEvery {
            api.discoverTv(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            tvCalls += TvDiscoverCall(
                sortBy = arg(3),
                withCompanies = arg(4),
                withNetworks = arg(5),
                firstAirDateLte = arg(6),
                voteCountGte = arg(7),
                withStatus = arg(16)
            )
            Response.success(
                TmdbDiscoverResponse(
                    results = listOf(
                        TmdbDiscoverResult(
                            id = 100 + tvCalls.size,
                            name = "Show ${tvCalls.size}",
                            posterPath = "/show-${tvCalls.size}.jpg",
                            backdropPath = "/show-bg-${tvCalls.size}.jpg",
                            firstAirDate = "2023-03-10",
                            voteAverage = 8.0
                        )
                    )
                )
            )
        }

        // Rails are fetched concurrently; a test dispatcher keeps the recording mock single-threaded.
        val service = TmdbMetadataService(api, StandardTestDispatcher(testScheduler), TEST_TMDB_KEYS)

        val data = service.fetchEntityBrowse(
            entityKind = TmdbEntityKind.COMPANY,
            entityId = 99,
            sourceType = "movie",
            fallbackName = "Acme Pictures",
            language = "en"
        )

        assertNotNull(data)
        assertEquals(
            listOf(
                TmdbEntityMediaType.MOVIE,
                TmdbEntityMediaType.MOVIE,
                TmdbEntityMediaType.MOVIE,
                TmdbEntityMediaType.TV,
                TmdbEntityMediaType.TV,
                TmdbEntityMediaType.TV
            ),
            data?.rails?.map { it.mediaType }
        )
        assertEquals(3, movieCalls.size)
        assertEquals(3, tvCalls.size)
        assertTrue(movieCalls.all { it.withCompanies == "99" })
        assertTrue(tvCalls.all { it.withCompanies == "99" })
        assertTrue(tvCalls.all { it.withNetworks == null })
        assertEquals(200, movieCalls.first { it.sortBy == "vote_average.desc" }.voteCountGte)
        assertTrue(movieCalls.first { it.sortBy == "primary_release_date.desc" }.releaseDateLte != null)
        assertTrue(tvCalls.first { it.sortBy == "first_air_date.desc" }.firstAirDateLte != null)
        assertTrue(data?.rails?.flatMap { it.items }.orEmpty().all { it.id.startsWith("tmdb:") })
    }

    @Test
    fun `company browse keeps rail order when rails complete in reverse launch order`() = runTest {
        val api = mockk<TmdbApi>()
        // Rails launch movie popular/top rated/recent, then tv; each later rail finishes
        // sooner, so the first-launched rail completes last.
        val movieDelays = mapOf("popularity.desc" to 600L, "vote_average.desc" to 500L, "primary_release_date.desc" to 400L)
        val tvDelays = mapOf("popularity.desc" to 300L, "vote_average.desc" to 200L, "first_air_date.desc" to 100L)
        val completed = mutableListOf<Pair<TmdbEntityMediaType, String>>()
        val page = Response.success(
            TmdbDiscoverResponse(results = listOf(TmdbDiscoverResult(id = 1, title = "Item", posterPath = "/i.jpg")))
        )

        coEvery { api.getCompanyDetails(99, any()) } returns Response.success(
            TmdbCompanyDetailsResponse(id = 99, name = "Acme Pictures")
        )
        coEvery {
            api.discoverMovies(any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            val sortBy: String = arg(3)
            delay(movieDelays.getValue(sortBy))
            completed += TmdbEntityMediaType.MOVIE to sortBy
            page
        }
        coEvery {
            api.discoverTv(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            val sortBy: String = arg(3)
            delay(tvDelays.getValue(sortBy))
            completed += TmdbEntityMediaType.TV to sortBy
            page
        }

        val service = TmdbMetadataService(api, StandardTestDispatcher(testScheduler), TEST_TMDB_KEYS)

        val data = service.fetchEntityBrowse(
            entityKind = TmdbEntityKind.COMPANY,
            entityId = 99,
            sourceType = "movie",
            fallbackName = "Acme Pictures",
            language = "en"
        )

        // Completion order really differs from launch order...
        assertEquals(TmdbEntityMediaType.TV to "first_air_date.desc", completed.first())
        assertEquals(TmdbEntityMediaType.MOVIE to "popularity.desc", completed.last())
        // ...yet the rails come back in launch order.
        assertEquals(
            listOf(
                TmdbEntityMediaType.MOVIE to TmdbEntityRailType.POPULAR,
                TmdbEntityMediaType.MOVIE to TmdbEntityRailType.TOP_RATED,
                TmdbEntityMediaType.MOVIE to TmdbEntityRailType.RECENT,
                TmdbEntityMediaType.TV to TmdbEntityRailType.POPULAR,
                TmdbEntityMediaType.TV to TmdbEntityRailType.TOP_RATED,
                TmdbEntityMediaType.TV to TmdbEntityRailType.RECENT
            ),
            data?.rails?.map { it.mediaType to it.railType }
        )
    }

    @Test
    fun `browse with a failed rail is not cached and the next browse refetches that rail`() = runTest {
        val api = mockk<TmdbApi>()
        var topRatedCalls = 0
        val page = Response.success(
            TmdbDiscoverResponse(results = listOf(TmdbDiscoverResult(id = 1, title = "Item", posterPath = "/i.jpg")))
        )

        coEvery { api.getCompanyDetails(99, any()) } returns Response.success(
            TmdbCompanyDetailsResponse(id = 99, name = "Acme Pictures")
        )
        coEvery {
            api.discoverMovies(any(), any(), any(), any(), any(), any(), any())
        } answers {
            val sortBy: String = arg(3)
            if (sortBy == "vote_average.desc") {
                topRatedCalls += 1
                if (topRatedCalls == 1) throw IOException("offline")
            }
            page
        }
        coEvery {
            api.discoverTv(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns page

        val service = TmdbMetadataService(api, StandardTestDispatcher(testScheduler), TEST_TMDB_KEYS)

        suspend fun browse() = service.fetchEntityBrowse(
            entityKind = TmdbEntityKind.COMPANY,
            entityId = 99,
            sourceType = "movie",
            fallbackName = "Acme Pictures",
            language = "en"
        )
        val topRated = TmdbEntityMediaType.MOVIE to TmdbEntityRailType.TOP_RATED

        val first = browse()
        val second = browse()

        assertFalse(first?.rails.orEmpty().any { (it.mediaType to it.railType) == topRated })
        assertTrue(second?.rails.orEmpty().any { (it.mediaType to it.railType) == topRated })
        assertEquals(6, second?.rails?.size)
        assertEquals(2, topRatedCalls)
    }

    @Test
    fun `network browse only requests tv rails and scopes by network id`() = runTest {
        val api = mockk<TmdbApi>()
        val tvCalls = mutableListOf<TvDiscoverCall>()

        coEvery { api.getNetworkDetails(77, any()) } returns Response.success(
            TmdbNetworkDetailsResponse(
                id = 77,
                name = "Prime TV",
                originCountry = "US"
            )
        )
        coEvery {
            api.discoverTv(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            tvCalls += TvDiscoverCall(
                sortBy = arg(3),
                withCompanies = arg(4),
                withNetworks = arg(5),
                firstAirDateLte = arg(6),
                voteCountGte = arg(7),
                withStatus = arg(16)
            )
            Response.success(
                TmdbDiscoverResponse(
                    results = listOf(
                        TmdbDiscoverResult(
                            id = 500 + tvCalls.size,
                            name = "Network Show ${tvCalls.size}",
                            posterPath = "/network-${tvCalls.size}.jpg",
                            backdropPath = "/network-bg-${tvCalls.size}.jpg",
                            firstAirDate = "2022-07-01",
                            voteAverage = 8.3
                        )
                    )
                )
            )
        }
        coEvery {
            api.discoverMovies(any(), any(), any(), any(), any(), any(), any())
        } throws AssertionError("movie discovery must not run for networks")

        // Rails are fetched concurrently; a test dispatcher keeps the recording mock single-threaded.
        val service = TmdbMetadataService(api, StandardTestDispatcher(testScheduler), TEST_TMDB_KEYS)

        val data = service.fetchEntityBrowse(
            entityKind = TmdbEntityKind.NETWORK,
            entityId = 77,
            sourceType = "series",
            fallbackName = "Prime TV",
            language = "en"
        )

        assertNotNull(data)
        assertEquals(3, tvCalls.size)
        assertTrue(data?.rails?.all { it.mediaType == TmdbEntityMediaType.TV } == true)
        assertTrue(tvCalls.all { it.withNetworks == "77" })
        assertTrue(tvCalls.all { it.withCompanies == null })
        assertTrue(tvCalls.all { it.withStatus == "0|3|4" })
        assertNull(tvCalls.firstOrNull { it.sortBy == "popularity.desc" }?.voteCountGte)
        assertEquals(200, tvCalls.first { it.sortBy == "vote_average.desc" }.voteCountGte)
    }

    @Test
    fun `a cancelled first caller does not fail a second waiter on the shared enrichment`() = runTest {
        val api = mockk<TmdbApi>()
        val gate = CompletableDeferred<Unit>()
        var detailsCalls = 0
        coEvery { api.getMovieDetails(any(), any(), any()) } coAnswers {
            detailsCalls += 1
            gate.await()
            Response.success(TmdbDetailsResponse(id = 10, title = "Movie", overview = "Synopsis"))
        }
        coEvery { api.getMovieCredits(any(), any(), any()) } returns Response.success(TmdbCreditsResponse())
        coEvery { api.getMovieImages(any(), any(), any()) } returns Response.success(TmdbImagesResponse())
        coEvery { api.getMovieReleaseDates(any(), any()) } returns Response.success(TmdbMovieReleaseDatesResponse())
        coEvery { api.getMovieVideos(any(), any(), any()) } returns Response.success(TmdbVideosResponse(id = 10))

        val service = TmdbMetadataService(api, StandardTestDispatcher(testScheduler), TEST_TMDB_KEYS)

        val first = async { service.fetchEnrichment(tmdbId = "10", contentType = ContentType.MOVIE, language = "en") }
        advanceUntilIdle()
        val second = async { service.fetchEnrichment(tmdbId = "10", contentType = ContentType.MOVIE, language = "en") }
        advanceUntilIdle()

        first.cancel()
        gate.complete(Unit)

        assertEquals("Synopsis", second.await()?.description)
        assertTrue(first.isCancelled)
        assertEquals(1, detailsCalls)
    }

    @Test
    fun `collection ref skips series without any request`() = runTest {
        val api = mockk<TmdbApi>()

        val service = TmdbMetadataService(api, TEST_TMDB_KEYS)

        assertNull(service.fetchCollectionRef(tmdbId = "20", contentType = ContentType.SERIES, language = "en"))
        assertNull(service.fetchCollectionRef(tmdbId = "20", contentType = ContentType.TV, language = "en"))
        coVerify(exactly = 0) { api.getTvDetails(any(), any(), any()) }
        coVerify(exactly = 0) { api.getMovieDetails(any(), any(), any()) }
    }

    @Test
    fun `collection ref for a movie is one cached details request`() = runTest {
        val api = mockk<TmdbApi>()
        coEvery { api.getMovieDetails(10, any(), "en") } returns Response.success(
            TmdbDetailsResponse(
                id = 10,
                title = "Movie",
                belongsToCollection = TmdbCollectionSummary(id = 87, name = "The Saga")
            )
        )

        val service = TmdbMetadataService(api, TEST_TMDB_KEYS)

        val first = service.fetchCollectionRef(tmdbId = "10", contentType = ContentType.MOVIE, language = "en")
        val second = service.fetchCollectionRef(tmdbId = "10", contentType = ContentType.MOVIE, language = "en")

        assertEquals(TmdbCollectionRef(id = 87, name = "The Saga"), first)
        assertEquals(first, second)
        coVerify(exactly = 1) { api.getMovieDetails(any(), any(), any()) }
    }

    @Test
    fun `collection ref caches a movie that belongs to no collection`() = runTest {
        val api = mockk<TmdbApi>()
        coEvery { api.getMovieDetails(10, any(), "en") } returns Response.success(
            TmdbDetailsResponse(id = 10, title = "Movie", belongsToCollection = null)
        )

        val service = TmdbMetadataService(api, StandardTestDispatcher(testScheduler), TEST_TMDB_KEYS)

        assertNull(service.fetchCollectionRef(tmdbId = "10", contentType = ContentType.MOVIE, language = "en"))
        assertNull(service.fetchCollectionRef(tmdbId = "10", contentType = ContentType.MOVIE, language = "en"))
        coVerify(exactly = 1) { api.getMovieDetails(any(), any(), any()) }
    }

    @Test
    fun `collection ref retries after a failed lookup`() = runTest {
        val api = mockk<TmdbApi>()
        var calls = 0
        coEvery { api.getMovieDetails(10, any(), "en") } answers {
            calls += 1
            if (calls == 1) throw IOException("offline")
            Response.success(
                TmdbDetailsResponse(
                    id = 10,
                    title = "Movie",
                    belongsToCollection = TmdbCollectionSummary(id = 87, name = "The Saga")
                )
            )
        }

        val service = TmdbMetadataService(api, StandardTestDispatcher(testScheduler), TEST_TMDB_KEYS)

        assertNull(service.fetchCollectionRef(tmdbId = "10", contentType = ContentType.MOVIE, language = "en"))
        assertEquals(
            TmdbCollectionRef(id = 87, name = "The Saga"),
            service.fetchCollectionRef(tmdbId = "10", contentType = ContentType.MOVIE, language = "en")
        )
        coVerify(exactly = 2) { api.getMovieDetails(any(), any(), any()) }
    }

    @Test
    fun `cached rail page keeps the last-page hasMore`() = runTest {
        val api = mockk<TmdbApi>()
        coEvery {
            api.discoverMovies(any(), any(), any(), any(), any(), any(), any())
        } returns Response.success(
            TmdbDiscoverResponse(
                page = 1,
                totalPages = 1,
                results = listOf(TmdbDiscoverResult(id = 1, title = "Only", posterPath = "/only.jpg"))
            )
        )

        val service = TmdbMetadataService(api, TEST_TMDB_KEYS)

        suspend fun page() = service.fetchEntityRailPage(
            entityKind = TmdbEntityKind.COMPANY,
            entityId = 99,
            mediaType = TmdbEntityMediaType.MOVIE,
            railType = TmdbEntityRailType.POPULAR,
            language = "en",
            page = 1
        )

        val fresh = page()
        val cached = page()

        assertFalse(fresh.hasMore)
        assertFalse(cached.hasMore)
        assertEquals(fresh, cached)
        coVerify(exactly = 1) { api.discoverMovies(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `browse built on a failed header is not cached`() = runTest {
        val api = mockk<TmdbApi>()
        var headerCalls = 0
        coEvery { api.getNetworkDetails(77, any()) } answers {
            headerCalls += 1
            if (headerCalls == 1) throw IOException("offline")
            Response.success(TmdbNetworkDetailsResponse(id = 77, name = "Prime TV"))
        }
        coEvery {
            api.discoverTv(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns Response.success(
            TmdbDiscoverResponse(results = listOf(TmdbDiscoverResult(id = 5, name = "Show", posterPath = "/s.jpg")))
        )

        val service = TmdbMetadataService(api, StandardTestDispatcher(testScheduler), TEST_TMDB_KEYS)

        suspend fun browse() = service.fetchEntityBrowse(
            entityKind = TmdbEntityKind.NETWORK,
            entityId = 77,
            sourceType = "series",
            fallbackName = "Fallback",
            language = "en"
        )

        assertEquals("Fallback", browse()?.header?.name)
        assertEquals("Prime TV", browse()?.header?.name)
        assertEquals("Prime TV", browse()?.header?.name)
        assertEquals(2, headerCalls)
    }

    @Test
    fun `browse is null when the header and every rail failed, even with a route name`() = runTest {
        val api = mockk<TmdbApi>()
        var headerOnline = false
        coEvery { api.getNetworkDetails(77, any()) } answers {
            if (!headerOnline) throw IOException("offline")
            Response.success(TmdbNetworkDetailsResponse(id = 77, name = "Prime TV"))
        }
        coEvery {
            api.discoverTv(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } throws IOException("offline")

        val service = TmdbMetadataService(api, StandardTestDispatcher(testScheduler), TEST_TMDB_KEYS)

        suspend fun browse() = service.fetchEntityBrowse(
            entityKind = TmdbEntityKind.NETWORK,
            entityId = 77,
            sourceType = "series",
            fallbackName = "Fallback",
            language = "en"
        )

        assertNull(browse())
        // A header with every rail failed is still a page (its empty state), not an error.
        headerOnline = true
        val headerOnly = browse()
        assertEquals("Prime TV", headerOnly?.header?.name)
        assertEquals(emptyList<TmdbEntityRail>(), headerOnly?.rails)
    }

    private fun personApi(
        cast: List<TmdbPersonCreditCast> = emptyList(),
        crew: List<TmdbPersonCreditCrew> = emptyList(),
        person: TmdbPersonResponse = TmdbPersonResponse(id = 7, name = "Someone", knownForDepartment = "Acting")
    ): TmdbApi {
        val api = mockk<TmdbApi>()
        coEvery { api.getPersonDetails(7, any(), "en") } returns Response.success(person)
        coEvery { api.getPersonCombinedCredits(7, any(), "en") } returns Response.success(
            TmdbPersonCreditsResponse(cast = cast, crew = crew)
        )
        return api
    }

    @Test
    fun `person credits map into poster previews, newest first, posterless counted`() = runTest {
        val api = personApi(
            cast = listOf(
                TmdbPersonCreditCast(id = 1, title = "Old", mediaType = "movie", posterPath = "/1.jpg", releaseDate = "2001-02-03", voteAverage = 9.0),
                TmdbPersonCreditCast(id = 2, title = "New", mediaType = "movie", posterPath = "/2.jpg", backdropPath = "/2b.jpg", releaseDate = "2005-01-01", voteAverage = 8.0, overview = "Plot", character = "Celia"),
                TmdbPersonCreditCast(id = 9, title = "No poster", mediaType = "movie", voteAverage = 9.0),
                TmdbPersonCreditCast(id = 9, title = "No poster", mediaType = "movie", character = "Again"),
                TmdbPersonCreditCast(id = 12, title = "Blank poster", mediaType = "movie", posterPath = " ", releaseDate = "2009-01-01"),
                TmdbPersonCreditCast(id = 8, title = "Undated", mediaType = "movie", posterPath = "/8.jpg"),
                TmdbPersonCreditCast(id = 10, title = "Upcoming", mediaType = "movie", posterPath = "/10.jpg", releaseDate = "2031-01-01"),
                TmdbPersonCreditCast(id = 3, name = "Show", title = "Show title", mediaType = "tv", posterPath = "/3.jpg", firstAirDate = "2010-05-05", releaseDate = "1999-01-01", voteAverage = 7.0, episodeCount = 4),
                TmdbPersonCreditCast(id = 11, title = "Person entry", mediaType = "person", posterPath = "/11.jpg")
            )
        )
        val service = TmdbMetadataService(api, TEST_TMDB_KEYS)

        val person = service.fetchPersonDetail(personId = 7, language = "en")!!
        val acting = person.departments.single()
        assertEquals("Acting", acting.name)
        // 29/Q5: upcoming credits stay, newest first; undated last.
        assertEquals(listOf("Upcoming", "Show", "New", "Old", "Undated"), acting.credits.map { it.preview.name })
        val new = acting.credits[2]
        assertEquals("tmdb:2", new.preview.id)
        assertEquals(ContentType.MOVIE, new.preview.type)
        assertEquals("2005", new.preview.releaseInfo)
        assertEquals("2005-01-01", new.date)
        assertEquals(listOf("Celia"), new.roles)
        assertEquals("https://image.tmdb.org/t/p/w500/2.jpg", new.preview.poster)
        assertEquals("https://image.tmdb.org/t/p/w1280/2b.jpg", new.preview.background)
        assertEquals("Plot", new.preview.description)
        // 26/B2: a TMDB vote average is never labelled IMDb.
        assertEquals(8.0f, new.preview.tmdbRating)
        assertNull(new.preview.imdbRating)
        val show = acting.credits[1]
        assertEquals("tmdb:3", show.preview.id)
        assertEquals(ContentType.SERIES, show.preview.type)
        assertEquals("2010", show.preview.releaseInfo)
        assertEquals(4, show.episodeCount)
        assertEquals(emptyList<String>(), show.roles)
        assertNull(acting.credits.last().date)
        assertEquals(2, person.creditsWithoutPoster)
    }

    @Test
    fun `person credits keep every department, one card per title per department with roles joined`() = runTest {
        val api = personApi(
            cast = listOf(
                TmdbPersonCreditCast(id = 5, title = "Film", mediaType = "movie", posterPath = "/5.jpg", releaseDate = "2020-01-01", character = "Herself")
            ),
            crew = listOf(
                TmdbPersonCreditCrew(id = 5, title = "Film", mediaType = "movie", posterPath = "/5.jpg", releaseDate = "2020-01-01", department = "Directing", job = "Director"),
                TmdbPersonCreditCrew(id = 5, title = "Film", mediaType = "movie", posterPath = "/5.jpg", releaseDate = "2020-01-01", department = "Writing", job = "Writer"),
                TmdbPersonCreditCrew(id = 5, title = "Film", mediaType = "movie", posterPath = "/5.jpg", releaseDate = "2020-01-01", department = "Writing", job = "Story"),
                TmdbPersonCreditCrew(id = 5, title = "Film", mediaType = "movie", posterPath = "/5.jpg", releaseDate = "2020-01-01", department = "Writing", job = "Writer"),
                // 29/Q9: TV id 5 is a different title from movie id 5.
                TmdbPersonCreditCrew(id = 5, name = "Series", mediaType = "tv", posterPath = "/s5.jpg", firstAirDate = "2015-01-01", department = "Writing", job = "Writer", episodeCount = 3),
                TmdbPersonCreditCrew(id = 5, name = "Series", mediaType = "tv", posterPath = "/s5.jpg", firstAirDate = "2015-01-01", department = "Writing", job = "Creator", episodeCount = 9),
                TmdbPersonCreditCrew(id = 6, title = "Untagged", mediaType = "movie", posterPath = "/6.jpg", job = "Thanks")
            )
        )
        val service = TmdbMetadataService(api, TEST_TMDB_KEYS)

        val person = service.fetchPersonDetail(personId = 7, language = "en")!!
        // Most credits first, ties by name.
        assertEquals(listOf("Writing", "Acting", "Crew", "Directing"), person.departments.map { it.name })
        val writing = person.departments.first().credits
        assertEquals(listOf("movie:tmdb:5", "series:tmdb:5"), writing.map { "${it.preview.apiType}:${it.preview.id}" })
        assertEquals(listOf("Writer", "Story"), writing[0].roles)
        assertNull(writing[0].episodeCount)
        assertEquals(listOf("Writer", "Creator"), writing[1].roles)
        assertEquals(9, writing[1].episodeCount)
        assertEquals(listOf("Herself"), person.departments[1].credits.single().roles)
        assertEquals(listOf("Director"), person.departments[3].credits.single().roles)
        assertEquals(0, person.creditsWithoutPoster)
    }

    @Test
    fun `person also-known-as drops blanks, repeats and the person's own name`() = runTest {
        val api = personApi(
            person = TmdbPersonResponse(
                id = 7,
                name = "Mira Halden",
                alsoKnownAs = listOf(" Mira H. ", "Mira Halden", "", "Mira Halden-Vos", "Mira H.")
            )
        )
        val service = TmdbMetadataService(api, TEST_TMDB_KEYS)

        val person = service.fetchPersonDetail(personId = 7, language = "en")!!
        assertEquals(listOf("Mira H.", "Mira Halden-Vos"), person.alsoKnownAs)
        assertTrue(person.departments.isEmpty())
    }

    private data class MovieDiscoverCall(
        val sortBy: String?,
        val withCompanies: String?,
        val releaseDateLte: String?,
        val voteCountGte: Int?
    )

    private data class TvDiscoverCall(
        val sortBy: String?,
        val withCompanies: String?,
        val withNetworks: String?,
        val firstAirDateLte: String?,
        val voteCountGte: Int?,
        val withStatus: String?
    )

    @Test
    fun `a failed recommendations request is not cached, so the row fills once a key works`() = runTest {
        val api = mockk<TmdbApi>()
        var calls = 0
        // Matching the exact key also proves the call site sends the key source's key.
        coEvery { api.getMovieRecommendations(5, TEST_TMDB_KEY, "en", any()) } answers {
            calls += 1
            if (calls == 1) Response.error(401, "".toResponseBody(null))
            else Response.success(TmdbRecommendationsResponse(results = emptyList()))
        }
        val service = TmdbMetadataService(api, StandardTestDispatcher(testScheduler), TEST_TMDB_KEYS)

        assertEquals(emptyList<Any>(), service.fetchMoreLikeThis("5", ContentType.MOVIE, "en"))
        service.fetchMoreLikeThis("5", ContentType.MOVIE, "en")
        service.fetchMoreLikeThis("5", ContentType.MOVIE, "en")

        // The 401 was retried; the success that followed is cached.
        assertEquals(2, calls)
    }

    @Test
    fun `a failed movie collection request is not cached`() = runTest {
        val api = mockk<TmdbApi>()
        var calls = 0
        coEvery { api.getCollectionDetails(87, TEST_TMDB_KEY, "en") } answers {
            calls += 1
            if (calls == 1) Response.error(401, "".toResponseBody(null))
            else Response.success(TmdbCollectionResponse(id = 87, parts = emptyList()))
        }
        val service = TmdbMetadataService(api, StandardTestDispatcher(testScheduler), TEST_TMDB_KEYS)

        service.fetchMovieCollection(87, "en")
        service.fetchMovieCollection(87, "en")
        service.fetchMovieCollection(87, "en")

        assertEquals(2, calls)
    }
}
