package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingAccount
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Spec 106 S2a: the activities-gated sync loop. */
class SimklSyncTest {
    @get:Rule val folder = TemporaryFolder()

    private val engine = FakeSimklEngine()
    private val clock = FakeClock()
    private val client = simklClient(engine, clock)
    private val accounts = accountStore()
    private val tokens = SimklTokens(client, TEST_CONFIG, accounts) { clock.now }
    private val quota = SimklQuota { clock.now }
    private val session = SimklSession(client, tokens, quota)
    private val store by lazy { SimklSyncStore(folder.root, Dispatchers.Unconfined) }
    private val sync by lazy { SimklSync(session, store, accounts) { clock.now } }

    private val day = 24L * 60 * 60 * 1_000

    private suspend fun signedIn(lastSynced: Long? = null) = accounts.save(
        1, TrackingProviderId.SIMKL, TrackingAccount("at", "rt", clock.now + 7 * day, "arshad.k", lastSyncedAtEpochMs = lastSynced)
    )

    private fun activities(all: String, watching: String = "w1", removed: String = "r1", playback: String = "p1") =
        """{"all":"$all","tv_shows":{"all":"$all","watching":"$watching","removed_from_list":"$removed","playback":"$playback"},
            "movies":{},"anime":{}}"""

    private fun show(id: Long, imdb: String, status: String = "watching") =
        """{"status":"$status","show":{"title":"Show $id","ids":{"simkl":$id,"imdb":"$imdb"}}}"""

    /** Runs a full pull with one show and one playback. */
    private suspend fun initialSync() {
        engine.reply(200, activities("2026-11-01T00:00:00Z"))
        engine.reply(200, """{"shows":[${show(1, "tt1")}, ${show(2, "tt2")}]}""")
        engine.reply(200, "{}")
        engine.reply(200, "null")
        engine.reply(200, """[{"id":9,"progress":40,"paused_at":"2026-11-01T00:00:00Z","episode":{"season":1,"number":2},
            "show":{"title":"Show 1","ids":{"simkl":1,"imdb":"tt1"}}}]""")
        assertTrue(sync.refresh(1, TrackingRefreshIntent.USER_INITIATED))
        engine.calls.clear()
    }

    @Test
    fun `a first sync reads activities first, pulls each type in full, then playbacks`() = runTest {
        signedIn()
        initialSync()
        val snapshot = store.get(1)!!
        assertEquals("2026-11-01T00:00:00Z", snapshot.activities!!.all)
        assertEquals(listOf(1L, 2L), snapshot.entries.map { it.media!!.simklId })
        assertEquals(9L, snapshot.playback.single().id)
        assertEquals(clock.now, accounts.get(1, TrackingProviderId.SIMKL)!!.lastSyncedAtEpochMs)
    }

    @Test
    fun `the first sync's calls go in Simkl's order with episode data`() = runTest {
        signedIn()
        engine.reply(200, activities("a"))
        repeat(3) { engine.reply(200, "{}") }
        engine.reply(200, "[]")
        sync.refresh(1, TrackingRefreshIntent.USER_INITIATED)
        assertEquals(
            listOf("/sync/activities", "/sync/all-items/shows", "/sync/all-items/movies", "/sync/all-items/anime", "/sync/playback"),
            engine.calls.map { it.path }
        )
        assertEquals("full_anime_seasons", engine.calls[1].query("extended"))
        assertEquals("yes", engine.calls[1].query("episode_watched_at"))
        assertEquals("yes", engine.calls[1].query("include_all_episodes"))
    }

    @Test
    fun `nothing moved costs one call and keeps the snapshot`() = runTest {
        signedIn()
        initialSync()
        val before = store.get(1)
        engine.reply(200, activities("2026-11-01T00:00:00Z"))
        assertTrue(sync.refresh(1, TrackingRefreshIntent.USER_INITIATED))
        assertEquals(listOf("/sync/activities"), engine.calls.map { it.path })
        assertSame(before, store.get(1))
    }

