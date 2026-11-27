package com.nuvio.tv.data.mdblist

import com.nuvio.tv.core.tracking.TrackingAccount
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRateLimitException
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.domain.model.LibraryEntryInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Spec 105 S3a: the watchlist in the sync and MDBList as the Library source. */
class MdbListLibraryTest {
    @get:Rule val folder = TemporaryFolder()

    private val engine = FakeMdbListEngine().apply { watchlistDefault = null }
    private val clock = FakeClock(now = parseMdbListTime("2026-11-10T00:00:00Z")!!)
    private val accounts = accountStore()
    private val quota = MdbListQuota { clock.now }
    private val session = MdbListSession(mdbListClient(engine, clock), accounts, quota)
    private val store by lazy { MdbListSyncStore(folder.root, Dispatchers.Unconfined) }
    private val sync by lazy { MdbListSync(session, store, accounts) { clock.now } }
    private val profile = MutableStateFlow(1)
    private val refreshes = mutableListOf<TrackingRefreshIntent>()

    private fun TestScope.library() = MdbListTrackingLibraryProvider(
        store, sync, session, profile, backgroundScope, UnconfinedTestDispatcher(testScheduler),
        refreshSync = { intent -> refreshes += intent; sync.refresh(profile.value, intent) }
    ) { clock.now }

    private suspend fun connected() =
        accounts.save(1, TrackingProviderId.MDBLIST, TrackingAccount(accessToken = "abc123def456ghi789", accountName = "arshadk"))

    private fun activities(watchlisted: String? = "l1", server: String = "2026-11-09T00:00:00Z") =
        """{${watchlisted?.let { """"watchlisted_at":"$it",""" } ?: ""}"watched_at":"w1","journal_at":"j1","paused_at":"p1",
            "server_time":"$server"}"""

    private val matrixRow = """{"id":603,"title":"The Matrix","imdb_id":"tt0133093","tvdb_id":null,
        "ids":{"mdblist":"m603","imdb":"tt0133093","tmdb":603,"tvdb":null},"mediatype":"movie","release_year":1999,
        "poster":"/matrix.jpg","genres":[{"name":"Action"},"Science Fiction"],"watchlist_at":"2025-02-02 17:28:22.000000"}"""
    private val teacherRow = """{"id":258902,"title":"English Teacher","imdb_id":"tt20782190","tvdb_id":421968,
        "mediatype":"show","release_year":2024,"poster":"https://img.example/p.jpg","genres":"Comedy, Drama"}"""

    private fun watchlist(movies: String = matrixRow, shows: String = teacherRow, cursor: String? = null) =
        """{"movies":[$movies],"shows":[$shows]${cursor?.let { ""","pagination":{"next_cursor":"$it"}""" } ?: ""}}"""

    /** A first sync with an empty history and the given watchlist pages. */
    private suspend fun firstSync(vararg pages: String) {
        engine.reply(200, activities())
        engine.reply(200, "{}")
        engine.reply(200, "{}")
        engine.reply(200, "[]")
        pages.forEach { engine.reply(200, it) }
        assertTrue(sync.refresh(1, TrackingRefreshIntent.USER_INITIATED))
        engine.calls.clear()
    }

    private fun item(id: String, type: String = "movie") = LibraryEntryInput(itemId = id, itemType = type, title = "Title $id")

    private fun SentCall.json(): JsonObject = Json.parseToJsonElement(body!!).jsonObject

    @Test
    fun `a first sync pulls the watchlist with posters and genres, rows read from every id field`() = runTest {
        connected()
        firstSync(watchlist())
        val items = store.get(1)!!.watchlist!!
        assertEquals(listOf("tt0133093", "tt20782190"), items.map { it.media.contentId })
        assertEquals(listOf(true, false), items.map { it.isMovie })
        assertEquals(258902L, items[1].media.tmdb)
        assertEquals("421968", items[1].media.id("tvdb"))
        assertEquals(listOf("Action", "Science Fiction"), items[0].genres)
        assertEquals(listOf("Comedy", "Drama"), items[1].genres)
    }

