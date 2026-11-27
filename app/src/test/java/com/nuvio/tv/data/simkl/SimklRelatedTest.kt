package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.domain.model.ContentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 106 S4a: More like this from Simkl (redirect, title record, cards) and the source setting's effective value. */
class SimklRelatedTest {

    private val engine = FakeSimklEngine()
    private val clock = FakeClock()

    private fun related(config: SimklApiConfiguration = TEST_CONFIG) =
        SimklRelated(SimklClient(engine, config, monotonicMs = { clock.now }, sleep = clock.sleep, jitterMs = { 0L }), config, Dispatchers.Unconfined)

    private fun redirectTo(location: String) = engine.reply(301, "", mapOf("Location" to location))

    private fun item(simkl: Long, title: String, type: String = "movie", extra: String = "", ids: String = """"imdb":"tt$simkl"""") =
        """{"title":"$title","year":2010,"poster":"13/1346","fanart":"12/1256","type":"$type"$extra,"ids":{"simkl":$simkl,$ids}}"""

    private val inception = """{"title":"Inception","ids":{"simkl":472214,"imdb":"tt1375666"},
        "users_recommendations":[${item(250822, "Interstellar")},${item(53282, "The Dark Knight")}],
        "similar":[${item(53282, "The Dark Knight")},${item(472214, "Inception")},${item(64226, "Ballistic")}]}"""

    @Test
    fun `members' recommendations come first, then similar titles, deduped and without the title itself`() = runTest {
        redirectTo("https://simkl.com/movies/472214/inception")
        engine.reply(200, inception)

        val cards = related().related("tt1375666", "movie", SimklAnimeSeasons.PER_SHOW)

        assertEquals(listOf("tt250822", "tt53282", "tt64226"), cards.map { it.id })
        assertEquals(listOf("Interstellar", "The Dark Knight", "Ballistic"), cards.map { it.name })
        val (redirect, record) = engine.calls
        assertEquals("/redirect", redirect.path)
        assertEquals("simkl", redirect.query("to"))
        assertEquals("tt1375666", redirect.query("imdb"))
        assertNull(redirect.query("type"))
        assertEquals("/movies/472214", record.path)
        assertTrue(engine.calls.all { it.headers["Authorization"] == null })
    }

    @Test
    fun `a card is landscape from the fanart, with Simkl's year and portrait poster`() = runTest {
        redirectTo("/movies/472214/inception")
        engine.reply(200, """{"users_recommendations":[${item(250822, "Interstellar")}],
            "similar":[${item(9, "No fanart").replace(""""fanart":"12/1256",""", "")}]}""")

        val (interstellar, noFanart) = related().related("tt1375666", "movie", SimklAnimeSeasons.PER_SHOW)

        assertEquals(ContentType.MOVIE, interstellar.type)
        assertEquals("2010", interstellar.releaseInfo)
        assertEquals("https://wsrv.nl/?url=https://simkl.in/fanart/12/1256_w.webp&q=90", interstellar.poster)
        assertEquals(interstellar.poster, interstellar.landscapePoster)
        assertEquals(interstellar.poster, interstellar.background)
        assertEquals("https://wsrv.nl/?url=https://simkl.in/posters/13/1346_m.webp&q=90", interstellar.rawPosterUrl)
        assertEquals("tt250822", interstellar.imdbId)
        assertEquals("https://wsrv.nl/?url=https://simkl.in/posters/13/1346_w.webp&q=90", noFanart.poster)
    }

    @Test
    fun `anime are named by the profile's mode, English title first, and an anime film is a film`() = runTest {
        redirectTo("https://simkl.com/anime/831411/kimetsu-no-yaiba")
        val jjk = item(1211265, "Jujutsu Kaisen", "anime", ""","en_title":null,"anime_type":"tv"""",
            """"imdb":"tt12343534","tmdb":"95479","kitsu":"42765","mal":"40748"""")
        val train = item(1738291, "Mugen Ressha Hen", "anime", ""","en_title":"Demon Slayer: Mugen Train","anime_type":"movie"""",
            """"imdb":"tt11032374","kitsu":"45249"""")
        val record = """{"users_recommendations":[$jjk],"similar":[$train]}"""
        engine.reply(200, record)
        redirectTo("https://simkl.com/anime/831411/kimetsu-no-yaiba")
        engine.reply(200, record)

        val service = related()
        val show = service.related("kitsu:41370", "series", SimklAnimeSeasons.PER_SHOW)
        val season = service.related("kitsu:41370", "series", SimklAnimeSeasons.PER_SEASON)

        assertEquals(listOf("tt12343534", "tt11032374"), show.map { it.id })
        assertEquals(listOf("kitsu:42765", "kitsu:45249"), season.map { it.id })
        assertEquals(listOf("Jujutsu Kaisen", "Demon Slayer: Mugen Train"), season.map { it.name })
        assertEquals(listOf(ContentType.SERIES, ContentType.MOVIE), season.map { it.type })
        assertEquals("41370", engine.calls.first().query("kitsu"))
        assertNull(engine.calls.first().query("type"))
        assertEquals("/anime/831411", engine.calls[1].path)
    }

    @Test
    fun `a Location on another host only names the record, which is asked of Simkl's API`() = runTest {
        redirectTo("https://evil.example/movies/472214/inception")
        engine.reply(200, inception)
        assertEquals(3, related().related("tt1375666", "movie", SimklAnimeSeasons.PER_SHOW).size)
        assertEquals("https://api.simkl.com/movies/472214", engine.calls[1].url.substringBefore('?'))
    }

    @Test
    fun `a film and a show sharing a TMDB number both show, an unusable item hides no duplicate, the title is left out`() = runTest {
        redirectTo("https://simkl.com/tv/17465/x")
        engine.reply(200, """{"users_recommendations":[
            ${item(1, "Film", "movie", ids = """"tmdb":"603"""")},${item(2, "Show", "tv", ids = """"tmdb":"603"""")},
            ${item(3, "", "tv")},${item(3, "Named later", "tv")},${item(4, "Itself", "tv", ids = """"tmdb":"1399"""")}]}""")
        val cards = related().related("tmdb:1399", "series", SimklAnimeSeasons.PER_SHOW)
        assertEquals(listOf("Film", "Show", "Named later"), cards.map { it.name })
        assertEquals(listOf(ContentType.MOVIE, ContentType.SERIES, ContentType.SERIES), cards.map { it.type })
    }

    @Test
    fun `the redirect asks by the first id in order, with a type only for TMDB and TVDB`() {
        assertEquals(mapOf("to" to "simkl", "tmdb" to "1399", "type" to "tv"), simklRedirectQuery(TrackingExternalIds(tmdb = 1399), isMovie = false))
        assertEquals(mapOf("to" to "simkl", "tvdb" to "113", "type" to "movie"), simklRedirectQuery(TrackingExternalIds(tvdb = "113"), isMovie = true))
        assertEquals(mapOf("to" to "simkl", "imdb" to "tt1"), simklRedirectQuery(TrackingExternalIds(imdb = "tt1", tmdb = 5), isMovie = true))
        assertEquals(mapOf("to" to "simkl", "mal" to "38000"), simklRedirectQuery(TrackingExternalIds(mal = 38000, anidb = 1), isMovie = false))
        assertEquals(mapOf("to" to "simkl", "simkl" to "17465"), simklRedirectQuery(TrackingExternalIds(simkl = 17465), isMovie = false))
        assertNull(simklRedirectQuery(TrackingExternalIds(mdblist = "x"), isMovie = false))
    }

    @Test
    fun `only a catalog page in the redirect names a record`() {
        val base = TEST_CONFIG.baseUrl
        assertEquals("/tv/17465" to 17465L, simklCatalogPath(base, "https://simkl.com/tv/17465/game-of-thrones"))
        assertEquals("/anime/831411" to 831411L, simklCatalogPath(base, "/anime/831411"))
        assertNull(simklCatalogPath(base, "https://simkl.com/search/?q=nothing"))
        assertNull(simklCatalogPath(base, "https://simkl.com/movies/inception"))
        assertNull(simklCatalogPath(base, "https://simkl.com/movies/0/x"))
        assertNull(simklCatalogPath(base, null))
    }

    @Test
    fun `the page address is the redirect's catalog page, always on simkl dot com`() = runTest {
        val base = TEST_CONFIG.baseUrl
        assertEquals("simkl.com/tv/1088153/sintel", simklPageAddress(base, "https://simkl.com/tv/1088153/sintel"))
        assertEquals("simkl.com/movies/53736", simklPageAddress(base, "/movies/53736/"))
        assertEquals("simkl.com/anime/9/x", simklPageAddress(base, "https://evil.example/anime/9/x/extra"))
        assertNull(simklPageAddress(base, "https://simkl.com/search/?q=tt1"))
        assertNull(simklPageAddress(base, "https://simkl.com/movies/inception"))
        assertNull(simklPageAddress(base, null))

        redirectTo("https://simkl.com/movies/53736/tears-of-steel")
        val service = related()
        assertEquals("simkl.com/movies/53736/tears-of-steel", service.pageAddress("tt2285752", "movie"))
        assertEquals("/redirect", engine.calls.single().path)
        assertNull(engine.calls.single().headers["Authorization"])
        // Not a catalog page: no address. No id Simkl can look up: nothing sent.
        redirectTo("https://simkl.com/search/?q=tt2285752")
        assertNull(service.pageAddress("tt2285752", "movie"))
        assertNull(service.pageAddress("custom:abc", "movie"))
        assertEquals(2, engine.calls.size)
    }

    @Test
    fun `a title Simkl does not know, or one Nuvio has no id for, sends nothing more`() = runTest {
        redirectTo("https://simkl.com/search/?q=tt0000001")
        val service = related()
        assertEquals(emptyList<Any>(), service.related("tt0000001", "movie", SimklAnimeSeasons.PER_SHOW))
        assertEquals(emptyList<Any>(), service.related("custom:abc", "movie", SimklAnimeSeasons.PER_SHOW))
        assertEquals(1, engine.calls.size)
    }

    @Test
    fun `answers are kept, failures are not`() = runTest {
        val service = related()
        redirectTo("https://simkl.com/movies/472214/inception")
        engine.reply(404, """{"error":"not_found"}""")
        assertEquals(emptyList<Any>(), service.related("tt1375666", "movie", SimklAnimeSeasons.PER_SHOW))

        redirectTo("https://simkl.com/movies/472214/inception")
        engine.reply(200, inception)
        assertEquals(3, service.related("tt1375666", "movie", SimklAnimeSeasons.PER_SHOW).size)
        assertEquals(3, service.related("tt1375666", "movie", SimklAnimeSeasons.PER_SHOW).size)
        assertEquals(4, engine.calls.size)
    }

    @Test
    fun `an unreadable item is skipped and an unreadable record is empty`() = runTest {
        redirectTo("https://simkl.com/movies/472214/inception")
        engine.reply(200, """{"users_recommendations":[{"title":"Bad","year":"twenty","ids":{"simkl":1}},
            ${item(2, "")},${item(3, "Good")},"text"],"similar":null}""")
        assertEquals(listOf("tt3"), related().related("tt1375666", "movie", SimklAnimeSeasons.PER_SHOW).map { it.id })

        redirectTo("https://simkl.com/movies/472214/inception")
        engine.reply(200, "[]")
        assertEquals(emptyList<Any>(), related().related("tt1375666", "movie", SimklAnimeSeasons.PER_SHOW))
    }

    @Test
    fun `at most the asked number of cards`() = runTest {
        redirectTo("https://simkl.com/movies/1/x")
        engine.reply(200, """{"similar":[${(10L..40L).joinToString(",") { item(it, "T$it") }}]}""")
        assertEquals(12, related().related("tt1", "movie", SimklAnimeSeasons.PER_SHOW).size)
    }

    @Test
    fun `with no app id set up nothing is sent`() = runTest {
        val cards = related(TEST_CONFIG.copy(clientId = { null })).related("tt1375666", "movie", SimklAnimeSeasons.PER_SHOW)
        assertEquals(emptyList<Any>(), cards)
        assertEquals(0, engine.calls.size)
    }

    @Test
    fun `a cancelled call is not swallowed`() = runTest {
        val cancelling = SimklHttpEngine { _, _, _, _ -> throw CancellationException("left the page") }
        val service = SimklRelated(SimklClient(cancelling, TEST_CONFIG), TEST_CONFIG, Dispatchers.Unconfined)
        assertFailsSuspend<CancellationException> { service.related("tt1", "movie", SimklAnimeSeasons.PER_SHOW) }
    }

    @Test
    fun `Simkl is the effective source only while it is connected, and unknown values read as TMDB`() {
        assertEquals(RelatedTitlesSource.SIMKL, RelatedTitlesSource.SIMKL.effective(setOf(TrackingProviderId.SIMKL)))
        assertEquals(RelatedTitlesSource.TMDB, RelatedTitlesSource.SIMKL.effective(setOf(TrackingProviderId.MDBLIST)))
        assertEquals(RelatedTitlesSource.TMDB, RelatedTitlesSource.TMDB.effective(setOf(TrackingProviderId.SIMKL)))
        assertEquals(RelatedTitlesSource.SIMKL, RelatedTitlesSource.fromStorage(" SIMKL "))
        assertEquals(RelatedTitlesSource.TMDB, RelatedTitlesSource.fromStorage("trakt"))
        assertEquals(RelatedTitlesSource.TMDB, RelatedTitlesSource.fromStorage(null))
    }
}
