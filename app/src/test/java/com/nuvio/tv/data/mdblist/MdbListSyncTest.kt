package com.nuvio.tv.data.mdblist

import com.nuvio.tv.core.tracking.TrackingAccount
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Spec 105 S2a: the last_activities-gated sync, journal deltas and Continue Watching removal. */
class MdbListSyncTest {
    @get:Rule val folder = TemporaryFolder()

    private val engine = FakeMdbListEngine()
    private val clock = FakeClock(now = parseMdbListTime("2026-11-10T00:00:00Z")!!)
    private val accounts = accountStore()
    private val quota = MdbListQuota { clock.now }
    private val session = MdbListSession(mdbListClient(engine, clock), accounts, quota)
    private val store by lazy { MdbListSyncStore(folder.root, Dispatchers.Unconfined) }
    private val sync by lazy { MdbListSync(session, store, accounts) { clock.now } }

    private val key = "abc123def456ghi789"

    private suspend fun connected() =
        accounts.save(1, TrackingProviderId.MDBLIST, TrackingAccount(accessToken = key, accountName = "arshadk"))

    private fun activities(
        server: String = "2026-11-09T00:00:00Z",
        watched: String = "w1",
        journal: String = "j1",
        paused: String = "p1",
        rated: String = "r1"
    ) = """{"watched_at":"$watched","episode_watched_at":"e1","journal_at":"$journal","paused_at":"$paused",
        "rated_at":"$rated","server_time":"$server"}"""

    private val movieRow = """{"watched_at":"2026-11-01T10:00:00Z","movie":{"title":"The Matrix",
        "ids":{"imdb":"tt0133093","tmdb":603,"mdblist":"m1"}}}"""

    private fun episodeRow(season: Int?, number: Int?, at: String = "2026-11-02T10:00:00Z") =
        """{"last_watched_at":"$at","episode":{"season":$season,"number":$number,"name":"Ep",
            "show":{"title":"Breaking Bad","ids":{"tmdb":1396,"imdb":"tt0903747","mdblist":"s1"}}}}"""

    private fun playback(id: Long, episode: Int, imdb: String = "tt0903747") =
        """{"id":$id,"progress":40.5,"paused_at":"2026-11-03T10:00:00Z","type":"episode",
            "show":{"title":"Breaking Bad","ids":{"tmdb":1396,"imdb":"$imdb"}},"episode":{"season":1,"number":$episode}}"""

    /** A first sync: one film, one episode, one playback. */
    private suspend fun initialSync() {
        engine.reply(200, activities())
        engine.reply(200, """{"movies":[$movieRow],"pagination":{"next_cursor":null}}""")
        engine.reply(200, """{"episodes":[${episodeRow(1, 2)}]}""")
        engine.reply(200, "[${playback(7, 3)}]")
        assertTrue(sync.refresh(1, TrackingRefreshIntent.USER_INITIATED))
        engine.calls.clear()
    }

    private fun journal(vararg rows: String, cursor: String? = null) =
        """{"journal":[${rows.joinToString()}],"server_time":"2026-11-09T12:00:00Z",
            "pagination":{"has_more":${cursor != null}${cursor?.let { ""","next_cursor":"$it"""" } ?: ""}}}"""

    private fun row(type: String, status: String, ids: String, season: Int? = null, episode: Int? = null, category: String = "watched") =
        """{"category":"$category","item_type":"$type","status":"$status","ids":$ids,"action_at":"2026-11-09T06:00:00Z",
            "value_at":"2026-11-08T20:00:00Z"${season?.let { ""","season":$it""" } ?: ""}${episode?.let { ""","episode":$it""" } ?: ""}}"""

    @Test
    fun `a first sync reads activities, then every watched page of each type, then playbacks`() = runTest {
        connected()
        initialSync()
        val snapshot = store.get(1)!!
        assertEquals("2026-11-09T00:00:00Z", snapshot.journalSince)
        assertEquals(listOf("tt0133093", "tt0903747"), snapshot.marks.map { it.media.contentId })
        assertEquals(1 to 2, snapshot.marks[1].season to snapshot.marks[1].episode)
        assertEquals(7L, snapshot.playback.single().id)
        assertEquals(clock.now, accounts.get(1, TrackingProviderId.MDBLIST)!!.lastSyncedAtEpochMs)
    }