    @Test
    fun `the watchlist call asks for 1000 per page with posters and genres, and follows cursors`() = runTest {
        connected()
        engine.reply(200, activities())
        engine.reply(200, "{}")
        engine.reply(200, "{}")
        engine.reply(200, "[]")
        engine.reply(200, watchlist(shows = "", cursor = "c1"))
        engine.reply(200, """{"movies":[],"shows":[$teacherRow]}""", mapOf("X-Next-Cursor" to "c2"))
        engine.reply(200, """{"movies":[],"shows":[]}""")
        assertTrue(sync.refresh(1, TrackingRefreshIntent.USER_INITIATED))
        val pages = engine.calls.filter { it.path == "/watchlist/items" }
        assertEquals(listOf(null, "c1", "c2"), pages.map { it.query("cursor") })
        assertTrue(pages.all { it.query("limit") == "1000" && it.query("append_to_response") == "poster,genres" })
        assertEquals(2, store.get(1)!!.watchlist!!.size)
    }

    @Test
    fun `a page saying more with no cursor is a fault, and nothing is saved`() = runTest {
        connected()
        engine.reply(200, activities())
        engine.reply(200, "{}")
        engine.reply(200, "{}")
        engine.reply(200, "[]")
        engine.reply(200, watchlist(), mapOf("X-Has-More" to "true"))
        failsWith<MdbListException> { sync.refresh(1, TrackingRefreshIntent.USER_INITIATED) }
        assertNull(store.get(1))
    }

    @Test
    fun `the watchlist is pulled again only when its stamp moves, and alone when nothing else moved`() = runTest {
        connected()
        firstSync(watchlist())
        engine.reply(200, activities())
        assertTrue(sync.refresh(1, TrackingRefreshIntent.AUTOMATIC))
        assertEquals(listOf("/sync/last_activities"), engine.calls.map { it.path })
        engine.calls.clear()
        engine.reply(200, activities(watchlisted = "l2"))
        engine.reply(200, watchlist(shows = ""))
        assertTrue(sync.refresh(1, TrackingRefreshIntent.AUTOMATIC))
        assertEquals(listOf("/sync/last_activities", "/watchlist/items"), engine.calls.map { it.path })
        assertEquals(listOf("tt0133093"), store.get(1)!!.watchlist!!.map { it.media.contentId })
        assertEquals("l2", store.get(1)!!.activities!!.watchlistedAt)
    }

    @Test
    fun `a snapshot from before S3a pulls the watchlist once, keeping its marks`() = runTest {
        connected()
        firstSync(watchlist())
        val old = store.get(1)!!.copy(watchlist = null)
        store.saveIf(1, store.generation(1), old)
        engine.reply(200, activities())
        engine.reply(200, watchlist(movies = ""))
        assertTrue(sync.refresh(1, TrackingRefreshIntent.AUTOMATIC))
        assertEquals(listOf("/sync/last_activities", "/watchlist/items"), engine.calls.map { it.path })
        assertEquals(listOf("tt20782190"), store.get(1)!!.watchlist!!.map { it.media.contentId })
        assertEquals(old.journalSince, store.get(1)!!.journalSince)
    }

    @Test
    fun `a failed watchlist read saves nothing, so the next run asks again`() = runTest {
        connected()
        firstSync(watchlist())
        val before = store.get(1)
        engine.reply(200, activities(watchlisted = "l2"))
        engine.reply(404)
        failsWith<MdbListApiException> { sync.refresh(1, TrackingRefreshIntent.AUTOMATIC) }
        assertEquals(before, store.get(1))
    }

    @Test
    fun `the Library shows the watchlist newest first with TMDb posters`() = runTest(UnconfinedTestDispatcher()) {
        connected()
        val library = library()
        firstSync(watchlist())
        val items = library.items.first()
        assertEquals(listOf("tt0133093", "tt20782190"), items.map { it.id })
        val matrix = items[0]
        assertEquals("movie", matrix.type)
        assertEquals("https://image.tmdb.org/t/p/w500/matrix.jpg", matrix.poster)
        assertEquals("1999", matrix.releaseInfo)
        assertEquals(parseMdbListTime("2025-02-02T17:28:22Z"), matrix.listedAt)
        assertNull(matrix.trackerList)
        assertEquals("series", items[1].type)
        assertEquals("https://img.example/p.jpg", items[1].poster)
        assertEquals(0L, items[1].listedAt)
        assertTrue(library.isInLibrary("tmdb:603", "movie").first())
        assertFalse(library.isInLibrary("tt0133093", "series").first())
    }

