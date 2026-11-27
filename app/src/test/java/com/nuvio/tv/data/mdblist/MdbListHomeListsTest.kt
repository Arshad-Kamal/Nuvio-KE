package com.nuvio.tv.data.mdblist

import com.nuvio.tv.core.tracking.TrackingAccount
import com.nuvio.tv.core.tracking.TrackingProviderId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 105 S5a: the account's lists for Home rows, their pages, and what is kept to spare requests. */
class MdbListHomeListsTest {

    private val engine = FakeMdbListEngine().apply { watchlistDefault = null }
    private val clock = FakeClock()
    private val accounts = accountStore()
    private val session = MdbListSession(mdbListClient(engine, clock), accounts, MdbListQuota { clock.now })
    private val lists = MdbListHomeLists(session) { clock.now }

    private suspend fun connected(key: String = "abc123def456ghi789") =
        accounts.save(1, TrackingProviderId.MDBLIST, TrackingAccount(accessToken = key, accountName = "arshadk"))

    private fun index(own: String = OWN, liked: String = LIKED_ONE_PAGE, external: String = EXTERNAL) {
        engine.replyTo(MdbListHttpMethod.GET, "/lists/user", 200, own)
        engine.replyTo(MdbListHttpMethod.GET, "/lists/liked", 200, liked)
        engine.replyTo(MdbListHttpMethod.GET, "/external/lists/user", 200, external)
    }

    @Test
    fun `the index lists own, liked and external lists once each, skipping rows without an id or name`() = runTest {
        connected()
        index()
        val read = lists.lists(1)
        assertEquals(listOf("mdblist_11", "mdblist_12", "mdblist_21", "mdblist_ext_11"), read.map { it.key })
        assertEquals(listOf("Weekend", "Anime", "Top 250", "Letterboxd 2024"), read.map { it.name })
        assertEquals(MdbListHomeListKind.LIKED, read[2].kind)
        assertEquals(3, engine.calls.size)
    }

    @Test
    fun `liked lists follow has_more pages`() = runTest {
        connected()
        index(liked = """{"lists":[{"id":21,"name":"Top 250","items":250}],"pagination":{"has_more":true}}""")
        engine.replyTo(MdbListHttpMethod.GET, "/lists/liked", 200, """{"lists":[{"id":22,"name":"Noir","items":40}],"pagination":{"has_more":false}}""")
        assertEquals(listOf("mdblist_21", "mdblist_22"), lists.lists(1).filter { it.kind == MdbListHomeListKind.LIKED }.map { it.key })
        assertEquals(listOf("0", "100"), engine.calls.filter { it.path == "/lists/liked" }.map { it.query("offset") })
    }

    @Test
    fun `the index is read again only after its floor, and a failure serves the last one`() = runTest {
        connected()
        index()
        lists.lists(1)
        clock.now += MdbListHomeLists.INDEX_FLOOR_MS - 1
        lists.lists(1)
        assertEquals(3, engine.calls.size)
        clock.now += 1
        engine.replyTo(MdbListHttpMethod.GET, "/lists/user", 404, "{}")
        assertEquals(4, lists.lists(1).size)
        assertEquals(4, engine.calls.size)
    }

    @Test
    fun `while paused the last index and first pages are served, never fetched`() = runTest {
        connected()
        index()
        val first = lists.lists(1).first()
        engine.replyTo(MdbListHttpMethod.GET, "/lists/11/items", 200, PAGE)
        lists.firstPage(1, first)
        clock.now += MdbListHomeLists.INDEX_FLOOR_MS
        engine.replyTo(MdbListHttpMethod.GET, "/lists/user", 429, DAILY_LIMIT_BODY) // the day's limit: paused from here
        assertEquals(4, lists.lists(1).size)
        val sent = engine.calls.size
        assertEquals(3, lists.firstPage(1, first.copy(itemCount = 99)).items.size)
        assertEquals(4, lists.lists(1).size)
        assertEquals(sent, engine.calls.size)
    }

    @Test
    fun `a new key starts over and a disconnected profile reads nothing`() = runTest {
        failsWith<MdbListSignedOutException> { lists.lists(1) }
        connected()
        index()
        lists.lists(1)
        connected(key = "zzz999yyy888xxx777")
        index()
        lists.lists(1)
        assertEquals(6, engine.calls.size)
    }