    @Test
    fun `the first sync's calls go in order with the type filter and page size`() = runTest {
        connected()
        engine.reply(200, activities())
        engine.reply(200, "{}")
        engine.reply(200, "{}")
        engine.reply(200, "[]")
        sync.refresh(1, TrackingRefreshIntent.USER_INITIATED)
        assertEquals(
            listOf("/sync/last_activities", "/sync/watched", "/sync/watched", "/sync/playback", "/watchlist/items"),
            engine.calls.map { it.path }
        )
        assertEquals(listOf("movie", "episode"), engine.calls.subList(1, 3).map { it.query("mediatype") })
        assertTrue(engine.calls.subList(1, 3).all { it.query("limit") == "1000" && it.query("apikey") == key })
    }

    @Test
    fun `watched pages follow the body's cursor, then the header's, keeping the type filter`() = runTest {
        connected()
        engine.reply(200, activities())
        engine.reply(200, "{}")
        engine.reply(200, """{"episodes":[${episodeRow(1, 1)}],"pagination":{"next_cursor":"c1"}}""")
        engine.reply(200, """{"episodes":[${episodeRow(1, 2)}]}""", mapOf("X-Next-Cursor" to "c2"))
        engine.reply(200, """{"episodes":[${episodeRow(1, 3)}]}""")
        engine.reply(200, "[]")
        sync.refresh(1, TrackingRefreshIntent.USER_INITIATED)
        val pages = engine.calls.filter { it.query("mediatype") == "episode" }
        assertEquals(listOf(null, "c1", "c2"), pages.map { it.query("cursor") })
        assertEquals(listOf(1, 2, 3), store.get(1)!!.marks.map { it.episode })
    }

    @Test
    fun `a cursor that comes back again is a fault, and nothing is saved`() = runTest {
        connected()
        engine.reply(200, activities())
        engine.reply(200, """{"pagination":{"next_cursor":"c1"}}""")
        engine.reply(200, """{"pagination":{"next_cursor":"c1"}}""")
        failsWith<MdbListException> { sync.refresh(1, TrackingRefreshIntent.USER_INITIATED) }
        assertNull(store.get(1))
    }

    @Test
    fun `rows that would mark a whole series are dropped`() = runTest {
        connected()
        engine.reply(200, activities())
        engine.reply(200, "{}")
        engine.reply(200, """{"episodes":[${episodeRow(1, null)}, ${episodeRow(null, 4)}, ${episodeRow(2, 5)}]}""")
        engine.reply(200, "[]")
        sync.refresh(1, TrackingRefreshIntent.USER_INITIATED)
        assertEquals(listOf(2 to 5), store.get(1)!!.marks.map { it.season to it.episode })
    }

    @Test
    fun `nothing moved costs one call and keeps the snapshot`() = runTest {
        connected()
        initialSync()
        val before = store.get(1)
        engine.reply(200, activities(server = "2026-11-09T05:00:00Z", rated = "r2"))
        assertTrue(sync.refresh(1, TrackingRefreshIntent.AUTOMATIC))
        assertEquals(listOf("/sync/last_activities"), engine.calls.map { it.path })
        assertSame(before, store.get(1))
    }

    @Test
    fun `moved watched stamps apply the journal since the saved server time`() = runTest {
        connected()
        initialSync()
        engine.reply(200, activities(server = "2026-11-09T12:00:00Z", journal = "j2"))
        engine.reply(
            200,
            journal(
                row("episode", "added", """{"tmdb":1396}""", season = 1, episode = 3),
                row("movie", "removed", """{"imdb":"tt0133093"}"""),
                row("show", "added", """{"tmdb":1396}"""),
                row("season", "removed", """{"tmdb":1396}""", season = 1),
                row("movie", "added", """{"tmdb":27205}""", category = "rated")
            )
        )
        assertTrue(sync.refresh(1, TrackingRefreshIntent.USER_INITIATED))
        val call = engine.calls[1]
        assertEquals("/sync/journal", call.path)
        assertEquals("2026-11-09T00:00:00Z", call.query("since"))
        assertEquals(2, engine.calls.size) // playback did not move
        val snapshot = store.get(1)!!
        assertEquals(listOf(1 to 2, 1 to 3), snapshot.marks.map { it.season to it.episode })
        // The new episode takes the show's stored title and ids.
        assertEquals("Breaking Bad", snapshot.marks.last().media.title)
        assertEquals("tt0903747", snapshot.marks.last().media.contentId)
        assertEquals("2026-11-08T20:00:00Z", snapshot.marks.last().watchedAt)
        assertEquals("2026-11-09T12:00:00Z", snapshot.journalSince)
    }