    @Test
    fun `an add sends the title's ids under its type and shows it at once`() = runTest(UnconfinedTestDispatcher()) {
        connected()
        val library = library()
        firstSync(watchlist(movies = "", shows = ""))
        engine.reply(200, """{"added":1,"existing":0,"not_found":0}""")
        library.setInLibrary(1, item("tmdb:1396", "series"), inLibrary = true)
        val call = engine.calls.single()
        assertEquals(MdbListHttpMethod.POST to "/watchlist/items/add", call.method to call.path)
        assertEquals("""{"shows":[{"ids":{"tmdb":1396}}]}""", call.body)
        assertTrue(library.isInLibrary("tmdb:1396", "series").first())
        assertEquals(listOf("tmdb:1396"), library.items.first().map { it.id })
    }

    @Test
    fun `a removal goes once, and nothing goes when this TV already shows the wanted state`() = runTest(UnconfinedTestDispatcher()) {
        connected()
        val library = library()
        firstSync(watchlist())
        library.setInLibrary(1, item("tt0133093"), inLibrary = true)
        assertTrue(engine.calls.isEmpty())
        engine.reply(200, """{"removed":1,"not_found":0}""")
        library.setInLibrary(1, item("tt0133093"), inLibrary = false)
        assertEquals("/watchlist/items/remove", engine.calls.single().path)
        assertEquals("""{"movies":[{"ids":{"imdb":"tt0133093"}}]}""", engine.calls.single().body)
        assertFalse(library.isInLibrary("tt0133093", "movie").first())
        assertEquals(listOf("tt20782190"), library.items.first().map { it.id })
    }

    @Test
    fun `an add MDBList cannot place throws with nothing recorded, an id it cannot take sends nothing`() = runTest(UnconfinedTestDispatcher()) {
        connected()
        val library = library()
        firstSync(watchlist(movies = "", shows = ""))
        engine.reply(200, """{"added":{"movies":0,"shows":0},"existing":0,"not_found":{"movies":1,"shows":0}}""")
        failsWith<MdbListUnmatchableException> { library.setInLibrary(1, item("tt9999999"), inLibrary = true) }
        assertFalse(library.isInLibrary("tt9999999", "movie").first())
        engine.calls.clear()
        failsWith<MdbListUnmatchableException> { library.setInLibrary(1, item("kitsu:12", "series"), inLibrary = true) }
        assertTrue(engine.calls.isEmpty())
    }

    @Test
    fun `this TV's change stays until a sync started a minute after it has settled`() = runTest(UnconfinedTestDispatcher()) {
        connected()
        val library = library()
        firstSync(watchlist())
        engine.reply(200, """{"removed":1}""")
        library.setInLibrary(1, item("tt0133093"), inLibrary = false)
        // MDBList's watchlist still lists it (the write not seen yet): this TV keeps showing the removal.
        clock.now += 30_000L
        engine.reply(200, activities(watchlisted = "l2"))
        engine.reply(200, watchlist())
        assertTrue(sync.refresh(1, TrackingRefreshIntent.AUTOMATIC))
        assertFalse(library.isInLibrary("tt0133093", "movie").first())
        // A minute on, still listed at MDBList (someone re-added it): the overlay leaves and MDBList's list shows.
        clock.now += 60_000L
        engine.reply(200, activities(watchlisted = "l3"))
        engine.reply(200, watchlist())
        assertTrue(sync.refresh(1, TrackingRefreshIntent.AUTOMATIC))
        assertTrue(library.isInLibrary("tt0133093", "movie").first())
    }

    @Test
    fun `a pull that saw the old list under the write's stamp is pulled again until the write had time to show`() =
        runTest(UnconfinedTestDispatcher()) {
            connected()
            val library = library()
            firstSync(watchlist())
            engine.reply(200, """{"removed":1}""")
            library.setInLibrary(1, item("tt0133093"), inLibrary = false)
            // Inside the minute: the stamp moved but the list still has the title.
            clock.now += 30_000L
            engine.reply(200, activities(watchlisted = "l2"))
            engine.reply(200, watchlist())
            assertTrue(sync.refresh(1, TrackingRefreshIntent.AUTOMATIC))
            // Later, the stamp stands: the list is read again anyway, now without the title.
            clock.now += 60_000L
            engine.calls.clear()
            engine.reply(200, activities(watchlisted = "l2"))
            engine.reply(200, watchlist(movies = ""))
            assertTrue(sync.refresh(1, TrackingRefreshIntent.AUTOMATIC))
            assertEquals(listOf("/sync/last_activities", "/watchlist/items"), engine.calls.map { it.path })
            assertFalse(library.isInLibrary("tt0133093", "movie").first())
            // That run started a minute after the write: the stamp rules again.
            engine.calls.clear()
            engine.reply(200, activities(watchlisted = "l2"))
            assertTrue(sync.refresh(1, TrackingRefreshIntent.AUTOMATIC))
            assertEquals(listOf("/sync/last_activities"), engine.calls.map { it.path })
        }

