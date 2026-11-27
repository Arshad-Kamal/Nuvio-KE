package com.nuvio.tv.data.mdblist

import com.nuvio.tv.core.tracking.TrackingAccount
import com.nuvio.tv.core.tracking.TrackingCatalogReference
import com.nuvio.tv.core.tracking.TrackingEpisode
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Spec 105 S2b: scrobbles and watched marks sent to MDBList. */
class MdbListWritesTest {
    @get:Rule val folder = TemporaryFolder()
    private val engine = FakeMdbListEngine()
    private val clock = FakeClock()
    private val accounts = accountStore()
    private val quota = MdbListQuota { clock.now }
    private val session = MdbListSession(mdbListClient(engine, clock), accounts, quota)
    private val profile = MutableStateFlow(1)
    private val scrobbler = MdbListScrobbler(session, TEST_CONFIG, profile, IDENTITY_NUMBERING)
    private val writer = MdbListHistoryWriter(session, IDENTITY_NUMBERING) { 1_763_000_000_000L }

    private suspend fun connected(profileId: Int = 1, key: String = "key-one-12345") =
        accounts.save(profileId, TrackingProviderId.MDBLIST, TrackingAccount(accessToken = key, accountName = "a"))

    private val film = TrackingMediaReference(TrackingMediaKind.MOVIE, "Matrix", ids = TrackingExternalIds(imdb = "tt0133093", tmdb = 603))

    private fun episode(season: Int? = 1, number: Int = 2, episodeTmdb: Long? = null, kind: TrackingMediaKind = TrackingMediaKind.SHOW) =
        TrackingMediaReference(kind, "Breaking Bad", ids = TrackingExternalIds(imdb = "tt0903747", tvdb = "81189"),
            episode = TrackingEpisode(season = season, number = number, tmdbId = episodeTmdb))

    private fun body(index: Int = 0): JsonObject = Json.parseToJsonElement(engine.calls[index].body!!).jsonObject