    @Test
    fun `journal pages follow the cursor without since`() = runTest {
        connected()
        initialSync()
        engine.reply(200, activities(journal = "j2"))
        engine.reply(200, journal(row("episode", "added", """{"tmdb":1396}""", season = 1, episode = 4), cursor = "n1"))
        engine.reply(200, journal(row("episode", "removed", """{"imdb":"tt0903747"}""", season = 1, episode = 2)))
        sync.refresh(1, TrackingRefreshIntent.USER_INITIATED)
        assertEquals("n1", engine.calls[2].query("cursor"))
        assertNull(engine.calls[2].query("since"))
        assertEquals(listOf(1 to 4), store.get(1)!!.marks.filter { it.isEpisode }.map { it.season to it.episode })
    }

    @Test
    fun `a journal that wants a full sync, by 409 or by flag, pulls everything`() = runTest {
        connected()
        initialSync()
        listOf(409 to """{"requires_full_sync":true,"reason":"sync_window_expired"}""", 200 to """{"requires_full_sync":true}""")
            .forEach { (status, body) ->
                engine.calls.clear()
                engine.reply(200, activities(journal = "j-$status"))
                engine.reply(status, body)
                engine.reply(200, "{}")
                engine.reply(200, "{}")
                engine.reply(200, "[]")
                assertTrue(sync.refresh(1, TrackingRefreshIntent.USER_INITIATED))
                assertEquals(
                    listOf("/sync/last_activities", "/sync/journal", "/sync/watched", "/sync/watched", "/sync/playback"),
                    engine.calls.map { it.path }
                )
                assertTrue(store.get(1)!!.marks.isEmpty())
            }
    }

    @Test
    fun `a journal reply without its list is not taken as empty`() = runTest {
        connected()
        initialSync()
        val before = store.get(1)
        engine.reply(200, activities(journal = "j2"))
        engine.reply(200, """{"server_time":"2026-11-09T12:00:00Z"}""")
        failsWith<MdbListException> { sync.refresh(1, TrackingRefreshIntent.USER_INITIATED) }
        assertSame(before, store.get(1))
    }

    @Test
    fun `a moved paused stamp re-reads playbacks only`() = runTest {
        connected()
        initialSync()
        engine.reply(200, activities(paused = "p2"))
        engine.reply(200, "[]")
        sync.refresh(1, TrackingRefreshIntent.USER_INITIATED)
        assertEquals(listOf("/sync/last_activities", "/sync/playback"), engine.calls.map { it.path })
        assertTrue(store.get(1)!!.playback.isEmpty())
        assertEquals(2, store.get(1)!!.marks.size)
    }

    @Test
    fun `a since older than the journal keeps, on MDBList's clock, is a full pull without asking the journal`() = runTest {
        connected()
        initialSync()
        engine.reply(200, activities(server = "2026-12-09T00:00:00Z", journal = "j2"))
        engine.reply(200, "{}")
        engine.reply(200, "{}")
        engine.reply(200, "[]")
        sync.refresh(1, TrackingRefreshIntent.USER_INITIATED)
        assertEquals(listOf("/sync/last_activities", "/sync/watched", "/sync/watched", "/sync/playback"), engine.calls.map { it.path })
    }

    @Test
    fun `a quiet run moves a day-old since on, and a younger one is left alone`() = runTest {
        connected()
        initialSync()
        engine.reply(200, activities(server = "2026-11-09T20:00:00Z"))
        sync.refresh(1, TrackingRefreshIntent.AUTOMATIC)
        assertEquals("2026-11-09T00:00:00Z", store.get(1)!!.journalSince)
        engine.reply(200, activities(server = "2026-11-10T06:00:00Z"))
        sync.refresh(1, TrackingRefreshIntent.AUTOMATIC)
        assertEquals("2026-11-10T06:00:00Z", store.get(1)!!.journalSince)
        assertEquals(2, store.get(1)!!.marks.size)
        assertEquals(listOf("/sync/last_activities", "/sync/last_activities"), engine.calls.map { it.path })
    }

    @Test
    fun `a reply without server time keeps the saved since`() = runTest {
        connected()
        initialSync()
        engine.reply(200, """{"watched_at":"w1","episode_watched_at":"e1","journal_at":"j2","paused_at":"p1"}""")
        engine.reply(200, journal())
        sync.refresh(1, TrackingRefreshIntent.USER_INITIATED)
        assertEquals("2026-11-09T00:00:00Z", store.get(1)!!.journalSince)
        assertEquals("j2", store.get(1)!!.activities!!.journalAt)
    }