    @Test
    fun `a key change forgets this TV's changes, a write in flight included`() = runTest(UnconfinedTestDispatcher()) {
        connected()
        val library = library()
        firstSync(watchlist(movies = "", shows = ""))
        engine.onCall = { library.forget(1) }
        engine.reply(200, """{"added":1}""")
        library.setInLibrary(1, item("tt1"), inLibrary = true)
        engine.onCall = {}
        assertFalse(library.isInLibrary("tt1", "movie").first())
    }

    @Test
    fun `a transfer adds in one call, a batch with not found resent one at a time`() = runTest(UnconfinedTestDispatcher()) {
        connected()
        val library = library()
        firstSync(watchlist(movies = "", shows = ""))
        engine.reply(200, """{"added":2,"existing":0,"not_found":1}""")
        engine.reply(200, """{"added":1,"not_found":0}""")
        engine.reply(200, """{"added":0,"not_found":1}""")
        engine.reply(200, """{"added":0,"existing":1,"not_found":0}""")
        val accepted = library.addAll(1, listOf(item("tt1"), item("tt2"), item("tt3", "series"), item("kitsu:5", "series")))
        assertEquals(setOf("tt1", "tt3"), accepted)
        val bulk = engine.calls.first().json()
        assertEquals(2, bulk["movies"]!!.jsonArray.size)
        assertEquals(1, bulk["shows"]!!.jsonArray.size)
        assertEquals(4, engine.calls.size)
        assertEquals(listOf("tt1", "tt3"), library.items.first().map { it.id }.sorted())
    }

    @Test
    fun `a transfer stopped by the daily limit reports what was sent and accepted`() = runTest(UnconfinedTestDispatcher()) {
        connected()
        val library = library()
        firstSync(watchlist(movies = "", shows = ""))
        val items = (1..150).map { item("tt$it") }
        engine.reply(200, """{"added":100}""")
        engine.reply(429, DAILY_LIMIT_BODY)
        val stop = failsWith<TrackingRateLimitException> { library.addAll(1, items) }
        assertEquals(100, stop.accepted.size)
        assertEquals(150, stop.sent!!.size)
        // Paused now: the next transfer sends nothing.
        val next = failsWith<TrackingRateLimitException> { library.addAll(1, listOf(item("tt999"))) }
        assertTrue(next.sent!!.isEmpty())
        assertTrue(next.accepted.isEmpty())
    }

    @Test
    fun `a list pulled inside a write's minute is pulled again after a restart, whatever the stamp`() =
        runTest(UnconfinedTestDispatcher()) {
            connected()
            val library = library()
            firstSync(watchlist())
            engine.reply(200, """{"removed":1}""")
            library.setInLibrary(1, item("tt0133093"), inLibrary = false)
            clock.now += 30_000L
            engine.reply(200, activities(watchlisted = "l2"))
            engine.reply(200, watchlist())
            assertTrue(sync.refresh(1, TrackingRefreshIntent.AUTOMATIC))
            // A restart: the write marker and the overlay are gone; only the saved snapshot remains.
            val restarted = MdbListSync(session, MdbListSyncStore(folder.root, Dispatchers.Unconfined), accounts) { clock.now }
            clock.now += 60_000L
            engine.calls.clear()
            engine.reply(200, activities(watchlisted = "l2"))
            engine.reply(200, watchlist(movies = ""))
            assertTrue(restarted.refresh(1, TrackingRefreshIntent.AUTOMATIC))
            assertEquals(listOf("/sync/last_activities", "/watchlist/items"), engine.calls.map { it.path })
            assertEquals("l2", MdbListSyncStore(folder.root, Dispatchers.Unconfined).get(1)!!.activities!!.watchlistedAt)
        }

    @Test
    fun `a page whose body says more with no cursor is a fault, and a row with null ids still reads`() = runTest {
        connected()
        engine.reply(200, activities())
        engine.reply(200, "{}")
        engine.reply(200, "{}")
        engine.reply(200, "[]")
        engine.reply(200, """{"movies":[],"shows":[],"pagination":{"has_more":true}}""")
        failsWith<MdbListException> { sync.refresh(1, TrackingRefreshIntent.USER_INITIATED) }
        val row = mdbListJson.decodeFromString(MdbListWatchlistRow.serializer(), """{"id":603,"ids":null,"title":"x"}""")
        assertEquals(603L, row.toWatchlistItem(isMovie = true)!!.media.tmdb)
    }