    @Test
    fun `a moved list fetches the delta from the saved stamp and replaces by Simkl id`() = runTest {
        signedIn()
        initialSync()
        engine.reply(200, activities("2026-11-02T00:00:00Z", watching = "w2"))
        engine.reply(200, """{"anime":[{"status":"completed","show":{"title":"Moved","ids":{"simkl":2,"mal":5}}}]}""")
        assertTrue(sync.refresh(1, TrackingRefreshIntent.USER_INITIATED))
        val delta = engine.calls[1]
        assertEquals("/sync/all-items", delta.path)
        assertEquals("2026-11-01T00:00:00Z", delta.query("date_from"))
        assertEquals(2, engine.calls.size) // playback and removals did not move
        val entries = store.get(1)!!.entries
        assertEquals(2, entries.size)
        val moved = entries.single { it.media!!.simklId == 2L }
        assertEquals(SimklMediaType.ANIME, moved.type)
        assertEquals("2026-11-02T00:00:00Z", store.get(1)!!.activities!!.all)
    }

    @Test
    fun `a removal is found by diffing the id list, and moved playbacks are read again`() = runTest {
        signedIn()
        initialSync()
        engine.reply(200, activities("b", removed = "r2", playback = "p2"))
        engine.reply(200, """{"shows":[{"show":{"ids":{"simkl":1}}}]}""")
        engine.reply(200, "[]")
        sync.refresh(1, TrackingRefreshIntent.USER_INITIATED)
        assertEquals("simkl_ids_only", engine.calls[1].query("extended"))
        assertEquals(listOf(1L), store.get(1)!!.entries.map { it.media!!.simklId })
        assertTrue(store.get(1)!!.playback.isEmpty())
    }

    @Test
    fun `a failed fetch keeps the saved stamp so the next run asks again`() = runTest {
        signedIn()
        initialSync()
        engine.reply(200, activities("c", watching = "w3"))
        engine.reply(500, "{}")
        engine.reply(500, "{}")
        engine.reply(500, "{}")
        engine.reply(500, "{}")
        val stamped = accounts.get(1, TrackingProviderId.SIMKL)!!.lastSyncedAtEpochMs
        clock.now += 60_000L
        assertFailsSuspend<SimklApiException> { sync.refresh(1, TrackingRefreshIntent.USER_INITIATED) }
        assertEquals("2026-11-01T00:00:00Z", store.get(1)!!.activities!!.all)
        assertEquals(stamped, accounts.get(1, TrackingProviderId.SIMKL)!!.lastSyncedAtEpochMs)
    }

    @Test
    fun `an automatic run within fifteen minutes sends nothing but a user's run goes`() = runTest {
        signedIn()
        initialSync()
        clock.now += 15 * 60_000L - 1
        assertFalse(sync.refresh(1, TrackingRefreshIntent.AUTOMATIC))
        assertTrue(engine.calls.isEmpty())
        clock.now += 1
        engine.reply(200, activities("2026-11-01T00:00:00Z"))
        assertTrue(sync.refresh(1, TrackingRefreshIntent.AUTOMATIC))
        engine.calls.clear()
        engine.reply(200, activities("2026-11-01T00:00:00Z"))
        assertTrue(sync.refresh(1, TrackingRefreshIntent.INVALIDATED))
        assertEquals(1, engine.calls.size)
    }

    @Test
    fun `a spent day or a signed-out profile sends nothing`() = runTest {
        assertFalse(sync.refresh(1, TrackingRefreshIntent.USER_INITIATED))
        signedIn()
        quota.recordFailure(1, SimklApiException(429, SIMKL_ERROR_USER_LIMIT, retryAfterSeconds = 3_600))
        assertFalse(sync.refresh(1, TrackingRefreshIntent.USER_INITIATED))
        assertTrue(engine.calls.isEmpty())
    }

    @Test
    fun `too many items splits by status`() = runTest {
        signedIn()
        engine.reply(200, activities("a"))
        engine.reply(400, """{"error":"max_items"}""")
        repeat(5) { status -> engine.reply(200, if (status == 0) """{"shows":[${show(1, "tt1")}]}""" else "{}") }
        repeat(2) { engine.reply(200, "{}") }
        engine.reply(200, "[]")
        sync.refresh(1, TrackingRefreshIntent.USER_INITIATED)
        assertEquals(
            listOf("watching", "plantowatch", "hold", "completed", "dropped").map { "/sync/all-items/shows/$it" },
            engine.calls.subList(2, 7).map { it.path }
        )
        assertEquals(1, store.get(1)!!.entries.size)
    }