    @Test
    fun `both stamps moved apply the journal and re-read playbacks`() = runTest {
        connected()
        initialSync()
        engine.reply(200, activities(journal = "j2", paused = "p2"))
        engine.reply(200, journal(row("episode", "added", """{"tmdb":1396}""", season = 2, episode = 1)))
        engine.reply(200, "[${playback(8, 4)}]")
        sync.refresh(1, TrackingRefreshIntent.USER_INITIATED)
        assertEquals(listOf("/sync/last_activities", "/sync/journal", "/sync/playback"), engine.calls.map { it.path })
        assertEquals(2 to 1, store.get(1)!!.marks.last().let { it.season to it.episode })
        assertEquals(listOf(8L), store.get(1)!!.playback.map { it.id })
    }

    @Test
    fun `a connect while a sync runs leaves no old account's data`() = runTest {
        connected()
        val gate = CompletableDeferred<Unit>()
        engine.onCall = { if (engine.calls.last().path == "/sync/last_activities") gate.await() }
        engine.replyTo(MdbListHttpMethod.GET, "/sync/last_activities", 200, activities())
        engine.replyTo(MdbListHttpMethod.GET, "/user", 200, """{"username":"other"}""")
        engine.replyTo(MdbListHttpMethod.GET, "/sync/playback", 200, "[]")
        repeat(2) { engine.replyTo(MdbListHttpMethod.GET, "/sync/watched", 200, "{}") }
        val auth = MdbListKeyAuth(mdbListClient(engine, clock), session, accounts) { store.clear(it) }
        val running = async { sync.refresh(1, TrackingRefreshIntent.USER_INITIATED) }
        testScheduler.runCurrent()
        auth.connect(1, "zzz999yyy888xxx777") { true }
        gate.complete(Unit)
        assertFalse(running.await())
        assertNull(store.get(1))
    }

    @Test
    fun `a failed fetch keeps the saved stamps so the next run asks again`() = runTest {
        connected()
        initialSync()
        val stamped = accounts.get(1, TrackingProviderId.MDBLIST)!!.lastSyncedAtEpochMs
        clock.now += 60_000L
        engine.reply(200, activities(server = "2026-11-09T12:00:00Z", journal = "j2"))
        repeat(4) { engine.reply(503, "{}") }
        failsWith<MdbListApiException> { sync.refresh(1, TrackingRefreshIntent.USER_INITIATED) }
        assertEquals("2026-11-09T00:00:00Z", store.get(1)!!.journalSince)
        assertEquals("j1", store.get(1)!!.activities!!.journalAt)
        assertEquals(stamped, accounts.get(1, TrackingProviderId.MDBLIST)!!.lastSyncedAtEpochMs)
    }

    @Test
    fun `no key, a revoked key or a spent day sends nothing`() = runTest {
        assertFalse(sync.refresh(1, TrackingRefreshIntent.USER_INITIATED))
        accounts.save(1, TrackingProviderId.MDBLIST, TrackingAccount(accessToken = key, revoked = true))
        assertFalse(sync.refresh(1, TrackingRefreshIntent.USER_INITIATED))
        connected()
        quota.recordFailure(1, MdbListApiException(429, dailyLimit = true))
        assertFalse(sync.refresh(1, TrackingRefreshIntent.USER_INITIATED))
        assertTrue(engine.calls.isEmpty())
    }

    @Test
    fun `a sync running when the key changes never saves the old account's data`() = runTest {
        connected()
        val gate = CompletableDeferred<Unit>()
        engine.onCall = { gate.await() }
        engine.reply(200, activities())
        engine.reply(200, "{}")
        engine.reply(200, "{}")
        engine.reply(200, "[]")
        val running = async { sync.refresh(1, TrackingRefreshIntent.USER_INITIATED) }
        testScheduler.runCurrent()
        store.clear(1)
        gate.complete(Unit)
        assertFalse(running.await())
        assertNull(store.get(1))
    }

    @Test
    fun `removing a title's playback reads MDBList's list, clears it there and in the snapshot`() = runTest {
        connected()
        initialSync()
        engine.reply(200, "[${playback(7, 3)}, ${playback(8, 4)}, ${playback(9, 1, imdb = "tt1")}]")
        engine.reply(200, """{"action":"clear","deleted":true}""")
        engine.reply(404, """{"error":"not found"}""")
        sync.removePlayback(1, "tt0903747", null, null)
        assertEquals(listOf("/sync/playback", "/scrobble/clear", "/scrobble/clear"), engine.calls.map { it.path })
        val body = Json.parseToJsonElement(engine.calls[1].body!!).jsonObject.getValue("show").jsonObject
        val ids = body.getValue("ids").jsonObject
        assertEquals("tt0903747", ids.getValue("imdb").jsonPrimitive.content)
        assertTrue(ids.getValue("imdb").jsonPrimitive.isString)
        assertEquals("1396", ids.getValue("tmdb").jsonPrimitive.content)
        assertFalse(ids.getValue("tmdb").jsonPrimitive.isString)
        assertEquals(1, body.getValue("season").jsonPrimitive.content.toInt())
        assertEquals(3, body.getValue("episode").jsonPrimitive.content.toInt())
        assertEquals(MdbListHttpMethod.POST, engine.calls[1].method)
        assertTrue(store.get(1)!!.playback.isEmpty())
    }

