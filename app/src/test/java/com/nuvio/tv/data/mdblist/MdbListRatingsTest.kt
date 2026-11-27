package com.nuvio.tv.data.mdblist

import com.nuvio.tv.core.tracking.TrackingAccount
import com.nuvio.tv.core.tracking.TrackingProviderId
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Spec 105 S4: one title's MDBList scores, parsed as upstream reads them, and the app-wide 12 h cache. */
class MdbListRatingsTest {
    @get:Rule val folder = TemporaryFolder()

    private val engine = FakeMdbListEngine().apply { watchlistDefault = null }
    private val clock = FakeClock()
    private val accounts = accountStore()
    private val session = MdbListSession(mdbListClient(engine, clock), accounts, MdbListQuota { clock.now })
    private val file get() = File(folder.root, "mdblist_sync/ratings.json")

    private fun TestScope.cache() = MdbListRatingsCache(file, Dispatchers.Unconfined, backgroundScope) { clock.now }

    private suspend fun connected() =
        accounts.save(1, TrackingProviderId.MDBLIST, TrackingAccount(accessToken = "abc123def456ghi789", accountName = "arshadk"))

    private val matrix = """{"title":"The Matrix","ratings":[
        {"source":"imdb","value":8.7,"score":87},
        {"source":"metacritic","value":73,"score":73},
        {"source":"trakt","value":85,"score":85},
        {"source":"tomatoes","value":83,"score":83},
        {"source":"popcorn","value":85,"score":85},
        {"source":"tmdb","value":82,"score":82},
        {"source":"letterboxd","value":4.2,"score":84},
        {"source":"rogerebert","value":3.5,"score":70},
        {"source":"myanimelist","value":null,"score":null},
        {"source":"imdb","value":1.0}
    ]}"""

    @Test
    fun `scores read every known source once, letterboxd from its score, and leave out unknown or empty ones`() {
        val scores = parseMdbListScores(matrix)!!.values
        assertEquals(8.7, scores[MdbListRatingSource.IMDB]!!, 0.0)
        assertEquals(4.2, scores[MdbListRatingSource.LETTERBOXD]!!, 0.0001)
        assertEquals(85.0, scores[MdbListRatingSource.POPCORN]!!, 0.0)
        assertEquals(7, scores.size)
        assertNull(scores[MdbListRatingSource.MAL])
    }

    @Test
    fun `out-of-range values are dropped and a reply without ratings is none`() {
        val scores = parseMdbListScores("""{"ratings":[{"source":"imdb","value":87},{"source":"tomatoes","value":-1},
            {"source":"letterboxd","value":4.5},{"source":"audience","value":"x"}]}""")!!.values
        assertEquals(mapOf(MdbListRatingSource.LETTERBOXD to 4.5), scores)
        assertNull(parseMdbListScores("""{"title":"x"}"""))
        assertNull(parseMdbListScores("[]"))
        assertNull(parseMdbListScores("not json"))
    }

    @Test
    fun `one call per title by IMDb id, then the cache serves it until it is 12 h old`() = runTest {
        connected()
        val ratings = MdbListRatings(session, cache())
        engine.reply(200, matrix)
        assertEquals(7, ratings.scores(1, "tt0133093", "movie")!!.values.size)
        assertEquals("/imdb/movie/tt0133093/", engine.calls.single().path)
        assertEquals(7, ratings.scores(1, "tt0133093", "movie")!!.values.size)
        assertEquals(1, engine.calls.size)
        clock.now += MdbListRatingsCache.TTL_MS - 1
        ratings.scores(1, "tt0133093", "movie")
        assertEquals(1, engine.calls.size)
        clock.now += 1
        engine.reply(200, matrix)
        ratings.scores(1, "tt0133093", "movie")
        assertEquals(2, engine.calls.size)
    }

    @Test
    fun `a TMDB-only show asks by TMDB id, an unplaceable title asks nothing`() = runTest {
        connected()
        val ratings = MdbListRatings(session, cache())
        engine.reply(200, """{"ratings":[{"source":"tmdb","value":88}]}""")
        assertEquals(88.0, ratings.scores(1, "tmdb:1396", "series")!!.values[MdbListRatingSource.TMDB]!!, 0.0)
        assertEquals("/tmdb/show/1396/", engine.calls.single().path)
        assertNull(ratings.scores(1, "kitsu:12", "series"))
        assertEquals(1, engine.calls.size)
    }

    @Test
    fun `an addon id that is not a plain IMDb id never reaches the request path`() = runTest {
        connected()
        val ratings = MdbListRatings(session, cache())
        assertNull(ratings.scores(1, "tt1/../../lists/user", "movie"))
        assertNull(ratings.scores(1, "tt12ab", "movie"))
        assertTrue(engine.calls.isEmpty())
    }