    @Test
    fun `a sync running when the account changes never saves the old account's data`() = runTest {
        signedIn()
        engine.hold = CompletableDeferred()
        engine.reply(200, activities("a"))
        repeat(3) { engine.reply(200, "{}") }
        engine.reply(200, "[]")
        val running = async { sync.refresh(1, TrackingRefreshIntent.USER_INITIATED) }
        testScheduler.runCurrent()
        store.clear(1)
        engine.hold!!.complete(Unit)
        assertFalse(running.await())
        assertNull(store.get(1))
    }

    private fun playback(id: Long, episode: Int) =
        """{"id":$id,"progress":40,"paused_at":"2026-11-01T00:00:00Z","episode":{"season":1,"number":$episode},
            "show":{"title":"Show 1","ids":{"simkl":1,"imdb":"tt1"}}}"""

    @Test
    fun `removing a title's playback reads Simkl's list and deletes it there and from the snapshot`() = runTest {
        signedIn()
        initialSync()
        engine.reply(200, "[${playback(9, 2)}]")
        engine.reply(404, "{}")
        sync.removePlayback(1, "tt1", null, null)
        assertEquals(listOf("/sync/playback", "/sync/playback/9"), engine.calls.map { it.path })
        assertEquals(SimklHttpMethod.DELETE, engine.calls[1].method)
        assertTrue(store.get(1)!!.playback.isEmpty())
    }

    @Test
    fun `a pause made since the last sync is removed too`() = runTest {
        signedIn()
        initialSync()
        engine.reply(200, "[${playback(9, 2)}, ${playback(10, 3)}]")
        engine.reply(200)
        sync.removePlayback(1, "tt1", 1, 3)
        assertEquals("/sync/playback/10", engine.calls.last().path)
        assertEquals(listOf(9L), store.get(1)!!.playback.map { it.id })
    }

    @Test
    fun `removing another episode's playback deletes nothing`() = runTest {
        signedIn()
        initialSync()
        engine.reply(200, "[${playback(9, 2)}]")
        sync.removePlayback(1, "tt1", 1, 3)
        assertEquals(listOf("/sync/playback"), engine.calls.map { it.path })
        assertEquals(1, store.get(1)!!.playback.size)
    }

    @Test
    fun `a failed delete does not stop the others, the deleted ones are saved and the failure is thrown`() = runTest {
        signedIn()
        initialSync()
        engine.reply(200, "[${playback(9, 2)}, ${playback(10, 3)}]")
        engine.reply(200)
        engine.reply(403, """{"error":"forbidden"}""")
        assertEquals(403, assertFailsSuspend<SimklApiException> { sync.removePlayback(1, "tt1", null, null) }.status)
        assertEquals(3, engine.calls.size)
        assertTrue(store.get(1)!!.playback.isEmpty())
    }

    @Test
    fun `a removal during a sync that refetches playbacks is not undone by the sync's save`() = runTest {
        signedIn()
        initialSync()
        engine.hold = CompletableDeferred()
        engine.replyTo(SimklHttpMethod.GET, "/sync/activities", 200, activities("d", playback = "p2"))
        // Both the sync and the removal read the list before the delete lands.
        engine.replyTo(SimklHttpMethod.GET, "/sync/playback", 200, "[${playback(9, 2)}]")
        engine.replyTo(SimklHttpMethod.GET, "/sync/playback", 200, "[${playback(9, 2)}]")
        engine.replyTo(SimklHttpMethod.DELETE, "/sync/playback/9", 200)
        val syncing = async { sync.refresh(1, TrackingRefreshIntent.USER_INITIATED) }
        val removing = async { sync.removePlayback(1, "tt1", null, null) }
        testScheduler.runCurrent()
        engine.hold!!.complete(Unit)
        assertTrue(syncing.await())
        removing.await()
        assertEquals("d", store.get(1)!!.activities!!.all)
        assertTrue(store.get(1)!!.playback.isEmpty())
    }