    @Test
    fun `watched marks and the watchlist moving together are saved in one run`() = runTest {
        connected()
        firstSync(watchlist())
        engine.reply(200, """{"watchlisted_at":"l2","watched_at":"w2","journal_at":"j2","paused_at":"p1",
            "server_time":"2026-11-09T06:00:00Z"}""")
        engine.reply(200, """{"journal":[{"category":"watched","item_type":"movie","status":"added","ids":{"imdb":"tt0133093"},
            "action_at":"2026-11-09T05:00:00Z"}]}""")
        engine.reply(200, watchlist(shows = ""))
        assertTrue(sync.refresh(1, TrackingRefreshIntent.AUTOMATIC))
        assertEquals(listOf("/sync/last_activities", "/sync/journal", "/watchlist/items"), engine.calls.map { it.path })
        val snapshot = store.get(1)!!
        assertEquals(listOf("tt0133093"), snapshot.marks.map { it.media.contentId })
        assertEquals(listOf("tt0133093"), snapshot.watchlist!!.map { it.media.contentId })
        assertEquals("2026-11-09T06:00:00Z", snapshot.journalSince)
    }

    @Test
    fun `a transfer read syncs first and lists the watchlist without this TV's changes`() = runTest(UnconfinedTestDispatcher()) {
        connected()
        val library = library()
        firstSync(watchlist())
        engine.reply(200, """{"added":1}""")
        library.setInLibrary(1, item("tt5"), inLibrary = true)
        // This TV's write makes the read pull the watchlist again; MDBList has not shown it yet.
        engine.reply(200, activities())
        engine.reply(200, watchlist())
        engine.calls.clear()
        val read = library.readLibrary(1)
        assertEquals(listOf("/sync/last_activities", "/watchlist/items"), engine.calls.map { it.path })
        assertEquals(listOf("tt0133093", "tt20782190"), read.map { it.entry.id })
        assertEquals(setOf("tt0133093", "tmdb:603"), read[0].externalIds)
    }

    @Test
    fun `a transfer read with no key fails instead of listing nothing`() = runTest(UnconfinedTestDispatcher()) {
        val library = library()
        failsWith<MdbListException> { library.readLibrary(1) }
    }

    @Test
    fun `a library refresh right after a settled sync sends nothing, a later one runs the sync`() = runTest(UnconfinedTestDispatcher()) {
        connected()
        val library = library()
        // A slow first sync (20 s a call): the skip counts from its end, not its start.
        engine.onCall = { clock.now += 20_000L }
        firstSync(watchlist())
        engine.onCall = {}
        library.refresh(TrackingRefreshIntent.AUTOMATIC)
        assertTrue(refreshes.isEmpty())
        clock.now += 31_000L
        engine.reply(200, activities())
        library.refresh(TrackingRefreshIntent.AUTOMATIC)
        assertEquals(listOf(TrackingRefreshIntent.AUTOMATIC), refreshes)
    }

    @Test
    fun `added dates in ISO, MDBList's spaced form and a bare date are read, anything else is none`() {
        assertEquals(parseMdbListTime("2026-01-02T03:04:05Z"), parseMdbListListedAt("2026-01-02T03:04:05Z"))
        assertEquals(parseMdbListTime("2025-02-02T17:28:22Z"), parseMdbListListedAt("2025-02-02 17:28:22.000000"))
        assertEquals(parseMdbListTime("2025-02-02T00:00:00Z"), parseMdbListListedAt("2025-02-02"))
        assertNull(parseMdbListListedAt("not a date"))
        assertNull(parseMdbListListedAt(null))
    }

    @Test
    fun `rows naming no IMDb or TMDB id are left out, an odd poster path is none`() {
        val row = mdbListJson.decodeFromString(MdbListWatchlistRow.serializer(), """{"tvdb_id":5,"title":"x"}""")
        assertNull(row.toWatchlistItem(isMovie = false))
        assertNull(mdbListImageUrl("//evil.example/p.jpg", "w500"))
        assertNull(mdbListImageUrl("http://plain.example/p.jpg", "w500"))
    }
}