    @Test
    fun `a first page is reused while its list keeps its count and is fresh`() = runTest {
        connected()
        val list = MdbListHomeList(MdbListHomeListKind.OWN, 11, "Weekend", 3)
        repeat(3) { engine.replyTo(MdbListHttpMethod.GET, "/lists/11/items", 200, PAGE) }
        lists.firstPage(1, list)
        lists.firstPage(1, list)
        assertEquals(1, engine.calls.size)
        lists.firstPage(1, list.copy(itemCount = 4))
        assertEquals(2, engine.calls.size)
        clock.now += MdbListHomeLists.FIRST_PAGE_TTL_MS
        lists.firstPage(1, list.copy(itemCount = 4))
        assertEquals(3, engine.calls.size)
        val call = engine.calls.first()
        assertEquals("100", call.query("limit"))
        assertEquals("poster,genres", call.query("append_to_response"))
        assertNull(call.query("cursor"))
    }

    @Test
    fun `a page keeps list order across films and shows and drops rows Nuvio cannot name`() = runTest {
        connected()
        engine.replyTo(MdbListHttpMethod.GET, "/external/lists/11/items", 200, PAGE)
        val page = lists.firstPage(1, MdbListHomeList(MdbListHomeListKind.EXTERNAL, 11, "Letterboxd 2024", 4))
        assertEquals(listOf("tt0133093", "tt0903747", "tmdb:603"), page.items.map { it.media.contentId })
        assertEquals("/m.jpg", page.items[0].poster)
        assertEquals("c2", page.nextCursor)
    }

    @Test
    fun `a next page keeps only new titles and ends when none is new or the cap is reached`() = runTest {
        connected()
        val list = MdbListHomeList(MdbListHomeListKind.OWN, 11, "Weekend", 9_000)
        engine.replyTo(MdbListHttpMethod.GET, "/lists/11/items", 200, PAGE)
        val more = lists.nextPage(1, list, "c1", seen = setOf("movie:tt0133093"))
        assertEquals(listOf("tt0903747", "tmdb:603"), more.items.map { it.media.contentId })
        assertEquals("c2", more.nextCursor)
        assertEquals("c1", engine.calls.single().query("cursor"))

        engine.replyTo(MdbListHttpMethod.GET, "/lists/11/items", 200, PAGE)
        val stale = lists.nextPage(1, list, "c1", seen = setOf("movie:tt0133093", "show:tt0903747", "movie:tmdb:603"))
        assertTrue(stale.items.isEmpty())
        assertNull(stale.nextCursor)

        engine.replyTo(MdbListHttpMethod.GET, "/lists/11/items", 200, PAGE)
        val seen = (1..MdbListHomeLists.MAX_TITLES - 1).map { "tmdb:${it + 1_000_000}" }.toSet()
        val last = lists.nextPage(1, list, "c1", seen = seen)
        assertEquals(1, last.items.size)
        assertNull(last.nextCursor)
    }

    @Test
    fun `after a failure nothing is asked again for five minutes`() = runTest {
        connected()
        engine.replyTo(MdbListHttpMethod.GET, "/lists/user", 404, "{}")
        failsWith<MdbListApiException> { lists.lists(1) }
        failsWith<MdbListApiException> { lists.lists(1) } // the same failure again, nothing sent
        assertEquals(1, engine.calls.size)
        clock.now += MdbListHomeLists.RETRY_MS
        index()
        assertEquals(4, lists.lists(1).size)

        val list = MdbListHomeList(MdbListHomeListKind.OWN, 11, "Weekend", 3)
        engine.replyTo(MdbListHttpMethod.GET, "/lists/11/items", 404, "{}")
        failsWith<MdbListApiException> { lists.firstPage(1, list) }
        val sent = engine.calls.size
        failsWith<MdbListApiException> { lists.firstPage(1, list) }
        assertEquals(sent, engine.calls.size)
    }

    @Test
    fun `a re-read whose liked lists fail keeps the liked lists held before`() = runTest {
        connected()
        index()
        lists.lists(1)
        clock.now += MdbListHomeLists.INDEX_FLOOR_MS
        engine.replyTo(MdbListHttpMethod.GET, "/lists/user", 200, OWN)
        engine.replyTo(MdbListHttpMethod.GET, "/lists/liked", 404, "{}")
        engine.replyTo(MdbListHttpMethod.GET, "/external/lists/user", 200, EXTERNAL)
        assertEquals(listOf("mdblist_11", "mdblist_12", "mdblist_21", "mdblist_ext_11"), lists.lists(1).map { it.key })
    }

