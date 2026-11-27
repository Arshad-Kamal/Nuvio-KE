package com.nuvio.tv.core.scout

import com.nuvio.tv.core.tmdb.TmdbApiKeySource
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbRecommendationResult
import com.nuvio.tv.data.remote.api.TmdbRecommendationsResponse
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.Response
import java.io.IOException

class ScoutPicksBuilderTest {

    private val api = mockk<TmdbApi>()
    private val service = mockk<TmdbService> {
        every { cachedTmdbId(any()) } returns null
    }
    private val now = 10L * 24 * 60 * 60 * 1000

    @Test
    fun `no key means no request and no picks`() = runTest {
        val builder = ScoutPicksBuilder(api, service, TmdbApiKeySource { "" })

        assertTrue(builder.build(listOf(film("tt1", "Sintel")), emptyList(), emptyList(), "en", now).isEmpty())
        coVerify(exactly = 0) { service.ensureTmdbId(any(), any()) }
        coVerify(exactly = 0) { api.getMovieRecommendations(any(), any(), any(), any()) }
    }

    @Test
    fun `seeds resolve to tmdb ids and their pages rank with watched titles left out`() = runTest {
        coEvery { service.ensureTmdbId("tt1", "movie") } returns "45745"
        coEvery { service.ensureTmdbId("tmdb:133701", "movie") } returns "133701"
        coEvery { api.getMovieRecommendations(45745, KEY, "en", 1) } returns page(rec(133701, "Tears of Steel"), rec(500, "Spring"))
        coEvery { api.getMovieRecommendations(133701, KEY, "en", 1) } returns page(rec(45745, "Sintel"), rec(501, "Cosmos"))
        val builder = ScoutPicksBuilder(api, service, TmdbApiKeySource { KEY })

        val picks = builder.build(
            listOf(film("tt1", "Sintel"), film("tmdb:133701", "Tears of Steel")),
            emptyList(),
            emptyList(),
            "en",
            now
        )

        assertEquals(setOf(500, 501), picks.map { it.tmdbId }.toSet())
        assertEquals("Sintel", picks.single { it.tmdbId == 500 }.reason)
    }

    @Test
    fun `a seed whose lookup or page fails is skipped`() = runTest {
        coEvery { service.ensureTmdbId("tt1", "movie") } returns null
        coEvery { service.ensureTmdbId("tt2", "movie") } returns "2"
        coEvery { service.ensureTmdbId("tt3", "movie") } returns "3"
        coEvery { api.getMovieRecommendations(2, KEY, "en", 1) } returns Response.error(401, "".toResponseBody(null))
        coEvery { api.getMovieRecommendations(3, KEY, "en", 1) } returns page(rec(900, "Elephants Dream"))
        val builder = ScoutPicksBuilder(api, service, TmdbApiKeySource { KEY })

        val picks = builder.build(listOf(film("tt1", "A"), film("tt2", "B"), film("tt3", "C")), emptyList(), emptyList(), "en", now)

        assertEquals(listOf(900), picks.map { it.tmdbId })
    }

    @Test
    fun `a thrown failure skips the seed but cancellation is not swallowed`() = runTest {
        coEvery { service.ensureTmdbId("tt1", "movie") } throws IOException("offline")
        coEvery { service.ensureTmdbId("tt2", "movie") } returns "2"
        coEvery { api.getMovieRecommendations(2, KEY, "en", 1) } returns page(rec(900, "Elephants Dream"))
        val builder = ScoutPicksBuilder(api, service, TmdbApiKeySource { KEY })

        assertEquals(listOf(900), builder.build(listOf(film("tt1", "A"), film("tt2", "B")), emptyList(), emptyList(), "en", now).map { it.tmdbId })

        coEvery { service.ensureTmdbId("tt1", "movie") } throws CancellationException("gone")
        try {
            builder.build(listOf(film("tt1", "A")), emptyList(), emptyList(), "en", now)
            fail("cancellation swallowed")
        } catch (expected: CancellationException) {
        }
    }

    @Test
    fun `a show seed asks for tv recommendations and two ids for one title ask once`() = runTest {
        coEvery { service.ensureTmdbId(any(), "tv") } returns "77"
        coEvery { api.getTvRecommendations(77, KEY, "en", 1) } returns page(rec(300, "Charge"))
        val builder = ScoutPicksBuilder(api, service, TmdbApiKeySource { KEY })
        val show = { id: String -> WatchedItem(contentId = id, contentType = "series", title = "Cosmos", season = 1, episode = 1, watchedAt = now - 1000) }

        val picks = builder.build(listOf(show("tt7"), show("tmdb:77")), emptyList(), emptyList(), "en", now)

        assertEquals(listOf("tv:300"), picks.map { it.key })
        coVerify(exactly = 1) { api.getTvRecommendations(77, KEY, "en", 1) }
        coVerify(exactly = 0) { api.getMovieRecommendations(any(), any(), any(), any()) }
    }