    @Test
    fun `an account with no activity yet is not pulled again while nothing moves`() = runTest {
        signedIn()
        engine.reply(200, """{"all":null}""")
        repeat(3) { engine.reply(200, "{}") }
        engine.reply(200, "[]")
        sync.refresh(1, TrackingRefreshIntent.USER_INITIATED)
        engine.calls.clear()
        engine.reply(200, """{"all":null}""")
        assertTrue(sync.refresh(1, TrackingRefreshIntent.USER_INITIATED))
        assertEquals(1, engine.calls.size)
    }

    @Test
    fun `a delta too big for one call splits by type`() = runTest {
        signedIn()
        initialSync()
        engine.reply(200, activities("e", watching = "w9"))
        engine.reply(400, """{"error":"max_items"}""")
        repeat(3) { engine.reply(200, "{}") }
        sync.refresh(1, TrackingRefreshIntent.USER_INITIATED)
        assertEquals(listOf("/sync/all-items/shows", "/sync/all-items/movies", "/sync/all-items/anime"), engine.calls.drop(2).map { it.path })
        assertTrue(engine.calls.drop(2).all { it.query("date_from") == "2026-11-01T00:00:00Z" })
    }

    @Test
    fun `a ratings delta too big for one call splits by type`() = runTest {
        signedIn()
        initialSync()
        engine.reply(200, """{"all":"b","tv_shows":{"all":"b","watching":"w1","removed_from_list":"r1","playback":"p1","rated_at":"x2"},
            "movies":{},"anime":{}}""")
        engine.reply(400, """{"error":"max_items"}""")
        engine.reply(200, """{"shows":[{"user_rating":7,"show":{"ids":{"simkl":2}}}]}""")
        repeat(2) { engine.reply(200, "{}") }
        assertTrue(sync.refresh(1, TrackingRefreshIntent.USER_INITIATED))
        assertEquals(
            listOf("/sync/ratings", "/sync/ratings/shows", "/sync/ratings/movies", "/sync/ratings/anime"),
            engine.calls.drop(1).map { it.path }
        )
        assertTrue(engine.calls.drop(1).all { it.query("date_from") == "2026-11-01T00:00:00Z" })
        assertEquals(7.0, store.get(1)!!.entries.single { it.media?.simklId == 2L }.userRating)
    }

    @Test
    fun `kept marks replay outside the sync lock and the settled start never goes back`() = runTest {
        signedIn()
        initialSync()
        val replayGate = CompletableDeferred<Unit>()
        var replays = 0
        val gated = SimklSync(session, store, accounts, replayPending = { if (replays++ == 0) replayGate.await() }) { clock.now }
        val first = async { gated.refresh(1, TrackingRefreshIntent.USER_INITIATED) }
        testScheduler.runCurrent() // the first run waits in its replay

        clock.now += 1_000
        val secondStart = clock.now
        engine.reply(200, activities("2026-11-01T00:00:00Z"))
        // Finishes while the first run's replay is still held: the replay does not hold the sync lock.
        assertTrue(gated.refresh(1, TrackingRefreshIntent.USER_INITIATED))
        assertEquals(secondStart, gated.settledRunStart(1).first())

        engine.reply(200, activities("2026-11-01T00:00:00Z"))
        replayGate.complete(Unit)
        assertTrue(first.await())
        // The first run started earlier and ended later: the settled start stays at the second run's.
        assertEquals(secondStart, gated.settledRunStart(1).first())
    }

    @Test
    fun `a pull shows as pulling only while it runs`() = runTest {
        signedIn()
        initialSync()
        assertFalse(sync.isPulling(1).first())
        engine.hold = CompletableDeferred()
        engine.reply(200, activities("2026-11-01T00:00:00Z"))
        val running = async { sync.refresh(1, TrackingRefreshIntent.USER_INITIATED) }
        testScheduler.runCurrent()
        assertTrue(sync.isPulling(1).first())
        assertFalse(sync.isPulling(2).first())
        engine.hold!!.complete(Unit)
        assertTrue(running.await())
        assertFalse(sync.isPulling(1).first())
    }
}
