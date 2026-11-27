package com.nuvio.tv.core.contentfilter

import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbFindResponse
import com.nuvio.tv.data.remote.api.TmdbFindResult
import com.nuvio.tv.data.remote.api.TmdbTagDetailsResponse
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response
import java.io.IOException

/** Spec 117 S3: TMDB tag responses and their mapping to lookup outcomes. */
class TitleTagSourceTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `movie keywords come under keywords and series keywords under results`() {
        val movie = json.decodeFromString(
            TmdbTagDetailsResponse.serializer(),
            """{"id":1,"adult":true,"original_language":"en","genres":[{"id":27,"name":"Horror"}],
               "keywords":{"keywords":[{"id":9826,"name":"murder"}]},"title":"x"}"""
        )
        val tv = json.decodeFromString(
            TmdbTagDetailsResponse.serializer(),
            """{"id":2,"keywords":{"results":[{"id":210024,"name":"anime"}]}}"""
        )
        assertEquals(listOf(9826), movie.keywords!!.all.map { it.id })
        assertEquals(true, movie.adult)
        assertEquals(listOf(210024), tv.keywords!!.all.map { it.id })
    }

    @Test
    fun `status codes map to lookup outcomes`() {
        assertEquals(Fetch.Ok("x"), classify(Response.success("x"), allowEmptyBody = false))
        assertEquals(Fetch.Unauthorized, classify(error<String>(401), allowEmptyBody = false))
        assertEquals(Fetch.NotFound, classify(error<String>(404), allowEmptyBody = false))
        assertEquals(Fetch.Failed(io = false), classify(error<String>(500), allowEmptyBody = false))
        assertEquals(Fetch.RateLimited(7_000), classify(error<String>(429, "Retry-After" to "7"), allowEmptyBody = false))
        assertEquals(Fetch.RateLimited(DEFAULT_RETRY_AFTER_MS), classify(error<String>(429), allowEmptyBody = false))
    }

    @Test
    fun `a tag response maps to genres, named keywords and the adult flag`() = runTest {
        val api = mockk<TmdbApi>()
        val body = json.decodeFromString(
            TmdbTagDetailsResponse.serializer(),
            """{"id":1,"adult":true,"original_language":"ja","genres":[{"id":16,"name":"Animation"}],
               "keywords":{"results":[{"id":210024,"name":"anime"},{"id":5}]}}"""
        )
        coEvery { api.getTvTags(1, "k", any()) } returns Response.success(body)
        assertEquals(
            Fetch.Ok(FetchedTags(listOf(16), listOf(210024 to "anime"), "ja", adult = true)),
            TmdbTitleTagSource(api).tags(TitleKind.TV, 1, "k")
        )
    }

    @Test
    fun `an empty 200 is ok only where allowed (the key probe)`() {
        assertEquals(Fetch.Ok(Unit), classify(Response.success<Unit>(null), allowEmptyBody = true))
        assertEquals(Fetch.Failed(io = false), classify(Response.success<Unit>(null), allowEmptyBody = false))
    }

    @Test
    fun `retry-after is clamped and defaults when unreadable`() {
        assertEquals(1_000L, retryAfterMs("0"))
        assertEquals(120_000L, retryAfterMs("3600"))
        assertEquals(DEFAULT_RETRY_AFTER_MS, retryAfterMs("Wed, 21 Oct 2015 07:28:00 GMT"))
    }

    @Test
    fun `find returns the match of the asked kind only`() = runTest {
        val api = mockk<TmdbApi>()
        coEvery { api.findByExternalId("tt1", "k", "imdb_id") } returns
            Response.success(TmdbFindResponse(tvResults = listOf(TmdbFindResult(id = 55))))
        val source = TmdbTitleTagSource(api)
        assertEquals(Fetch.Ok(55), source.find("tt1", TitleKind.TV, "k"))
        assertEquals(Fetch.NotFound, source.find("tt1", TitleKind.MOVIE, "k"))
    }

    @Test
    fun `an IO error is a breaker-counted failure, a bad body is not`() = runTest {
        val api = mockk<TmdbApi>()
        coEvery { api.getMovieTags(1, "k", any()) } throws IOException("timeout")
        coEvery { api.getTvTags(1, "k", any()) } throws IllegalArgumentException("bad body")
        val source = TmdbTitleTagSource(api)
        assertEquals(Fetch.Failed(io = true), source.tags(TitleKind.MOVIE, 1, "k"))
        assertEquals(Fetch.Failed(io = false), source.tags(TitleKind.TV, 1, "k"))
    }

    @Test
    fun `title ids resolve to a TMDB target only for tmdb and imdb ids`() {
        assertEquals(TmdbTarget.ById(603), TitleRef(TitleKind.MOVIE, "tmdb:603").target())
        assertEquals(TmdbTarget.ByImdb("tt0944947"), TitleRef(TitleKind.TV, "tt0944947:1:2").target())
        assertEquals(TmdbTarget.Unresolvable, TitleRef(TitleKind.TV, "kitsu:1").target())
        assertEquals(TmdbTarget.Unresolvable, TitleRef(TitleKind.MOVIE, "12345").target())
        assertEquals(TmdbTarget.Unresolvable, TitleRef(TitleKind.MOVIE, "tmdb:abc").target())
        assertEquals(null, TitleRef.of("channel", "tt1"))
        assertEquals(TitleKind.TV, TitleRef.of("series", "tt1")?.kind)
        assertEquals(TitleRef(TitleKind.TV, "tt0944947"), TitleRef.of("series", "tt0944947:1:2"))
        assertEquals(TitleRef(TitleKind.MOVIE, "tmdb:603"), TitleRef.of("movie", "tmdb:603:x"))
        assertEquals(TitleRef(TitleKind.MOVIE, "kitsu:1:2"), TitleRef.of("movie", "kitsu:1:2"))
    }

    private fun <T> error(code: Int, vararg headers: Pair<String, String>): Response<T> {
        val raw = okhttp3.Response.Builder()
            .request(okhttp3.Request.Builder().url("https://api.themoviedb.org/3/x").build())
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(code)
            .message("x")
            .headers(Headers.headersOf(*headers.flatMap { listOf(it.first, it.second) }.toTypedArray()))
            .build()
        return Response.error("".toResponseBody(null), raw)
    }
}