    @Test
    fun `a started title with a prefixed id is left out by id whatever its name`() = runTest {
        // Three finished titles, so the started one is no seed: only localTmdbId can exclude it.
        (1..3).forEach { n -> coEvery { service.ensureTmdbId("tt$n", "movie") } returns "$n" }
        coEvery { api.getMovieRecommendations(1, KEY, "en", 1) } returns page(rec(500, "Spring"), rec(501, "Coffee Run"))
        coEvery { api.getMovieRecommendations(2, KEY, "en", 1) } returns page()
        coEvery { api.getMovieRecommendations(3, KEY, "en", 1) } returns page()
        val started = WatchProgress(
            contentId = "movie:500", contentType = "movie", name = "Primavera", poster = null, backdrop = null, logo = null,
            videoId = "movie:500", season = null, episode = null, episodeTitle = null, position = 900, duration = 1000,
            lastWatched = now - 1000
        )
        val builder = ScoutPicksBuilder(api, service, TmdbApiKeySource { KEY })

        val picks = builder.build(listOf(film("tt1", "Sintel"), film("tt2", "B"), film("tt3", "C")), listOf(started), emptyList(), "en", now)

        assertEquals(listOf(501), picks.map { it.tmdbId })
        coVerify(exactly = 0) { service.ensureTmdbId("movie:500", any()) }
    }

    @Test
    fun `a seed is left out by its resolved id even when its own page fails`() = runTest {
        coEvery { service.ensureTmdbId("tt1", "movie") } returns "45745"
        coEvery { service.ensureTmdbId("tt2", "movie") } returns "2"
        coEvery { api.getMovieRecommendations(45745, KEY, "en", 1) } throws IOException("offline")
        // Another title for the same film: only the resolved-id key can match it.
        coEvery { api.getMovieRecommendations(2, KEY, "en", 1) } returns page(rec(45745, "Sintel: The Durian Open Movie"), rec(501, "Cosmos"))
        val builder = ScoutPicksBuilder(api, service, TmdbApiKeySource { KEY })

        val picks = builder.build(listOf(film("tt1", "Sintel"), film("tt2", "B")), emptyList(), emptyList(), "en", now)

        assertEquals(listOf(501), picks.map { it.tmdbId })
    }

    @Test
    fun `cancellation during a page fetch is not swallowed`() = runTest {
        coEvery { service.ensureTmdbId("tt1", "movie") } returns "1"
        coEvery { api.getMovieRecommendations(1, KEY, "en", 1) } throws CancellationException("gone")
        val builder = ScoutPicksBuilder(api, service, TmdbApiKeySource { KEY })

        try {
            builder.build(listOf(film("tt1", "A")), emptyList(), emptyList(), "en", now)
            fail("cancellation swallowed")
        } catch (expected: CancellationException) {
        }
    }

    @Test
    fun `a long history asks at most 8 lookups and 8 pages (D4)`() = runTest {
        coEvery { service.ensureTmdbId(any(), any()) } answers { firstArg<String>().removePrefix("tt") }
        coEvery { api.getMovieRecommendations(any(), KEY, "en", 1) } returns page(rec(900, "Elephants Dream"))
        val finished = (1..15).map { film("tt$it", "F$it") }
        val quit = (20..25).map {
            WatchProgress(
                contentId = "tt$it", contentType = "movie", name = "Q$it", poster = null, backdrop = null, logo = null,
                videoId = "tt$it", season = null, episode = null, episodeTitle = null, position = 50, duration = 1000,
                lastWatched = 0
            )
        }
        val builder = ScoutPicksBuilder(api, service, TmdbApiKeySource { KEY })

        builder.build(finished, quit, emptyList(), "en", now + 30L * 24 * 60 * 60 * 1000)

        coVerify(exactly = SCOUT_MAX_POSITIVE_SEEDS + SCOUT_MAX_NEGATIVE_SEEDS) { service.ensureTmdbId(any(), any()) }
        coVerify(exactly = SCOUT_MAX_POSITIVE_SEEDS + SCOUT_MAX_NEGATIVE_SEEDS) { api.getMovieRecommendations(any(), any(), any(), any()) }
    }