    @Test
    fun `a full re-read drops a list that is gone`() = runTest {
        connected()
        index()
        lists.lists(1)
        clock.now += MdbListHomeLists.INDEX_FLOOR_MS
        index(liked = """{"lists":[],"pagination":{"has_more":false}}""")
        assertEquals(listOf("mdblist_11", "mdblist_12", "mdblist_ext_11"), lists.lists(1).map { it.key })
    }

    @Test
    fun `a next page read while the key changed is dropped, and a signed-out profile reads none`() = runTest {
        val list = MdbListHomeList(MdbListHomeListKind.OWN, 11, "Weekend", 9_000)
        failsWith<MdbListSignedOutException> { lists.nextPage(1, list, "c1", emptySet()) }
        connected()
        engine.replyTo(MdbListHttpMethod.GET, "/lists/11/items", 200, PAGE)
        engine.onCall = {
            engine.onCall = {}
            connected(key = "zzz999yyy888xxx777")
        }
        failsWith<MdbListSignedOutException> { lists.nextPage(1, list, "c1", emptySet()) }
    }

    @Test
    fun `the daily limit on an optional list call fails the whole index`() = runTest {
        connected()
        engine.replyTo(MdbListHttpMethod.GET, "/lists/user", 200, OWN)
        engine.replyTo(MdbListHttpMethod.GET, "/lists/liked", 200, LIKED_ONE_PAGE)
        engine.replyTo(MdbListHttpMethod.GET, "/external/lists/user", 429, DAILY_LIMIT_BODY)
        failsWith<MdbListApiException> { lists.lists(1) }
    }

    @Test
    fun `a first page whose count changed during a failure wait is served old, asked again only after the wait`() = runTest {
        connected()
        val list = MdbListHomeList(MdbListHomeListKind.OWN, 11, "Weekend", 3)
        engine.replyTo(MdbListHttpMethod.GET, "/lists/11/items", 200, PAGE)
        lists.firstPage(1, list)
        engine.replyTo(MdbListHttpMethod.GET, "/lists/11/items", 404, "{}")
        assertEquals(3, lists.firstPage(1, list.copy(itemCount = 4)).items.size)
        assertEquals(3, lists.firstPage(1, list.copy(itemCount = 4)).items.size)
        assertEquals(2, engine.calls.size)
        clock.now += MdbListHomeLists.RETRY_MS
        engine.replyTo(MdbListHttpMethod.GET, "/lists/11/items", 200, PAGE)
        lists.firstPage(1, list.copy(itemCount = 4))
        assertEquals(3, engine.calls.size)
    }

    @Test
    fun `a clock set back makes a kept index due`() = runTest {
        connected()
        index()
        lists.lists(1)
        clock.now -= 60 * 60 * 1_000
        index()
        lists.lists(1)
        assertEquals(6, engine.calls.size)
    }

    @Test
    fun `liked or external lists failing leave the own lists, read whole again after five minutes`() = runTest {
        connected()
        engine.replyTo(MdbListHttpMethod.GET, "/lists/user", 200, OWN)
        engine.replyTo(MdbListHttpMethod.GET, "/lists/liked", 404, "{}")
        engine.replyTo(MdbListHttpMethod.GET, "/external/lists/user", 200, EXTERNAL)
        assertEquals(listOf("mdblist_11", "mdblist_12", "mdblist_ext_11"), lists.lists(1).map { it.key })
        clock.now += MdbListHomeLists.RETRY_MS - 1
        lists.lists(1)
        assertEquals(3, engine.calls.size)
        clock.now += 1
        index()
        assertEquals(4, lists.lists(1).size)
    }

    @Test
    fun `an answer read while the key changed is dropped`() = runTest {
        connected()
        index()
        engine.onCall = {
            engine.onCall = {}
            connected(key = "zzz999yyy888xxx777")
        }
        failsWith<MdbListSignedOutException> { lists.lists(1) }
        index()
        assertEquals(4, lists.lists(1).size)
    }

