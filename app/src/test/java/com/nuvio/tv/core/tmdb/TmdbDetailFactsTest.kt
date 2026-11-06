package com.nuvio.tv.core.tmdb

import com.nuvio.tv.data.mapper.certificationDescriptors
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbCollectionSummary
import com.nuvio.tv.data.remote.api.TmdbCompany
import com.nuvio.tv.data.remote.api.TmdbDetailsResponse
import com.nuvio.tv.data.remote.api.TmdbMovieReleaseDateCountry
import com.nuvio.tv.data.remote.api.TmdbMovieReleaseDateItem
import com.nuvio.tv.data.remote.api.TmdbMovieReleaseDatesResponse
import com.nuvio.tv.data.remote.api.TmdbTvContentRatingItem
import com.nuvio.tv.data.remote.api.TmdbTvContentRatingsResponse
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaCompany
import com.nuvio.tv.domain.model.MetaReleaseDate
import com.nuvio.tv.domain.model.MetaReleaseDateCountry
import com.nuvio.tv.domain.model.PosterShape
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Response

/** Spec 26e: TMDB facts on the addon path (B3, T4, T5, T15, X3). */
class TmdbDetailFactsTest {

    private fun movieApi(releases: List<TmdbMovieReleaseDateCountry>): TmdbApi {
        val api = mockk<TmdbApi>()
        coEvery { api.getMovieDetails(10, any(), any()) } returns Response.success(
            TmdbDetailsResponse(
                id = 10,
                title = "Die fabelhafte Welt",
                originalTitle = "Le Fabuleux Destin d'Amélie Poulain",
                voteAverage = 7.9,
                voteCount = 11_234,
                productionCompanies = listOf(TmdbCompany(id = 5, name = " Claudie Ossard ")),
                belongsToCollection = TmdbCollectionSummary(id = 87, name = "The Saga")
            )
        )
        coEvery { api.getMovieReleaseDates(10, any()) } returns Response.success(
            TmdbMovieReleaseDatesResponse(results = releases)
        )
        return api
    }

    private val releases = listOf(
        TmdbMovieReleaseDateCountry(
            iso31661 = "US",
            releaseDates = listOf(TmdbMovieReleaseDateItem(certification = "R", descriptors = listOf("sexuality")))
        ),
        TmdbMovieReleaseDateCountry(
            iso31661 = "DE",
            releaseDates = listOf(
                TmdbMovieReleaseDateItem(certification = "", releaseDate = "2001-08-16T00:00:00.000Z", type = 3),
                TmdbMovieReleaseDateItem(certification = "12", descriptors = listOf("Gewalt"), type = 5)
            )
        )
    )

    @Test
    fun `movie facts take the language region certification and cache the collection ref`() = runTest {
        val api = movieApi(releases)
        val service = TmdbMetadataService(api, TEST_TMDB_KEYS)

        val facts = service.fetchDetailFacts("10", ContentType.MOVIE, "de")!!

        assertEquals("Le Fabuleux Destin d'Amélie Poulain", facts.originalTitle)
        assertEquals(11_234, facts.voteCount)
        assertEquals(7.9, facts.voteAverage!!, 0.0)
        assertEquals(listOf("Claudie Ossard"), facts.productionCompanies.map { it.name })
        assertEquals("12", facts.ageRating)
        assertEquals(listOf("Gewalt"), facts.ageRatingDescriptors)
        assertEquals(listOf("US", "DE"), facts.releaseDates.map { it.countryCode })
        // The collection row reads the ref cached by the facts fetch: no second details request.
        assertEquals(TmdbCollectionRef(87, "The Saga"), service.fetchCollectionRef("10", ContentType.MOVIE, "de"))
        service.fetchDetailFacts("10", ContentType.MOVIE, "de")
        coVerify(exactly = 1) { api.getMovieDetails(any(), any(), any()) }
        coVerify(exactly = 1) { api.getMovieReleaseDates(any(), any()) }
    }

    @Test
    fun `movie certification falls back to US then any region`() = runTest {
        assertEquals("R", TmdbMetadataService(movieApi(releases), TEST_TMDB_KEYS).fetchDetailFacts("10", ContentType.MOVIE, "fr")?.ageRating)
        val onlyBrazil = listOf(
            TmdbMovieReleaseDateCountry(iso31661 = "BR", releaseDates = listOf(TmdbMovieReleaseDateItem(certification = "14")))
        )
        assertEquals("14", TmdbMetadataService(movieApi(onlyBrazil), TEST_TMDB_KEYS).fetchDetailFacts("10", ContentType.MOVIE, "en")?.ageRating)
    }