    @Test
    fun `a film's clear names the film, and an all-digit mdblist id stays text`() = runTest {
        connected()
        initialSync()
        engine.reply(200, """[{"id":3,"progress":20,"paused_at":"2026-11-03T10:00:00Z","type":"movie",
            "movie":{"title":"Inception","ids":{"tmdb":27205,"mdblist":"12345"}}}]""")
        engine.reply(200, """{"deleted":true}""")
        sync.removePlayback(1, "tmdb:27205", null, null)
        val ids = Json.parseToJsonElement(engine.calls[1].body!!).jsonObject.getValue("movie").jsonObject.getValue("ids").jsonObject
        assertTrue(ids.getValue("mdblist").jsonPrimitive.isString)
        assertFalse(ids.getValue("tmdb").jsonPrimitive.isString)
    }

    @Test
    fun `a key change during a removal stops the clears`() = runTest {
        connected()
        initialSync()
        engine.reply(200, "[${playback(7, 3)}]")
        engine.onCall = { store.clear(1) }
        sync.removePlayback(1, "tt0903747", null, null)
        assertEquals(listOf("/sync/playback"), engine.calls.map { it.path })
    }

    @Test
    fun `removing one episode's playback clears only that one`() = runTest {
        connected()
        initialSync()
        engine.reply(200, "[${playback(7, 3)}, ${playback(8, 4)}]")
        engine.reply(200, """{"deleted":true}""")
        sync.removePlayback(1, "tt0903747", 1, 4)
        assertEquals(2, engine.calls.size)
        assertEquals(listOf(7L), store.get(1)!!.playback.map { it.id })
    }

    @Test
    fun `a failed clear does not stop the others, and the failure is thrown`() = runTest {
        connected()
        initialSync()
        engine.reply(200, "[${playback(7, 3)}, ${playback(8, 4)}]")
        engine.reply(403, """{"error":"forbidden"}""")
        engine.reply(200, """{"deleted":true}""")
        assertEquals(403, failsWith<MdbListApiException> { sync.removePlayback(1, "tt0903747", null, null) }.status)
        assertEquals(3, engine.calls.size)
        assertEquals(listOf(7L), store.get(1)!!.playback.map { it.id })
    }

    @Test
    fun `a removal during a sync that refetches playbacks is not undone by the sync's save`() = runTest {
        connected()
        initialSync()
        val gate = CompletableDeferred<Unit>()
        engine.onCall = { gate.await() }
        engine.replyTo(MdbListHttpMethod.GET, "/sync/last_activities", 200, activities(paused = "p2"))
        // Both the sync and the removal read the list before the clear lands.
        engine.replyTo(MdbListHttpMethod.GET, "/sync/playback", 200, "[${playback(7, 3)}]")
        engine.replyTo(MdbListHttpMethod.GET, "/sync/playback", 200, "[${playback(7, 3)}]")
        engine.replyTo(MdbListHttpMethod.POST, "/scrobble/clear", 200, """{"deleted":true}""")
        val syncing = async { sync.refresh(1, TrackingRefreshIntent.USER_INITIATED) }
        val removing = async { sync.removePlayback(1, "tt0903747", null, null) }
        testScheduler.runCurrent()
        gate.complete(Unit)
        assertTrue(syncing.await())
        removing.await()
        assertEquals("p2", store.get(1)!!.activities!!.pausedAt)
        assertTrue(store.get(1)!!.playback.isEmpty())
    }

    @Test
    fun `a journal row meets its stored mark through any shared id`() {
        val marks = listOf(MdbListWatchedMark(MdbListMedia("Matrix", ids = mapOf("tmdb" to kotlinx.serialization.json.JsonPrimitive(603)))))
        val rows = listOf(
            MdbListJournalRow("watched", "movie", mapOf("tmdb" to kotlinx.serialization.json.JsonPrimitive("603"),
                "imdb" to kotlinx.serialization.json.JsonPrimitive("tt0133093")), "removed")
        )
        assertTrue(applyMdbListJournal(marks, rows).isEmpty())
    }
}