    @Test
    fun `a rated-only title seeds and a rating beats history of the same title under another id`() = runTest {
        // tt1 is finished; its Not for me sits under tmdb:1 (the detail page's id): the dislike must win.
        coEvery { service.ensureTmdbId("tt1", "movie") } returns "1"
        coEvery { service.ensureTmdbId("tmdb:1", "movie") } returns "1"
        coEvery { service.ensureTmdbId("tt5", "movie") } returns "5"
        coEvery { api.getMovieRecommendations(1, KEY, "en", 1) } returns page(rec(500, "Spring"))
        coEvery { api.getMovieRecommendations(5, KEY, "en", 1) } returns page(rec(500, "Spring"), rec(501, "Coffee Run"))
        val ratings = listOf(
            ScoutRatingEntry("tmdb:1", false, "Sintel", ScoutRating.NOT_FOR_ME, 1, tmdbId = 1),
            ScoutRatingEntry("tt5", false, "Big Buck Bunny", ScoutRating.LOVE, 2, imdbId = "tt5", tmdbId = 5)
        )
        val builder = ScoutPicksBuilder(api, service, TmdbApiKeySource { KEY })

        val picks = builder.build(listOf(film("tt1", "Sintel")), emptyList(), ratings, "en", now)

        // Spring: Love 6 - Not for me 3 = 3, below Coffee Run (5.85); had Finished (+1) won, it would lead with 7.
        assertEquals(listOf(501, 500), picks.map { it.tmdbId })
        assertTrue(picks.all { it.reason == "Big Buck Bunny" })
        coVerify(exactly = 1) { api.getMovieRecommendations(1, KEY, "en", 1) }
    }

    @Test
    fun `a Not for me cut by the negative cap still keeps its history twin from seeding`() = runTest {
        // Sintel: Not for me saved on tmdb:1 naming tt1 (the oldest of three), finished as tt1: the history row
        // is the rating, whatever the caps keep.
        coEvery { service.ensureTmdbId(any(), "movie") } answers { firstArg<String>().removePrefix("tmdb:").removePrefix("tt") }
        // Only Sintel's page has Spring, so Spring shows only if Sintel seeds positively.
        coEvery { api.getMovieRecommendations(any(), KEY, "en", 1) } returns page()
        coEvery { api.getMovieRecommendations(1, KEY, "en", 1) } returns page(rec(500, "Spring"))
        coEvery { api.getMovieRecommendations(4, KEY, "en", 1) } returns page(rec(501, "Coffee Run Remix"))
        val ratings = listOf(
            ScoutRatingEntry("tmdb:1", false, "Sintel", ScoutRating.NOT_FOR_ME, 1, imdbId = "tt1", tmdbId = 1),
            ScoutRatingEntry("tmdb:2", false, "Morevna", ScoutRating.NOT_FOR_ME, 2, tmdbId = 2),
            ScoutRatingEntry("tmdb:3", false, "Glass Half", ScoutRating.NOT_FOR_ME, 3, tmdbId = 3)
        )
        val builder = ScoutPicksBuilder(api, service, TmdbApiKeySource { KEY })

        val picks = builder.build(listOf(film("tt1", "Sintel"), film("tt4", "Coffee Run")), emptyList(), ratings, "en", now)

        // Had tt1 seeded positively, Spring would be picked "Because you watched Sintel".
        assertEquals(listOf(501 to "Coffee Run"), picks.map { it.tmdbId to it.reason })
    }

    @Test
    fun `a history tt id of a title rated without an IMDb id goes once it resolves to the stored TMDB id`() = runTest {
        coEvery { service.ensureTmdbId(any(), "movie") } answers { firstArg<String>().removePrefix("tmdb:").removePrefix("tt") }
        coEvery { api.getMovieRecommendations(any(), KEY, "en", 1) } returns page(rec(500, "Spring"))
        val ratings = listOf(
            ScoutRatingEntry("tmdb:1", false, "Sintel (2010)", ScoutRating.NOT_FOR_ME, 1, tmdbId = 1),
            ScoutRatingEntry("tmdb:2", false, "Morevna", ScoutRating.NOT_FOR_ME, 2, tmdbId = 2),
            ScoutRatingEntry("tmdb:3", false, "Glass Half", ScoutRating.NOT_FOR_ME, 3, tmdbId = 3)
        )
        val builder = ScoutPicksBuilder(api, service, TmdbApiKeySource { KEY })

        val picks = builder.build(listOf(film("tt1", "Sintel"), film("tt4", "Coffee Run")), emptyList(), ratings, "en", now)

        assertTrue(picks.none { it.reason == "Sintel" })
        coVerify(exactly = 0) { api.getMovieRecommendations(1, any(), any(), any()) }
    }