    @Test
    fun `a failed certification request keeps the details facts`() = runTest {
        val api = movieApi(releases)
        coEvery { api.getMovieReleaseDates(10, any()) } throws IOException("offline")

        val facts = TmdbMetadataService(api, TEST_TMDB_KEYS).fetchDetailFacts("10", ContentType.MOVIE, "en")!!

        assertNull(facts.ageRating)
        assertEquals(11_234, facts.voteCount)
    }

    @Test
    fun `tv facts read networks and content rating descriptors`() = runTest {
        val api = mockk<TmdbApi>()
        coEvery { api.getTvDetails(20, any(), any()) } returns Response.success(
            TmdbDetailsResponse(id = 20, name = "Show", originalName = "Show", voteCount = 0,
                networks = listOf(com.nuvio.tv.data.remote.api.TmdbNetwork(id = 49, name = "HBO")))
        )
        coEvery { api.getTvContentRatings(20, any()) } returns Response.success(
            TmdbTvContentRatingsResponse(
                results = listOf(
                    TmdbTvContentRatingItem(iso31661 = "GB", rating = "15"),
                    TmdbTvContentRatingItem(iso31661 = "US", rating = "TV-MA", descriptors = listOf("L", "V"))
                )
            )
        )

        val facts = TmdbMetadataService(api, TEST_TMDB_KEYS).fetchDetailFacts("20", ContentType.SERIES, "en")!!

        assertEquals("TV-MA", facts.ageRating)
        assertEquals(listOf("L", "V"), facts.ageRatingDescriptors)
        assertEquals(listOf("HBO"), facts.networks.map { it.name })
        assertNull(facts.voteCount)
        assertEquals(emptyList<MetaReleaseDateCountry>(), facts.releaseDates)
    }

    private fun meta(ageRating: String? = null, companies: List<MetaCompany> = emptyList()) = Meta(
        id = "tt1", type = ContentType.MOVIE, name = "Amelie", poster = null, posterShape = PosterShape.POSTER,
        background = null, logo = null, description = null, releaseInfo = null, imdbRating = 8.3f,
        genres = emptyList(), runtime = null, director = emptyList(), cast = emptyList(), videos = emptyList(),
        productionCompanies = companies, ageRating = ageRating, ageRatingDescriptors = listOf("addon reason"),
        country = null, awards = null, language = null, links = emptyList()
    )

    private val facts = TmdbDetailFacts(
        originalTitle = "Le Fabuleux", voteAverage = 7.9, voteCount = 100,
        productionCompanies = listOf(MetaCompany("TMDB Studio")), networks = emptyList(),
        ageRating = "12", ageRatingDescriptors = listOf("Gewalt"),
        releaseDates = listOf(MetaReleaseDateCountry("DE", listOf(MetaReleaseDate(certification = "12"))))
    )

    @Test
    fun `facts fill only empty addon fields`() {
        val merged = meta().withTmdbFacts(facts)
        assertEquals("12", merged.ageRating)
        assertEquals(listOf("Gewalt"), merged.ageRatingDescriptors)
        assertEquals(listOf("TMDB Studio"), merged.productionCompanies.map { it.name })
        assertEquals("Le Fabuleux", merged.originalTitle)
        assertEquals(100, merged.tmdbVoteCount)
        assertEquals(7.9f, merged.tmdbRating)
        assertEquals(8.3f, merged.imdbRating)

        val addon = meta(ageRating = "R", companies = listOf(MetaCompany("Addon Studio"))).withTmdbFacts(facts)
        assertEquals("R", addon.ageRating)
        assertEquals(listOf("addon reason"), addon.ageRatingDescriptors)
        assertEquals(listOf("Addon Studio"), addon.productionCompanies.map { it.name })
        assertEquals(meta(), meta().withTmdbFacts(null))
    }

    @Test
    fun `addon descriptors come from the release carrying its certification`() {
        val dates = listOf(
            MetaReleaseDateCountry("US", listOf(MetaReleaseDate(certification = "PG-13", descriptors = listOf("violence")))),
            MetaReleaseDateCountry("GB", listOf(MetaReleaseDate(certification = "12A", descriptors = listOf("threat"))))
        )
        assertEquals(listOf("threat"), certificationDescriptors("12A", dates))
        assertEquals(emptyList<String>(), certificationDescriptors("R", dates))
        assertEquals(emptyList<String>(), certificationDescriptors(null, dates))
        // Boards share codes: two countries' "PG" with different reasons is ambiguous.
        val shared = dates + MetaReleaseDateCountry("AU", listOf(MetaReleaseDate(certification = "PG-13", descriptors = listOf("themes"))))
        assertEquals(emptyList<String>(), certificationDescriptors("PG-13", shared))
    }
}
