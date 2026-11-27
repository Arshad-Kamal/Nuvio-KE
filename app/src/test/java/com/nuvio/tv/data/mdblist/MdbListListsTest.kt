package com.nuvio.tv.data.mdblist

import com.nuvio.tv.core.tracking.TrackingAccount
import com.nuvio.tv.core.tracking.TrackingProviderId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 105 S3b: the account's own static lists, membership by call, and list writes. */
class MdbListListsTest {
    private val engine = FakeMdbListEngine().apply { watchlistDefault = null }
    private val clock = FakeClock()
    private val accounts = accountStore()
    private val quota = MdbListQuota { clock.now }
    private val lists = MdbListLists(MdbListSession(mdbListClient(engine, clock), accounts, quota))

    private suspend fun connected() =
        accounts.save(1, TrackingProviderId.MDBLIST, TrackingAccount(accessToken = "abc123def456ghi789", accountName = "arshadk"))

    private fun SentCall.json(): JsonObject = Json.parseToJsonElement(body!!).jsonObject

    @Test
    fun `own lists keep static ones with a name and id, and read which types each takes`() = runTest {
        connected()
        engine.reply(200, """[
            {"id":11,"name":"Sunday night films","type":"static","dynamic":false,"mediatype":"movie","items":41},
            {"id":12,"name":"Blender shorts","type":"static","items":12},
            {"id":13,"name":"Top rated 2024","type":"dynamic","items":500},
            {"id":14,"name":"Flagged","type":"static","dynamic":true},
            {"id":15,"name":"Shows to finish","type":"static","mediatype":"shows","items":"7"},
            {"id":16,"type":"static"},
            {"id":-1,"name":"Bad id","type":"static"},
            {"id":17,"name":"No type"},
            {"id":11,"name":"Twice","type":"static"},
            {"id":18,"name":"Episodes","type":"static","mediatype":"episode"}
        ]""")
        val owned = lists.ownedLists(1)
        assertEquals(listOf(11L, 12L, 15L, 18L), owned.map { it.id })
        assertEquals(listOf(41, 12, 7, 0), owned.map { it.itemCount })
        assertEquals(listOf(true, true, false, false), owned.map { it.takes(isMovie = true) })
        assertEquals(listOf(false, true, true, false), owned.map { it.takes(isMovie = false) })
        val call = engine.calls.single()
        assertEquals(MdbListHttpMethod.GET, call.method)
        assertEquals("/lists/user", call.path)
        assertNull(call.query("include_shared"))
    }

    @Test
    fun `an own-lists reply that is not an array is a fault`() = runTest {
        connected()
        engine.reply(200, """{"lists":[]}""")
        failsWith<MdbListException> { lists.ownedLists(1) }
    }

    @Test
    fun `membership asks by type and every id the title has, and reads contains`() = runTest {
        connected()
        engine.reply(200, """{"contains":[11,15,0,"x"]}""")
        assertEquals(setOf(11L, 15L), lists.membership(1, "tt0133093", "movie"))
        engine.reply(200, """{"contains":[]}""")
        assertEquals(emptySet<Long>(), lists.membership(1, "tmdb:1396", "series"))
        val (film, show) = engine.calls
        assertEquals("/lists/user/membership", film.path)
        assertEquals("movie", film.query("mediatype"))
        assertEquals("tt0133093", film.query("imdb"))
        assertEquals("show", show.query("mediatype"))
        assertEquals("1396", show.query("tmdb"))
        assertNull(show.query("imdb"))
    }

    @Test
    fun `a membership reply without contains is a fault, never read as on no list`() = runTest {
        connected()
        engine.reply(200, "{}")
        failsWith<MdbListException> { lists.membership(1, "tt0133093", "movie") }
    }

    @Test
    fun `a title MDBList cannot place is refused unsent`() = runTest {
        connected()
        failsWith<MdbListUnmatchableException> { lists.membership(1, "kitsu:123", "series") }
        failsWith<MdbListUnmatchableException> { lists.setOnList(1, 11, "kitsu:123", "series", add = true) }
        assertTrue(engine.calls.isEmpty())
    }

    @Test
    fun `an add posts the title's flat ids to the list`() = runTest {
        connected()
        engine.reply(200, """{"added":{"movies":1,"shows":0},"existing":{"movies":0,"shows":0},"not_found":{"movies":0,"shows":0}}""")
        lists.setOnList(1, 11, "tt0133093", "movie", add = true)
        val call = engine.calls.single()
        assertEquals(MdbListHttpMethod.POST, call.method)
        assertEquals("/lists/11/items/add", call.path)
        assertEquals("""{"movies":[{"imdb":"tt0133093"}]}""", call.json().toString())
    }

    @Test
    fun `an add MDBList could not place throws, a removal of a title not on the list is done`() = runTest {
        connected()
        engine.reply(200, """{"added":{"shows":0},"not_found":{"shows":1}}""")
        failsWith<MdbListUnmatchableException> { lists.setOnList(1, 12, "tmdb:1396", "series", add = true) }
        engine.reply(200, """{"removed":{"shows":0},"not_found":{"shows":1}}""")
        lists.setOnList(1, 12, "tmdb:1396", "series", add = false)
        val removal = engine.calls.last()
        assertEquals("/lists/12/items/remove", removal.path)
        assertEquals("""{"shows":[{"tmdb":1396}]}""", removal.json().toString())
    }

    @Test
    fun `while paused at the daily limit nothing is sent`() = runTest {
        connected()
        engine.reply(429, DAILY_LIMIT_BODY)
        failsWith<MdbListApiException> { lists.ownedLists(1) }
        engine.calls.clear()
        failsWith<MdbListQuotaExceededException> { lists.membership(1, "tt0133093", "movie") }
        failsWith<MdbListQuotaExceededException> { lists.setOnList(1, 11, "tt0133093", "movie", add = false) }
        assertTrue(engine.calls.isEmpty())
    }

    @Test
    fun `the last lists read stay for the panel while paused, only under the same key`() = runTest {
        connected()
        assertNull(lists.cachedOwnedLists(1))
        engine.reply(200, """[{"id":11,"name":"Sunday night films","type":"static","items":41}]""")
        lists.ownedLists(1)
        assertEquals(listOf(11L), lists.cachedOwnedLists(1)!!.map { it.id })
        accounts.save(1, TrackingProviderId.MDBLIST, TrackingAccount(accessToken = "zzz999yyy888xxx777", accountName = "other"))
        assertNull(lists.cachedOwnedLists(1))
    }

    @Test
    fun `signed out, no call goes`() = runTest {
        failsWith<MdbListSignedOutException> { lists.ownedLists(1) }
        assertFalse(engine.calls.isNotEmpty())
    }
}