    @Test
    fun `a rating seeds by the TMDB id it stored, with no build-time lookup, whatever its page id`() = runTest {
        coEvery { api.getTvRecommendations(7, KEY, "en", 1) } returns page(rec(300, "Charge"))
        val ratings = listOf(ScoutRatingEntry("kitsu:9", true, "Agent 327", ScoutRating.LOVE, 1, imdbId = "tt9", tmdbId = 7))
        val builder = ScoutPicksBuilder(api, service, TmdbApiKeySource { KEY })

        val picks = builder.build(emptyList(), emptyList(), ratings, "en", now)

        assertEquals(listOf("tv:300" to "Agent 327"), picks.map { it.key to it.reason })
        coVerify(exactly = 0) { service.ensureTmdbId(any(), any()) }
    }

    @Test
    fun `a Not for me from the tt page beats the title's tmdb history id`() = runTest {
        // Rated on the tt1 page (TMDB id 1 stored, D7); the history knows the film as tmdb:1.
        coEvery { service.ensureTmdbId("tt1", "movie") } returns "1"
        coEvery { service.ensureTmdbId("tmdb:1", "movie") } returns "1"
        coEvery { service.ensureTmdbId("tt4", "movie") } returns "4"
        coEvery { api.getMovieRecommendations(1, KEY, "en", 1) } returns page(rec(500, "Spring"))
        coEvery { api.getMovieRecommendations(4, KEY, "en", 1) } returns page(rec(501, "Coffee Run Remix"))
        val ratings = listOf(ScoutRatingEntry("tt1", false, "Sintel", ScoutRating.NOT_FOR_ME, 1, imdbId = "tt1", tmdbId = 1))
        val builder = ScoutPicksBuilder(api, service, TmdbApiKeySource { KEY })

        val picks = builder.build(listOf(film("tmdb:1", "Sintel"), film("tt4", "Coffee Run")), emptyList(), ratings, "en", now)

        assertEquals(listOf(501 to "Coffee Run"), picks.map { it.tmdbId to it.reason })
    }

    @Test
    fun `a watched title rated on its own page seeds by the stored TMDB id with no lookup`() = runTest {
        coEvery { api.getTvRecommendations(7, KEY, "en", 1) } returns page(rec(300, "Charge"))
        val watched = WatchedItem(contentId = "kitsu:9", contentType = "series", title = "Agent 327", season = 1, episode = 1, watchedAt = now - 1000)
        val ratings = listOf(ScoutRatingEntry("kitsu:9", true, "Agent 327", ScoutRating.LOVE, 1, imdbId = "tt9", tmdbId = 7))
        val builder = ScoutPicksBuilder(api, service, TmdbApiKeySource { KEY })

        val picks = builder.build(listOf(watched), emptyList(), ratings, "en", now)

        assertEquals(listOf("tv:300" to "Agent 327"), picks.map { it.key to it.reason })
        coVerify(exactly = 0) { service.ensureTmdbId(any(), any()) }
    }

    @Test
    fun `a rated title is never picked`() = runTest {
        coEvery { service.ensureTmdbId("tt1", "movie") } returns "1"
        coEvery { api.getMovieRecommendations(1, KEY, "en", 1) } returns page(rec(500, "Spring"), rec(501, "Coffee Run"))
        // Untitled, so it cannot seed: only the ratings' own exclusions can keep 500 out.
        val ratings = listOf(ScoutRatingEntry("tmdb:500", false, "", ScoutRating.LIKE, 1, tmdbId = 500))
        val builder = ScoutPicksBuilder(api, service, TmdbApiKeySource { KEY })

        val picks = builder.build(listOf(film("tt1", "Sintel")), emptyList(), ratings, "en", now)

        assertEquals(listOf(501), picks.map { it.tmdbId })
    }

    private fun film(id: String, title: String) =
        WatchedItem(contentId = id, contentType = "movie", title = title, watchedAt = now - 1000)

    private fun rec(id: Int, title: String) =
        TmdbRecommendationResult(id = id, title = title, posterPath = "/p.jpg", voteAverage = 7.0, voteCount = 500)

    private fun page(vararg results: TmdbRecommendationResult): Response<TmdbRecommendationsResponse> =
        Response.success(TmdbRecommendationsResponse(results.toList()))

    private companion object {
        const val KEY = "test-key"
    }
}