    @Test
    fun `a film's start goes with its ids, progress and the app name on the active profile's key`() = runTest {
        connected(2, "key-two-12345")
        profile.value = 2
        engine.reply(200, """{"id":5,"action":"start"}""")
        scrobbler.scrobble(TrackingScrobbleAction.START, TrackingScrobbleEvent(film, 12.5))
        val call = engine.calls.single()
        assertEquals("/scrobble/start", call.path)
        assertEquals(MdbListHttpMethod.POST, call.method)
        assertEquals("key-two-12345", call.query("apikey"))
        val ids = body().getValue("movie").jsonObject.getValue("ids").jsonObject
        assertTrue(ids.getValue("imdb").jsonPrimitive.isString)
        assertFalse(ids.getValue("tmdb").jsonPrimitive.isString)
        assertEquals(12.5, body().getValue("progress").jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(MDBLIST_APP_LABEL, body().getValue("app_name").jsonPrimitive.content)
        assertEquals(TEST_CONFIG.appVersion, body().getValue("app_version").jsonPrimitive.content)
    }

    @Test
    fun `an episode's stop names the show with flat numbers and clamps the progress`() = runTest {
        connected()
        engine.reply(200, """{"action":"scrobble"}""")
        scrobbler.scrobble(TrackingScrobbleAction.STOP, TrackingScrobbleEvent(episode(), 104.0))
        assertEquals("/scrobble/stop", engine.calls.single().path)
        val show = body().getValue("show").jsonObject
        assertEquals(1, show.getValue("season").jsonPrimitive.content.toInt())
        assertEquals(2, show.getValue("episode").jsonPrimitive.content.toInt())
        assertEquals(81189L, show.getValue("ids").jsonObject.getValue("tvdb").jsonPrimitive.content.toLong())
        assertEquals(100.0, body().getValue("progress").jsonPrimitive.content.toDouble(), 0.0)
    }

    @Test
    fun `nothing MDBList could place is sent`() = runTest {
        connected()
        val noIds = TrackingMediaReference(TrackingMediaKind.MOVIE, "Home video", ids = TrackingExternalIds(kitsu = 7))
        val wholeSeries = TrackingMediaReference(TrackingMediaKind.SHOW, "Show", ids = TrackingExternalIds(imdb = "tt1"))
        listOf(
            TrackingScrobbleEvent(noIds, 10.0),
            TrackingScrobbleEvent(wholeSeries, 10.0),
            TrackingScrobbleEvent(episode(season = null), 10.0),
            TrackingScrobbleEvent(film, Double.NaN)
        ).forEach { scrobbler.scrobble(TrackingScrobbleAction.PAUSE, it) }
        assertTrue(engine.calls.isEmpty())
    }

    @Test
    fun `a spent day sends no scrobble`() = runTest {
        connected()
        quota.recordFailure(1, MdbListApiException(429, dailyLimit = true))
        failsWith<MdbListQuotaExceededException> { scrobbler.scrobble(TrackingScrobbleAction.START, TrackingScrobbleEvent(film, 1.0)) }
        assertTrue(engine.calls.isEmpty())
    }

    @Test
    fun `marks go dated, episodes by TMDB episode id when known, else under their show`() = runTest {
        connected()
        engine.reply(200, """{"updated":{"movies":1,"episodes":2},"not_found":{"movies":[],"episodes":[{"ids":{"tmdb":62085}}]}}""")
        val result = writer.addToHistory(
            1,
            listOf(
                TrackingHistoryItem(film, watchedAtEpochMs = 1_700_000_000_000L),
                TrackingHistoryItem(episode()),
                TrackingHistoryItem(episode(number = 3, episodeTmdb = 62085L)),
                TrackingHistoryItem(TrackingMediaReference(TrackingMediaKind.MOVIE, "x", ids = TrackingExternalIds(kitsu = 1)))
            )
        )
        assertEquals("/sync/watched", engine.calls.single().path)
        val movie = body().getValue("movies").jsonArray.single().jsonObject
        assertEquals("2023-11-14T22:13:20Z", movie.getValue("watched_at").jsonPrimitive.content)
        val show = body().getValue("shows").jsonArray.single().jsonObject
        val season = show.getValue("seasons").jsonArray.single().jsonObject
        assertEquals(1, season.getValue("number").jsonPrimitive.content.toInt())
        val nested = season.getValue("episodes").jsonArray.single().jsonObject
        assertEquals(2, nested.getValue("number").jsonPrimitive.content.toInt())
        assertEquals("2025-11-13T02:13:20Z", nested.getValue("watched_at").jsonPrimitive.content)
        val byId = body().getValue("episodes").jsonArray.single().jsonObject
        assertEquals(62085L, byId.getValue("ids").jsonObject.getValue("tmdb").jsonPrimitive.content.toLong())
        assertEquals("2025-11-13T02:13:20Z", byId.getValue("watched_at").jsonPrimitive.content)
        assertEquals(4, result.attemptedCount)
        assertEquals(2, result.notFoundCount) // the kitsu-only film and the episode MDBList could not find
    }

    @Test
    fun `an unmark carries no date and counts removal errors as not found`() = runTest {
        connected()
        engine.reply(200, """{"deleted":{"movies":1},"not_found":{"movies":[]},"errors":[{"ids":{"tmdb":1}}]}""")
        val result = writer.removeFromHistory(1, listOf(film, episode(episodeTmdb = 1L)))
        assertEquals("/sync/watched/remove", engine.calls.single().path)
        assertNull(body().getValue("movies").jsonArray.single().jsonObject["watched_at"])
        assertEquals(1, result.notFoundCount)
    }

    @Test
    fun `many marks go in batches of a hundred, and a failed batch throws after the earlier ones were sent`() = runTest {
        connected()
        val items = (1..150).map { TrackingHistoryItem(episode(number = it)) }
        engine.reply(200, """{"updated":{"episodes":100}}""")
        engine.reply(200, """{"updated":{"episodes":50}}""")
        assertEquals(0, writer.addToHistory(1, items).notFoundCount)
        assertEquals(2, engine.calls.size)
        assertEquals(100, body(0).getValue("shows").jsonArray.size)
        engine.calls.clear()
        engine.reply(200, """{"updated":{"episodes":100}}""")
        engine.reply(503, """{"error":"down"}""")
        assertEquals(503, failsWith<MdbListApiException> { writer.addToHistory(1, items) }.status)
        assertEquals(2, engine.calls.size) // the 503 write is not resent, though a read would be
    }

    @Test
    fun `the writer uses the profile it is given, not the active one`() = runTest {
        connected(1, "key-one-12345")
        connected(2, "key-two-12345")
        engine.reply(200, """{"updated":{"movies":1}}""")
        writer.addToHistory(2, listOf(TrackingHistoryItem(film)))
        assertEquals("key-two-12345", engine.calls.single().query("apikey"))
    }

    @Test
    fun `not found counts read lists or numbers, and an unreadable reply counts nothing`() = runTest {
        connected()
        engine.reply(200, """{"not_found":{"movies":2,"shows":[]}}""")
        assertEquals(2, writer.addToHistory(1, listOf(TrackingHistoryItem(film), TrackingHistoryItem(episode()))).notFoundCount)
        engine.reply(200, "not json")
        assertEquals(0, writer.addToHistory(1, listOf(TrackingHistoryItem(film))).notFoundCount)
    }

    @Test
    fun `an episode with its own TMDB id goes by it even without a season`() = runTest {
        connected()
        engine.reply(200, "{}")
        writer.addToHistory(1, listOf(TrackingHistoryItem(episode(season = null, number = 12, episodeTmdb = 999L, kind = TrackingMediaKind.ANIME))))
        assertEquals(999L, body().getValue("episodes").jsonArray.single().jsonObject.getValue("ids").jsonObject.getValue("tmdb").jsonPrimitive.content.toLong())
    }

    @Test
    fun `targets keep season 0 specials and drop what MDBList cannot match`() {
        assertEquals(MdbListTarget.Episode::class, episode(season = 0, number = 1).toMdbListTarget()!!::class)
        assertNull(episode(number = 0).toMdbListTarget())
        val notImdb = TrackingMediaReference(TrackingMediaKind.MOVIE, "x", ids = TrackingExternalIds(imdb = "nm123", tmdb = 0))
        assertNull(notImdb.toMdbListTarget())
    }

    @Test
    fun `leaving while paused sends a stop so a seek made in pause is saved`() {
        assertTrue(scrobbler.stopsWhenLeftPaused)
    }

    private fun TestScope.numbering() =
        MdbListEpisodeNumbering(session, tvdbMapStore(File(folder.root, "tvdb_maps.json"), backgroundScope), backgroundScope) { clock.now }

    /** A Detective Conan episode as Cinemeta numbers it (TVDB aired order), under its `tt` id. */
    private fun conan(season: Int, number: Int, videoId: String = "$CONAN_IMDB:$season:$number") = TrackingMediaReference(
        TrackingMediaKind.SHOW, "Detective Conan", ids = TrackingExternalIds(imdb = CONAN_IMDB, tvdb = "72454"),
        episode = TrackingEpisode(season, number), catalog = TrackingCatalogReference(CONAN_IMDB, "series", videoId)
    )

    private fun conanLookups() {
        engine.replyTo(MdbListHttpMethod.GET, "/imdb/show/$CONAN_IMDB/", 200, tvdbMapFixture("imdb-show-tt0131179.json"))
        engine.replyTo(MdbListHttpMethod.GET, "/shows/$CONAN_TMDB/tvdb-map/", 200, tvdbMapFixture("tvdb-map-30983.json"))
    }

    @Test
    fun `a tt episode plays in MDBList's TMDb numbering under the show's TMDb id`() = runTest(UnconfinedTestDispatcher()) {
        connected()
        conanLookups()
        engine.reply(200, """{"action":"start"}""")
        MdbListScrobbler(session, TEST_CONFIG, profile, numbering()::toMdbList) { clock.now }
            .scrobble(TrackingScrobbleAction.START, TrackingScrobbleEvent(conan(34, 35), 3.0))
        assertEquals(listOf("/imdb/show/$CONAN_IMDB/", "/shows/$CONAN_TMDB/tvdb-map/", "/scrobble/start"), engine.calls.map { it.path })
        val show = body(2).getValue("show").jsonObject
        assertEquals(1 to 1221, show.getValue("season").jsonPrimitive.content.toInt() to show.getValue("episode").jsonPrimitive.content.toInt())
        val ids = show.getValue("ids").jsonObject
        assertEquals(CONAN_TMDB, ids.getValue("tmdb").jsonPrimitive.content.toLong())
        assertFalse("tvdb" in ids)
    }

    @Test
    fun `marks are numbered, and episodes MDBList has no counterpart for count as not found`() = runTest(UnconfinedTestDispatcher()) {
        connected()
        conanLookups()
        engine.reply(200, """{"updated":{"episodes":1}}""")
        val writer = MdbListHistoryWriter(session, numbering()::toMdbList) { clock.now }
        val result = writer.addToHistory(1, listOf(
            TrackingHistoryItem(conan(34, 35), watchedAtEpochMs = 1_763_000_000_000L),
            TrackingHistoryItem(conan(20, 2)), // merged at MDBList
            TrackingHistoryItem(conan(1, 1, videoId = "kitsu:210:1")) // not a numbering MDBList knows
        ))
        assertEquals(2, result.notFoundCount)
        assertEquals(3, engine.calls.size) // one lookup each, then one write
        val season = body(2).getValue("shows").jsonArray.single().jsonObject.getValue("seasons").jsonArray.single().jsonObject
        assertEquals(1, season.getValue("number").jsonPrimitive.content.toInt())
        val episode = season.getValue("episodes").jsonArray.single().jsonObject
        assertEquals(1221, episode.getValue("number").jsonPrimitive.content.toInt())
        assertEquals("2025-11-13T02:13:20Z", episode.getValue("watched_at").jsonPrimitive.content)
    }

    @Test
    fun `an episode with its own TMDB id skips numbering, the others get the profile and item time`() = runTest {
        connected(2)
        val asked = mutableListOf<Triple<Int, Int?, Long?>>()
        val writer = MdbListHistoryWriter(session, { profileId, reference, itemTime ->
            asked += Triple(profileId, reference.episode?.number, itemTime)
            MdbListNumbered(reference)
        }) { 99L }
        engine.reply(200, "{}")
        writer.addToHistory(2, listOf(TrackingHistoryItem(episode(number = 3, episodeTmdb = 555L)), TrackingHistoryItem(episode(number = 4))))
        assertEquals(listOf(Triple(2, 4, 99L)), asked)
        engine.reply(200, "{}")
        writer.removeFromHistory(2, listOf(episode(number = 5)))
        assertEquals(Triple(2, 5, null), asked.last())
    }

    @Test
    fun `the numbering's after-send step runs only once MDBList accepted the write`() = runTest {
        connected()
        var sent = 0
        val numbering: MdbListNumbering = { _, reference, _ -> MdbListNumbered(reference) { sent++ } }
        val scrobbler = MdbListScrobbler(session, TEST_CONFIG, profile, numbering) { clock.now }
        engine.reply(400)
        failsWith<MdbListApiException> { scrobbler.scrobble(TrackingScrobbleAction.START, TrackingScrobbleEvent(episode(), 3.0)) }
        assertEquals(0, sent)
        engine.reply(200, "{}")
        scrobbler.scrobble(TrackingScrobbleAction.START, TrackingScrobbleEvent(episode(), 3.0))
        assertEquals(1, sent)

        val writer = MdbListHistoryWriter(session, numbering) { clock.now }
        engine.reply(400)
        failsWith<MdbListApiException> { writer.addToHistory(1, listOf(TrackingHistoryItem(episode()))) }
        assertEquals(1, sent)
        engine.reply(200, "{}")
        writer.addToHistory(1, listOf(TrackingHistoryItem(episode()), TrackingHistoryItem(episode(number = 3))))
        assertEquals(3, sent)
    }

    @Test
    fun `a batch MDBList partly did not place runs no after-send step`() = runTest {
        connected()
        var sent = 0
        val writer = MdbListHistoryWriter(session, { _, reference, _ -> MdbListNumbered(reference) { sent++ } }) { clock.now }
        engine.reply(200, """{"not_found":{"episodes":1}}""")
        writer.addToHistory(1, listOf(TrackingHistoryItem(episode()), TrackingHistoryItem(episode(number = 3))))
        assertEquals(0, sent)
    }
}