    @Test
    fun `liked lists stop after five pages`() = runTest {
        connected()
        engine.replyTo(MdbListHttpMethod.GET, "/lists/user", 200, "[]")
        repeat(6) { n ->
            engine.replyTo(MdbListHttpMethod.GET, "/lists/liked", 200, """{"lists":[{"id":${n + 30},"name":"L$n"}],"pagination":{"has_more":true}}""")
        }
        engine.replyTo(MdbListHttpMethod.GET, "/external/lists/user", 200, "[]")
        assertEquals(MdbListHomeLists.MAX_LIKED_PAGES, lists.lists(1).size)
    }

    @Test
    fun `an echoed cursor ends the list and the header cursor is read when the body has none`() = runTest {
        connected()
        val list = MdbListHomeList(MdbListHomeListKind.OWN, 11, "Weekend", 9)
        engine.reply(200, """{"movies":[{"id":1,"title":"A"}],"pagination":{"next_cursor":"c1"}}""")
        assertNull(lists.nextPage(1, list, "c1", seen = emptySet()).nextCursor)
        engine.reply(200, """{"movies":[{"id":2,"title":"B"}]}""", mapOf("X-Next-Cursor" to "c9"))
        assertEquals("c9", lists.nextPage(1, list, "c1", seen = emptySet()).nextCursor)
    }

    @Test
    fun `a TMDB-only film and show with the same number are two titles, unranked rows follow ranked ones`() = runTest {
        connected()
        engine.replyTo(
            MdbListHttpMethod.GET, "/lists/11/items", 200,
            """{"movies":[{"id":7,"title":"Film"},{"id":8,"rank":2,"title":"Ranked"}],"shows":[{"id":7,"rank":1,"title":"Show"}]}"""
        )
        val page = lists.firstPage(1, MdbListHomeList(MdbListHomeListKind.OWN, 11, "Weekend", 3))
        assertEquals(listOf("show:tmdb:7", "movie:tmdb:8", "movie:tmdb:7"), page.items.map { it.homeKey })
    }

    @Test
    fun `an own list is dynamic by either field, a liked or imported one never`() {
        val own = parseMdbListHomeLists(
            """[{"id":1,"name":"A","dynamic":true},{"id":2,"name":"B","type":"dynamic"},
                {"id":3,"name":"C","type":"static","dynamic":false},{"id":4,"name":"D"}]""",
            MdbListHomeListKind.OWN
        )
        assertEquals(listOf(true, true, false, false), own.map { it.dynamic })
        val liked = parseMdbListLikedPage("""{"lists":[{"id":5,"name":"E","dynamic":true,"type":"dynamic"}]}""")
        assertEquals(listOf(false), liked.lists.map { it.dynamic })
        val external = parseMdbListHomeLists("""[{"id":6,"name":"F","dynamic":true}]""", MdbListHomeListKind.EXTERNAL)
        assertEquals(listOf(false), external.map { it.dynamic })
    }

    @Test
    fun `a reply of the wrong shape is a fault`() = runTest {
        failsWith<MdbListException> { parseMdbListHomeLists("""{"lists":[]}""", MdbListHomeListKind.OWN) }
        failsWith<MdbListException> { parseMdbListLikedPage("[]") }
    }

    private companion object {
        const val OWN = """[{"id":11,"name":"Weekend","items":3},{"id":12,"name":"Anime","items":80},
            {"id":0,"name":"Bad id"},{"id":13,"name":"  "},"junk"]"""
        const val LIKED_ONE_PAGE = """{"lists":[{"id":21,"name":"Top 250","items":250},{"id":11,"name":"Weekend","items":3}],
            "pagination":{"has_more":false}}"""
        const val EXTERNAL = """[{"id":11,"name":"Letterboxd 2024","source":"letterboxd","items":40}]"""
        const val PAGE = """{"movies":[
            {"id":603,"rank":3,"title":"The Matrix Reloaded","release_year":2003},
            {"id":604,"rank":1,"title":"The Matrix","imdb_id":"tt0133093","release_year":1999,"poster":"/m.jpg"},
            {"rank":4,"title":"No ids"}],
          "shows":[{"id":1396,"rank":2,"title":"Breaking Bad","imdb_id":"tt0903747"}],
          "pagination":{"next_cursor":"c2"}}"""
    }
}
