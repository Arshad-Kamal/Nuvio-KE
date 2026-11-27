package com.nuvio.tv.core.contentfilter

import com.nuvio.tv.core.tmdb.TmdbKeyState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Spec 117 S3: TitleTagLookup's queue, failure rules, key states and ticks. */
class TitleTagLookupTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val withKey = TmdbKeyState(userKey = "", effectiveKey = "k")
    private val tags = FetchedTags(listOf(80), listOf(9826 to "murder"), "en", adult = false)

    private class FakeSource : TitleTagSource {
        val calls = mutableListOf<String>()
        val keys = mutableListOf<String>()
        var onTags: suspend (TitleKind, Int) -> Fetch<FetchedTags> = { _, _ -> error("set onTags") }
        var onFind: suspend (String, TitleKind) -> Fetch<Int> = { _, _ -> Fetch.NotFound }
        var onProbe: suspend () -> Fetch<Unit> = { Fetch.Ok(Unit) }

        override suspend fun tags(kind: TitleKind, tmdbId: Int, apiKey: String): Fetch<FetchedTags> {
            calls += "tags:${kind.key}:$tmdbId"
            keys += apiKey
            return onTags(kind, tmdbId)
        }

        override suspend fun find(imdbId: String, kind: TitleKind, apiKey: String): Fetch<Int> {
            calls += "find:$imdbId"
            return onFind(imdbId, kind)
        }

        override suspend fun probe(apiKey: String): Fetch<Unit> {
            calls += "probe"
            return onProbe()
        }
    }

    private class Harness(
        val lookup: TitleTagLookup,
        val source: FakeSource,
        val key: MutableStateFlow<TmdbKeyState?>,
        val ticks: MutableList<Long>
    )

    private fun TestScope.harness(
        key: TmdbKeyState? = withKey,
        workers: Int = 1,
        queueCap: Int = 5_000,
        source: FakeSource = FakeSource().apply { onTags = { _, _ -> Fetch.Ok(tags) } },
        settle: Boolean = true
    ): Harness {
        val keyState = MutableStateFlow(key)
        val clock = { testScheduler.currentTime }
        val cache = TitleTagCache(
            File(folder.root, "tags.json"), clock, backgroundScope, StandardTestDispatcher(testScheduler), writeDelayMs = 10
        )
        val lookup = TitleTagLookup(source, keyState, cache, backgroundScope, clock, workers, queueCap)
        val ticks = mutableListOf<Long>()
        backgroundScope.launch { lookup.changes.collect { ticks += testScheduler.currentTime } }
        if (settle) runCurrent()
        return Harness(lookup, source, keyState, ticks)
    }

    private fun movie(id: Int) = TitleRef(TitleKind.MOVIE, "tmdb:$id")

    @Test
    fun `a requested title is fetched once, stored and ticks`() = runTest {
        val h = harness()
        advanceTimeBy(2_000)
        h.ticks.clear() // the cache-load tick
        val ref = movie(603)
        assertEquals(TagState.Undecided, h.lookup.state(ref))
        h.lookup.request(ref, TagPriority.PAGE)
        h.lookup.request(ref, TagPriority.PAGE)
        runCurrent()
        assertEquals(listOf("tags:movie:603"), h.source.calls)
        assertTrue(h.lookup.state(ref) is TagState.Tagged)
        advanceTimeBy(2_000)
        assertTrue(h.ticks.isNotEmpty())
        h.lookup.request(ref, TagPriority.NOW)
        runCurrent()
        assertEquals(1, h.source.calls.size)
    }

    @Test
    fun `an imdb id is found once, then read through its alias`() = runTest {
        val h = harness()
        h.source.onFind = { _, _ -> Fetch.Ok(1399) }
        assertEquals(null, h.lookup.tmdbKey(TitleRef(TitleKind.TV, "tt0944947")))
        h.lookup.request(TitleRef(TitleKind.TV, "tt0944947"), TagPriority.PAGE)
        runCurrent()
        h.lookup.request(TitleRef(TitleKind.TV, "tt0944947:1:1"), TagPriority.PAGE)
        runCurrent()
        assertEquals(listOf("find:tt0944947", "tags:tv:1399"), h.source.calls)
        assertTrue(h.lookup.state(TitleRef(TitleKind.TV, "tt0944947:2:3")) is TagState.Tagged)
        // The allowlist key (117/D30): through the alias for an IMDb id, direct for a tmdb: id.
        assertEquals("tv:1399", h.lookup.tmdbKey(TitleRef(TitleKind.TV, "tt0944947:2:3")))
        assertEquals("movie:603", h.lookup.tmdbKey(movie(603)))
        assertEquals(null, h.lookup.tmdbKey(TitleRef(TitleKind.MOVIE, "kitsu:9")))
    }

    @Test
    fun `not on TMDB and unresolvable ids are words-only`() = runTest {
        val h = harness()
        h.lookup.request(TitleRef(TitleKind.MOVIE, "tt7"), TagPriority.PAGE)
        h.lookup.request(TitleRef(TitleKind.MOVIE, "kitsu:9"), TagPriority.PAGE)
        runCurrent()
        assertEquals(listOf("find:tt7"), h.source.calls)
        assertEquals(TagState.WordsOnly, h.lookup.state(TitleRef(TitleKind.MOVIE, "tt7")))
        assertEquals(TagState.WordsOnly, h.lookup.state(TitleRef(TitleKind.MOVIE, "kitsu:9")))
    }

    @Test
    fun `levels run NOW, PAGE, DRAW, BACKGROUND, FIFO within one, and a higher request promotes`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source = FakeSource().apply {
            onTags = { _, id -> if (id == 1) gate.await(); Fetch.Ok(tags) }
        }
        val h = harness(source = source)
        h.lookup.request(movie(1), TagPriority.BACKGROUND) // occupies the only worker
        runCurrent()
        h.lookup.request(movie(2), TagPriority.BACKGROUND)
        h.lookup.request(movie(3), TagPriority.DRAW)
        h.lookup.request(movie(4), TagPriority.PAGE)
        h.lookup.request(movie(5), TagPriority.DRAW)
        h.lookup.request(movie(6), TagPriority.NOW)
        h.lookup.request(movie(2), TagPriority.PAGE) // promoted behind 4
        h.lookup.request(movie(1), TagPriority.NOW) // in flight: not queued again
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(1, 6, 4, 2, 3, 5).map { "tags:movie:$it" }, h.source.calls)
    }

    @Test
    fun `at the queue cap new background work is refused, gate work is not`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source = FakeSource().apply { onTags = { _, _ -> gate.await(); Fetch.Ok(tags) } }
        val h = harness(queueCap = 2, source = source)
        h.lookup.request(movie(1), TagPriority.BACKGROUND)
        runCurrent() // 1 in flight
        h.lookup.request(movie(2), TagPriority.BACKGROUND)
        h.lookup.request(movie(3), TagPriority.BACKGROUND)
        h.lookup.request(movie(4), TagPriority.BACKGROUND) // refused
        h.lookup.request(movie(5), TagPriority.PAGE)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(1, 5, 2, 3).map { "tags:movie:$it" }, h.source.calls)
    }

    @Test
    fun `a failed title is not re-queued for 5 minutes, then a tick lets it back`() = runTest {
        var fail = true
        val source = FakeSource().apply { onTags = { _, _ -> if (fail) Fetch.Failed(io = false) else Fetch.Ok(tags) } }
        val h = harness(source = source)
        h.lookup.request(movie(1), TagPriority.PAGE)
        runCurrent()
        assertEquals(TagState.Undecided, h.lookup.state(movie(1)))
        h.lookup.request(movie(1), TagPriority.NOW)
        runCurrent()
        assertEquals(1, h.source.calls.size)

        advanceTimeBy(10_000)
        val ticksBefore = h.ticks.size
        fail = false
        advanceTimeBy(TitleTagLookup.FAILED_BACKOFF_MS)
        runCurrent()
        assertTrue("backoff end ticks", h.ticks.size > ticksBefore)
        h.lookup.request(movie(1), TagPriority.PAGE)
        runCurrent()
        assertTrue(h.lookup.state(movie(1)) is TagState.Tagged)
    }

    @Test
    fun `retry forgets one title's backoff so it is fetched now, the others keep theirs`() = runTest {
        var fail = true
        val source = FakeSource().apply { onTags = { _, _ -> if (fail) Fetch.Failed(io = false) else Fetch.Ok(tags) } }
        val h = harness(source = source)
        h.lookup.request(movie(1), TagPriority.PAGE)
        h.lookup.request(movie(2), TagPriority.PAGE)
        runCurrent()
        fail = false
        h.lookup.retry(movie(1))
        h.lookup.request(movie(1), TagPriority.NOW)
        h.lookup.request(movie(2), TagPriority.NOW)
        runCurrent()
        assertTrue(h.lookup.state(movie(1)) is TagState.Tagged)
        assertEquals("still backing off", TagState.Undecided, h.lookup.state(movie(2)))
        assertEquals(3, h.source.calls.size)
    }

    @Test
    fun `five IO failures in a row pause the source for 60 s, then it clears with its own tick`() = runTest {
        val source = FakeSource().apply { onTags = { _, _ -> Fetch.Failed(io = true) } }
        val h = harness(source = source)
        (1..5).forEach { h.lookup.request(movie(it), TagPriority.PAGE) }
        runCurrent()
        assertEquals(TagSourceStatus.PAUSED, h.lookup.status())
        advanceTimeBy(2_000)
        val ticksBefore = h.ticks.size
        advanceTimeBy(TitleTagLookup.BREAKER_PAUSE_MS)
        runCurrent()
        assertEquals(TagSourceStatus.READY, h.lookup.status())
        // Nothing else is queued and the 5-min backoffs have not ended: this is the breaker's tick.
        assertTrue(h.ticks.size > ticksBefore)
    }

    @Test
    fun `while paused gates do not wait, and a queued title is fetched after the pause`() = runTest {
        var down = true
        val source = FakeSource().apply { onTags = { _, _ -> if (down) Fetch.Failed(io = true) else Fetch.Ok(tags) } }
        val h = harness(source = source)
        (1..5).forEach { h.lookup.request(movie(it), TagPriority.PAGE) }
        runCurrent()
        val start = testScheduler.currentTime
        h.lookup.awaitDecided(listOf(movie(6)), TagPriority.PAGE, budgetMs = 3_000)
        assertEquals(start, testScheduler.currentTime)
        // Still failing: a worker that ignored the pause would fetch 6 now and count a sixth call.
        advanceTimeBy(TitleTagLookup.BREAKER_PAUSE_MS - 1_000)
        assertEquals(5, h.source.calls.size)
        down = false
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(h.lookup.state(movie(6)) is TagState.Tagged)
    }

    @Test
    fun `only consecutive IO failures count toward the breaker`() = runTest {
        val results = ArrayDeque<Fetch<FetchedTags>>(
            List(4) { Fetch.Failed(io = true) } + Fetch.Ok(tags) + List(4) { Fetch.Failed(io = true) } +
                Fetch.NotFound + List(4) { Fetch.Failed(io = true) } + Fetch.Failed(io = false) +
                List(4) { Fetch.Failed(io = true) } + List(5) { Fetch.Failed(io = false) }
        )
        val source = FakeSource().apply { onTags = { _, _ -> results.removeFirst() } }
        val h = harness(source = source)
        (1..29).forEach { h.lookup.request(movie(it), TagPriority.PAGE) }
        runCurrent()
        assertEquals(29, h.source.calls.size)
        assertEquals(TagSourceStatus.READY, h.lookup.status())
    }

    @Test
    fun `a 429 pauses every worker for its retry-after, then the title is fetched`() = runTest {
        var limited = true
        val source = FakeSource().apply {
            onTags = { _, id -> if (id == 1 && limited) { limited = false; Fetch.RateLimited(5_000) } else Fetch.Ok(tags) }
        }
        val h = harness(workers = 2, source = source)
        h.lookup.request(movie(1), TagPriority.PAGE)
        runCurrent()
        h.lookup.request(movie(2), TagPriority.PAGE)
        advanceTimeBy(4_000)
        assertEquals(listOf("tags:movie:1"), h.source.calls)
        advanceTimeBy(1_100)
        assertEquals(3, h.source.calls.size)
        assertTrue(h.lookup.state(movie(1)) is TagState.Tagged)
        assertTrue(h.lookup.state(movie(2)) is TagState.Tagged)
    }

    @Test
    fun `a 401 means no key until a re-probe succeeds`() = runTest {
        val source = FakeSource().apply { onTags = { _, _ -> Fetch.Unauthorized } }
        val h = harness(source = source)
        h.lookup.request(movie(1), TagPriority.PAGE)
        h.lookup.request(movie(2), TagPriority.PAGE)
        runCurrent()
        assertEquals(TagSourceStatus.NO_KEY, h.lookup.status())
        assertEquals(listOf("tags:movie:1"), h.source.calls) // 2 was cleared from the queue
        assertEquals(TagState.WordsOnly, h.lookup.state(movie(2)))
        h.lookup.request(movie(3), TagPriority.NOW)
        runCurrent()
        assertEquals(1, h.source.calls.size)

        h.source.onTags = { _, _ -> Fetch.Ok(tags) }
        val ticksBefore = h.ticks.size
        advanceTimeBy(TitleTagLookup.REPROBE_INTERVAL_MS + 1)
        runCurrent()
        assertEquals(TagSourceStatus.READY, h.lookup.status())
        assertTrue(h.ticks.size > ticksBefore)
        assertEquals(TagState.Undecided, h.lookup.state(movie(2)))
        // Nothing was left stranded in the queue: a new request fetches it.
        h.lookup.request(movie(2), TagPriority.PAGE)
        runCurrent()
        assertTrue(h.lookup.state(movie(2)) is TagState.Tagged)
    }

    @Test
    fun `a failed re-probe keeps the no-key state and tries again later`() = runTest {
        val source = FakeSource().apply {
            onTags = { _, _ -> Fetch.Unauthorized }
            onProbe = { Fetch.Unauthorized }
        }
        val h = harness(source = source)
        h.lookup.request(movie(1), TagPriority.PAGE)
        runCurrent()
        advanceTimeBy(TitleTagLookup.REPROBE_INTERVAL_MS + 1)
        assertEquals(TagSourceStatus.NO_KEY, h.lookup.status())
        h.source.onProbe = { Fetch.Ok(Unit) }
        advanceTimeBy(TitleTagLookup.REPROBE_INTERVAL_MS)
        assertEquals(TagSourceStatus.READY, h.lookup.status())
        assertEquals(2, h.source.calls.count { it == "probe" })
    }

    @Test
    fun `a key change clears a 401 period and ticks`() = runTest {
        val source = FakeSource().apply { onTags = { _, _ -> Fetch.Unauthorized } }
        val h = harness(source = source)
        h.lookup.request(movie(1), TagPriority.PAGE)
        runCurrent()
        assertEquals(TagSourceStatus.NO_KEY, h.lookup.status())
        advanceTimeBy(2_000)
        val ticksBefore = h.ticks.size
        h.key.value = TmdbKeyState(userKey = "mine", effectiveKey = "mine")
        advanceTimeBy(2_000)
        assertEquals(TagSourceStatus.READY, h.lookup.status())
        assertTrue(h.ticks.size > ticksBefore)
    }

    @Test
    fun `with no key nothing is queued and titles are words-only`() = runTest {
        val h = harness(key = TmdbKeyState(userKey = "", effectiveKey = ""))
        h.lookup.request(movie(1), TagPriority.NOW)
        runCurrent()
        assertTrue(h.source.calls.isEmpty())
        assertEquals(TagSourceStatus.NO_KEY, h.lookup.status())
        assertEquals(TagState.WordsOnly, h.lookup.state(movie(1)))
    }

    @Test
    fun `before the key store is read titles are undecided and wait, then fetch`() = runTest {
        val h = harness(key = null)
        assertEquals(TagSourceStatus.KEY_UNKNOWN, h.lookup.status())
        h.lookup.request(movie(1), TagPriority.PAGE)
        runCurrent()
        assertEquals(TagState.Undecided, h.lookup.state(movie(1)))
        assertTrue(h.source.calls.isEmpty())
        h.key.value = withKey
        runCurrent()
        assertTrue(h.lookup.state(movie(1)) is TagState.Tagged)
    }

    @Test
    fun `ticks are throttled to one a second, the latest kept`() = runTest {
        val source = FakeSource().apply { onTags = { _, _ -> delay(100); Fetch.Ok(tags) } }
        val h = harness(source = source)
        advanceTimeBy(2_000)
        h.ticks.clear()
        val start = testScheduler.currentTime
        (1..20).forEach { h.lookup.request(movie(it), TagPriority.PAGE) }
        advanceTimeBy(10_000)
        // 20 stores over ~2 s: a leading tick, then at most one a second, the last after the last store.
        assertTrue("ticks ${h.ticks}", h.ticks.size in 2..3)
        assertTrue(h.ticks.zipWithNext { a, b -> b - a }.all { it >= TitleTagLookup.TICK_INTERVAL_MS })
        assertTrue(h.ticks.last() >= start + 2_000)
    }

    @Test
    fun `a 401 for a key since replaced does not lock out the new key`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source = FakeSource().apply { onTags = { _, _ -> gate.await(); Fetch.Unauthorized } }
        val h = harness(source = source)
        h.lookup.request(movie(1), TagPriority.PAGE)
        runCurrent()
        h.key.value = TmdbKeyState(userKey = "new", effectiveKey = "new")
        runCurrent()
        h.source.onTags = { _, _ -> Fetch.Ok(tags) }
        gate.complete(Unit)
        runCurrent()
        assertEquals(TagSourceStatus.READY, h.lookup.status())
        // Re-queued and fetched again with the new key, not stranded.
        assertTrue(h.lookup.state(movie(1)) is TagState.Tagged)
        assertEquals(listOf("k", "new"), h.source.keys)
    }

    @Test
    fun `an imdb id whose TMDB id is already cached costs only the find`() = runTest {
        val h = harness()
        h.source.onFind = { _, _ -> Fetch.Ok(603) }
        h.lookup.request(movie(603), TagPriority.PAGE)
        runCurrent()
        h.lookup.request(TitleRef(TitleKind.MOVIE, "tt0133093"), TagPriority.PAGE)
        runCurrent()
        assertEquals(listOf("tags:movie:603", "find:tt0133093"), h.source.calls)
        assertTrue(h.lookup.state(TitleRef(TitleKind.MOVIE, "tt0133093")) is TagState.Tagged)
    }

    @Test
    fun `before the key store is read a gate waits its budget`() = runTest {
        val h = harness(key = null)
        val start = testScheduler.currentTime
        h.lookup.awaitDecided(listOf(movie(1)), TagPriority.PAGE, budgetMs = 3_000)
        assertEquals(start + 3_000, testScheduler.currentTime)
    }

    @Test
    fun `each backoff end ticks, the later one too`() = runTest {
        val source = FakeSource().apply { onTags = { _, _ -> Fetch.Failed(io = false) } }
        val h = harness(source = source)
        h.lookup.request(movie(1), TagPriority.PAGE)
        runCurrent()
        advanceTimeBy(3_000)
        h.lookup.request(movie(2), TagPriority.PAGE)
        runCurrent()
        advanceTimeBy(2_000)
        h.ticks.clear()
        advanceTimeBy(TitleTagLookup.FAILED_BACKOFF_MS)
        runCurrent()
        assertEquals("ticks ${h.ticks}", 2, h.ticks.size)
        h.lookup.request(movie(1), TagPriority.PAGE)
        h.lookup.request(movie(2), TagPriority.PAGE)
        runCurrent()
        assertEquals(4, h.source.calls.size)
    }

    @Test
    fun `awaitDecided returns when its titles are decided, or at the budget`() = runTest {
        val slow = CompletableDeferred<Unit>()
        val source = FakeSource().apply { onTags = { _, id -> if (id == 2) slow.await(); Fetch.Ok(tags) } }
        val h = harness(workers = 2, source = source)
        h.lookup.awaitDecided(listOf(movie(1)), TagPriority.PAGE, budgetMs = 3_000)
        assertTrue(h.lookup.state(movie(1)) is TagState.Tagged)

        val start = testScheduler.currentTime
        h.lookup.awaitDecided(listOf(movie(2)), TagPriority.PAGE, budgetMs = 3_000)
        assertEquals(start + 3_000, testScheduler.currentTime)
        assertEquals(TagState.Undecided, h.lookup.state(movie(2)))
        slow.complete(Unit)
    }

    @Test
    fun `awaitDecided stops waiting for a title whose fetch failed`() = runTest {
        val source = FakeSource().apply { onTags = { _, _ -> Fetch.Failed(io = false) } }
        val h = harness(source = source)
        val start = testScheduler.currentTime
        h.lookup.awaitDecided(listOf(movie(1)), TagPriority.PAGE, budgetMs = 3_000)
        assertTrue(testScheduler.currentTime - start < 3_000)
        assertEquals(TagState.Undecided, h.lookup.state(movie(1)))
    }

    @Test
    fun `a worker survives an unexpected exception and the title backs off`() = runTest {
        val source = FakeSource().apply {
            onTags = { _, id -> if (id == 1) throw IllegalStateException("bug") else Fetch.Ok(tags) }
        }
        val h = harness(source = source)
        h.lookup.request(movie(1), TagPriority.PAGE)
        runCurrent()
        h.lookup.request(movie(2), TagPriority.PAGE)
        runCurrent()
        assertTrue(h.lookup.state(movie(2)) is TagState.Tagged)
        h.lookup.request(movie(1), TagPriority.NOW)
        runCurrent()
        assertEquals(1, h.source.calls.count { it == "tags:movie:1" })
    }

    @Test
    fun `a request queued before the key is known is dropped when there is no key`() = runTest {
        val h = harness(key = null)
        h.lookup.request(movie(1), TagPriority.PAGE)
        runCurrent()
        h.key.value = TmdbKeyState(userKey = "", effectiveKey = "")
        runCurrent()
        assertTrue(h.source.calls.isEmpty())
        assertEquals(TagState.WordsOnly, h.lookup.state(movie(1)))
        val start = testScheduler.currentTime
        h.lookup.awaitDecided(listOf(movie(2)), TagPriority.PAGE, budgetMs = 3_000)
        assertEquals(start, testScheduler.currentTime)
    }

    @Test
    fun `a title already in the cache file is not fetched before the load finishes`() = runTest {
        val file = File(folder.root, "tags.json")
        TitleTagCache(file, { testScheduler.currentTime }, backgroundScope, StandardTestDispatcher(testScheduler)).apply {
            ensureLoaded()
            putTags(TitleKind.MOVIE, 603, tags)
            flush()
        }
        val h = harness(settle = false)
        h.lookup.request(movie(603), TagPriority.PAGE)
        advanceTimeBy(2_000)
        assertTrue(h.source.calls.isEmpty())
        assertTrue(h.lookup.state(movie(603)) is TagState.Tagged)
        assertTrue(h.ticks.isNotEmpty())
    }

    @Test
    fun `a higher request for an in-flight title keeps that level if it is re-queued`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var limited = true
        val source = FakeSource().apply {
            onTags = { _, id ->
                if (id == 1 && limited) { gate.await(); limited = false; Fetch.RateLimited(1_000) } else Fetch.Ok(tags)
            }
        }
        val h = harness(source = source)
        h.lookup.request(movie(1), TagPriority.BACKGROUND)
        runCurrent()
        h.lookup.request(movie(1), TagPriority.NOW) // in flight: promoted, not queued twice
        h.lookup.request(movie(2), TagPriority.PAGE)
        gate.complete(Unit)
        advanceTimeBy(2_000)
        assertEquals(listOf(1, 1, 2).map { "tags:movie:$it" }, h.source.calls)
    }
}