    @Test
    fun `a failed call is not cached`() = runTest {
        connected()
        val ratings = MdbListRatings(session, cache())
        engine.fail()
        failsWith<Throwable> { ratings.scores(1, "tt0133093", "movie") }
        val failedCalls = engine.calls.size
        engine.reply(200, matrix)
        assertEquals(7, ratings.scores(1, "tt0133093", "movie")!!.values.size)
        assertEquals(failedCalls + 1, engine.calls.size)
    }

    @Test
    fun `a save keeps the newest MAX_ENTRIES and drops expired entries, in memory and on disk`() = runTest {
        val cache = cache()
        val scores = parseMdbListScores(matrix)!!
        cache.put("expired", scores)
        clock.now += MdbListRatingsCache.TTL_MS
        repeat(MdbListRatingsCache.MAX_ENTRIES + 1) { i ->
            clock.now += 1
            cache.put("k$i", scores)
        }
        advanceTimeBy(MdbListRatingsCache.SAVE_DELAY_MS + 1)
        runCurrent()
        val again = MdbListRatingsCache(file, Dispatchers.Unconfined, backgroundScope) { clock.now }
        assertNull(again.get("k0"))
        assertNull(again.get("expired"))
        assertEquals(7, again.get("k${MdbListRatingsCache.MAX_ENTRIES}")!!.values.size)
        assertEquals(7, again.get("k1")!!.values.size)
        // The live cache dropped the oldest too (memory trimmed with the file).
        assertNull(cache.get("k0"))
    }

    @Test
    fun `an answer with no score is not cached`() = runTest {
        connected()
        val ratings = MdbListRatings(session, cache())
        engine.reply(200, """{"ratings":[]}""")
        assertNull(ratings.scores(1, "tt0133093", "movie"))
        engine.reply(200, """{"ratings":[]}""")
        assertNull(ratings.scores(1, "tt0133093", "movie"))
        assertEquals(2, engine.calls.size)
    }

    @Test
    fun `the cache survives a restart through its file`() = runTest {
        val first = cache()
        first.put("movie:tt0133093", parseMdbListScores(matrix)!!)
        advanceTimeBy(MdbListRatingsCache.SAVE_DELAY_MS + 1)
        runCurrent()
        assertTrue(file.isFile)
        val again = MdbListRatingsCache(file, Dispatchers.Unconfined, backgroundScope) { clock.now }
        assertEquals(7, again.get("movie:tt0133093")!!.values.size)
    }

    @Test
    fun `an unreadable cache file starts empty and is replaced on the next save`() = runTest {
        file.parentFile!!.mkdirs()
        file.writeText("{not json")
        val cache = cache()
        assertNull(cache.get("movie:tt0133093"))
        cache.put("movie:tt0133093", parseMdbListScores(matrix)!!)
        advanceTimeBy(MdbListRatingsCache.SAVE_DELAY_MS + 1)
        runCurrent()
        val again = MdbListRatingsCache(file, Dispatchers.Unconfined, backgroundScope) { clock.now }
        assertEquals(7, again.get("movie:tt0133093")!!.values.size)
    }

    @Test
    fun `while paused at the daily limit nothing is sent`() = runTest {
        connected()
        val ratings = MdbListRatings(session, cache())
        engine.reply(429, DAILY_LIMIT_BODY)
        failsWith<MdbListApiException> { ratings.scores(1, "tt0133093", "movie") }
        failsWith<MdbListQuotaExceededException> { ratings.scores(1, "tt0111161", "movie") }
        assertEquals(1, engine.calls.size)
    }

    @Test
    fun `while paused cached scores are still served`() = runTest {
        connected()
        val ratings = MdbListRatings(session, cache())
        engine.reply(200, matrix)
        ratings.scores(1, "tt0133093", "movie")
        engine.reply(429, DAILY_LIMIT_BODY)
        failsWith<MdbListApiException> { ratings.scores(1, "tt0111161", "movie") }
        assertEquals(7, ratings.scores(1, "tt0133093", "movie")!!.values.size)
        assertEquals(2, engine.calls.size)
    }

    @Test
    fun `ratings count as connected only with a stored key that is not revoked`() = runTest {
        assertFalse(accounts.mdbListConnected().first())
        connected()
        assertTrue(accounts.mdbListConnected().first())
        accounts.save(1, TrackingProviderId.MDBLIST, TrackingAccount(accessToken = "abc123def456ghi789", revoked = true))
        assertFalse(accounts.mdbListConnected().first())
    }
}
