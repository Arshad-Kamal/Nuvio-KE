package com.nuvio.tv.core.stream

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.repository.StreamRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * StreamPrefetchCache is a process-wide object whose scope runs on
 * Dispatchers.IO, so these tests use runBlocking and wait on explicit
 * completion signals (bounded by [WAIT_MS]) rather than virtual time.
 */
class StreamPrefetchCacheTest {

    private class FakeRepo(
        private val respond: (videoId: String, call: Int) -> Flow<NetworkResult<List<AddonStreams>>>
    ) : StreamRepository {
        private val calls = ConcurrentHashMap<String, AtomicInteger>()

        override fun getStreamsFromAllAddons(
            type: String,
            videoId: String,
            season: Int?,
            episode: Int?,
            forceRefresh: Boolean
        ): Flow<NetworkResult<List<AddonStreams>>> =
            respond(videoId, calls.getOrPut(videoId) { AtomicInteger() }.getAndIncrement())
    }

    private fun groups(name: String) = listOf(AddonStreams(name, null, emptyList()))

    private val never: Flow<NetworkResult<List<AddonStreams>>> = flow { awaitCancellation() }

    @Before
    fun setUp() = StreamPrefetchCache.resetForTest()

    @After
    fun tearDown() = StreamPrefetchCache.resetForTest()

    @Test
    fun `join onto a superseded prefetch falls back to the live scrape`() = runBlocking {
        val live = groups("live")
        val prefetchCalled = CompletableDeferred<Unit>()
        val partialsCalled = CompletableDeferred<Unit>()
        val repo = FakeRepo { videoId, call ->
            when {
                videoId == "join-a" && call == 0 -> never.also { prefetchCalled.complete(Unit) }
                // Call 1 is the join's partials collector: the running scrape has nothing yet.
                videoId == "join-a" && call == 1 -> never.also { partialsCalled.complete(Unit) }
                videoId == "join-a" -> flowOf(NetworkResult.Success(live))
                else -> never
            }
        }
        StreamPrefetchCache.prefetch(repo, "movie", "join-a", null, null, source = "test")
        // Call 0 must be the prefetch's: superseding before its IO body runs would hand
        // call 0 (never) to the live fallback and hang (39/200 runs, 2026-10-06).
        withTimeout(WAIT_MS) { prefetchCalled.await() }
        val joined = StreamPrefetchCache.streamsFor(repo, "movie", "join-a", null, null)
        val emissions = async { joined.toList() }
        // The join is live once its partials collector has subscribed (call 1 stays call 1).
        withTimeout(WAIT_MS) { partialsCalled.await() }

        // A newer ui prefetch cancels the joined job.
        StreamPrefetchCache.prefetch(repo, "movie", "join-b", null, null, source = "test")

        assertEquals(
            listOf(NetworkResult.Loading, NetworkResult.Success(live)),
            withTimeout(WAIT_MS) { emissions.await() }
        )
    }

    @Test
    fun `a join forwards the running scrape's partial pool before the prefetch result`() = runBlocking {
        val partial = groups("fast-addon")
        val full = groups("fast-addon") + groups("slow-addon")
        val gate = CompletableDeferred<Unit>()
        val prefetchCalled = CompletableDeferred<Unit>()
        // Call 0 is the prefetch; later calls stand in for the repository session it shares.
        val repo = FakeRepo { _, call ->
            flow {
                if (call == 0) prefetchCalled.complete(Unit)
                emit(NetworkResult.Success(partial))
                gate.await()
                emit(NetworkResult.Success(full))
                // The session collector stays open until the join cancels it.
                if (call != 0) awaitCancellation()
            }
        }
        val sawFull = CompletableDeferred<Unit>()
        StreamPrefetchCache.prefetch(
            repo, "movie", "part", null, null, source = "details_hero",
            // Holds the job until the join forwarded full, so the equal pool is not sent twice.
            rank = { withTimeoutOrNull(WAIT_MS) { sawFull.await() }; null }
        )
        withTimeout(WAIT_MS) { prefetchCalled.await() }

        val emissions = Collections.synchronizedList(mutableListOf<NetworkResult<List<AddonStreams>>>())
        val sawPartial = CompletableDeferred<Unit>()
        val collector = launch {
            StreamPrefetchCache.streamsFor(repo, "movie", "part", null, null).collect {
                emissions += it
                if (it == NetworkResult.Success(partial)) sawPartial.complete(Unit)
                if (it == NetworkResult.Success(full)) sawFull.complete(Unit)
            }
        }
        // The partial arrives while the prefetch still waits on its slow addon.
        withTimeout(WAIT_MS) { sawPartial.await() }
        gate.complete(Unit)
        withTimeout(WAIT_MS) { collector.join() }

        // Exactly once each: the prefetch's equal result is not sent after the forwarded one.
        assertEquals(
            listOf(NetworkResult.Loading, NetworkResult.Success(partial), NetworkResult.Success(full)),
            emissions.toList()
        )
    }

    @Test
    fun `a capped join never ends on a pool older than one it forwarded`() = runBlocking {
        val partial = groups("fast-addon")
        val bigger = groups("fast-addon") + groups("late-addon")
        val prefetchCalled = CompletableDeferred<Unit>()
        val sawBigger = CompletableDeferred<Unit>()
        // Call 0: the capped prefetch sees only the partial. Call 1: the session moves on.
        val repo = FakeRepo { _, call ->
            flow {
                if (call == 0) prefetchCalled.complete(Unit)
                emit(NetworkResult.Success(partial))
                if (call != 0) emit(NetworkResult.Success(bigger))
                awaitCancellation()
            }
        }
        StreamPrefetchCache.prefetch(
            repo, "movie", "cap-join", null, null, source = "details_hero",
            capMs = CAP_MS,
            // Holds the job (after its cap) until the join has forwarded the bigger pool.
            rank = { withTimeoutOrNull(WAIT_MS) { sawBigger.await() }; null }
        )
        withTimeout(WAIT_MS) { prefetchCalled.await() }

        val emissions = Collections.synchronizedList(mutableListOf<NetworkResult<List<AddonStreams>>>())
        val collector = launch {
            StreamPrefetchCache.streamsFor(repo, "movie", "cap-join", null, null).collect {
                emissions += it
                if (it == NetworkResult.Success(bigger)) sawBigger.complete(Unit)
            }
        }
        withTimeout(WAIT_MS) { collector.join() }

        assertEquals(
            listOf(NetworkResult.Loading, NetworkResult.Success(partial), NetworkResult.Success(bigger)),
            emissions.toList()
        )
    }

    @Test
    fun `a failing partials collector does not end the join`() = runBlocking {
        val full = groups("a")
        val partialsFailed = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val prefetchCalled = CompletableDeferred<Unit>()
        val repo = FakeRepo { _, call ->
            if (call == 0) {
                flow {
                    prefetchCalled.complete(Unit)
                    gate.await()
                    emit(NetworkResult.Success(full))
                }
            } else {
                flow {
                    partialsFailed.complete(Unit)
                    throw IllegalStateException("session failed")
                }
            }
        }
        StreamPrefetchCache.prefetch(repo, "movie", "fail-join", null, null, source = "details_hero")
        withTimeout(WAIT_MS) { prefetchCalled.await() }

        val emissions = async { StreamPrefetchCache.streamsFor(repo, "movie", "fail-join", null, null).toList() }
        // The partials collector has failed while the prefetch is still running.
        withTimeout(WAIT_MS) { partialsFailed.await() }
        gate.complete(Unit)

        assertEquals(
            listOf(NetworkResult.Loading, NetworkResult.Success(full)),
            withTimeout(WAIT_MS) { emissions.await() }
        )
    }

    @Test
    fun `a pending rank is announced on a new scrape and an in-flight dedup, not on a fresh entry`() = runBlocking {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val gate = CompletableDeferred<Unit>()
        val ranks = AtomicInteger()
        val bothRanked = CompletableDeferred<Unit>()
        val thirdRanked = CompletableDeferred<Unit>()
        val repo = FakeRepo { _, _ ->
            flow {
                gate.await()
                emit(NetworkResult.Success(groups("a")))
            }
        }
        fun hero(label: String) = StreamPrefetchCache.prefetch(
            repo, "movie", "pend", null, null, source = "details_hero",
            republishOnDedup = true,
            onRankPending = { events += label },
            rank = {
                events += "rank"
                when (ranks.incrementAndGet()) {
                    2 -> bothRanked.complete(Unit)
                    3 -> thirdRanked.complete(Unit)
                }
                null
            }
        )

        hero("new-scrape")
        hero("in-flight")
        assertEquals(listOf("new-scrape", "in-flight"), events.toList())

        gate.complete(Unit)
        withTimeout(WAIT_MS) { bothRanked.await() }
        hero("fresh-entry")
        withTimeout(WAIT_MS) { thirdRanked.await() }
        // A caller without republishOnDedup has no terminal-signal guarantee: never announced.
        StreamPrefetchCache.prefetch(
            repo, "movie", "pend-other", null, null, source = "details_hero",
            onRankPending = { events += "no-republish" },
            rank = { null }
        )

        assertEquals(listOf("new-scrape", "in-flight", "rank", "rank", "rank"), events.toList())
    }

    @Test
    fun `empty scrape still runs the ranker`() = runBlocking {
        val ranked = CompletableDeferred<List<AddonStreams>>()
        val repo = FakeRepo { _, _ -> flowOf(NetworkResult.Success(emptyList())) }

        StreamPrefetchCache.prefetch(
            repo, "movie", "empty", null, null, source = "test",
            rank = { groups -> ranked.complete(groups); null }
        )

        assertEquals(emptyList<AddonStreams>(), withTimeout(WAIT_MS) { ranked.await() })
    }

    @Test
    fun `ui dedup onto a background job stops a later background warmer cancelling it`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val warmed = groups("warmed")
        val prefetchCalled = CompletableDeferred<Unit>()
        val repo = FakeRepo { videoId, call ->
            when {
                videoId == "bg-1" && call == 0 -> flow {
                    prefetchCalled.complete(Unit)
                    gate.await()
                    emit(NetworkResult.Success(warmed))
                }
                // The join's partials collector sees the still-running scrape: nothing new yet.
                else -> never
            }
        }
        StreamPrefetchCache.prefetch(repo, "movie", "bg-1", null, null, source = "cw", background = true)
        // Call 0 must be the prefetch's, not the join's partials collector.
        withTimeout(WAIT_MS) { prefetchCalled.await() }
        // A ui caller dedups onto the background job and now depends on it.
        StreamPrefetchCache.prefetch(repo, "movie", "bg-1", null, null, source = "details_hero")
        // A later background warmer for another key must yield, not cancel.
        StreamPrefetchCache.prefetch(repo, "movie", "bg-2", null, null, source = "cw_focus", background = true)

        // Still a join onto bg-1 (a cancelled job would make this a live miss).
        val joined = StreamPrefetchCache.streamsFor(repo, "movie", "bg-1", null, null)
        val emissions = async { joined.toList() }
        gate.complete(Unit)

        assertEquals(
            listOf(NetworkResult.Loading, NetworkResult.Success(warmed)),
            withTimeout(WAIT_MS) { emissions.await() }
        )
    }

    @Test
    fun `superseded ui prefetch ranks an empty pool so its line terminates`() = runBlocking {
        val ranked = CompletableDeferred<List<AddonStreams>>()
        val repo = FakeRepo { _, _ -> never }

        StreamPrefetchCache.prefetch(
            repo, "movie", "hero-a", null, null, source = "details_hero",
            republishOnDedup = true,
            rank = { groups -> ranked.complete(groups); null }
        )
        StreamPrefetchCache.prefetch(repo, "movie", "hero-b", null, null, source = "details_hero")

        assertEquals(emptyList<AddonStreams>(), withTimeout(WAIT_MS) { ranked.await() })
    }

    @Test
    fun `superseding after the ranker returned keeps its signal`() = runBlocking {
        val pools = Collections.synchronizedList(mutableListOf<List<AddonStreams>>())
        val sawEmpty = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val warmed = groups("warmed")
        val prefetchCalled = CompletableDeferred<Unit>()
        val repo = FakeRepo { videoId, call ->
            when {
                videoId == "rank-a" && call == 0 -> flow {
                    prefetchCalled.complete(Unit)
                    gate.await()
                    emit(NetworkResult.Success(warmed))
                }
                videoId == "rank-a" -> flowOf(NetworkResult.Success(groups("live")))
                else -> never
            }
        }
        StreamPrefetchCache.prefetch(
            repo, "movie", "rank-a", null, null, source = "details_hero",
            republishOnDedup = true,
            rank = { pool ->
                pools += pool
                if (pool.isEmpty()) sawEmpty.complete(Unit)
                // Supersede at the ranker's tail: nothing suspends after this, so
                // the ranker returns normally and the job then completes cancelled.
                StreamPrefetchCache.prefetch(repo, "movie", "rank-b", null, null, source = "details_hero")
                null
            }
        )
        withTimeout(WAIT_MS) { prefetchCalled.await() }
        val joined = StreamPrefetchCache.streamsFor(repo, "movie", "rank-a", null, null)
        val emissions = async { joined.toList() }
        yield()
        gate.complete(Unit)

        // The join resumes only after the job completed, i.e. after its completion handler ran.
        withTimeout(WAIT_MS) { emissions.await() }
        assertNull(withTimeoutOrNull(GRACE_MS) { sawEmpty.await() })
        assertEquals(listOf(warmed), pools.toList())
    }

    @Test
    fun `hero cap hit with no groups re-ranks the full pool late`() = runBlocking {
        val pools = Collections.synchronizedList(mutableListOf<List<AddonStreams>>())
        val sawEmpty = CompletableDeferred<Unit>()
        val sawFull = CompletableDeferred<Unit>()
        val lateCollecting = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val full = groups("slow-addon")
        // Call 0 is the capped prefetch (sees nothing); call 1 the late re-collect.
        val repo = FakeRepo { id, call ->
            when {
                id == "late-a" && call == 0 -> never
                id == "late-a" -> flow {
                    lateCollecting.complete(Unit)
                    gate.await()
                    emit(NetworkResult.Success(full))
                }
                else -> never
            }
        }

        StreamPrefetchCache.prefetch(
            repo, "movie", "late-a", null, null, source = "details_hero",
            capMs = CAP_MS, republishOnDedup = true,
            rank = { pool ->
                pools += pool
                if (pool.isEmpty()) sawEmpty.complete(Unit) else sawFull.complete(Unit)
                null
            }
        )
        withTimeout(WAIT_MS) { sawEmpty.await() }
        // Wait for the late rank itself: earlier, the warmer could just yield to the hero job.
        withTimeout(WAIT_MS) { lateCollecting.await() }
        // A background warmer yields to ui work: it must not retire the late rank.
        StreamPrefetchCache.prefetch(repo, "movie", "late-bg", null, null, source = "cw", background = true)
        gate.complete(Unit)

        withTimeout(WAIT_MS) { sawFull.await() }
        assertEquals(listOf(emptyList(), full), pools.toList())
    }

    @Test
    fun `a newer ui prefetch cancels the late re-rank`() = runBlocking {
        val pools = Collections.synchronizedList(mutableListOf<List<AddonStreams>>())
        val sawEmpty = CompletableDeferred<Unit>()
        val lateCollecting = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val repo = FakeRepo { id, call ->
            when {
                id == "late-x" && call == 0 -> never
                id == "late-x" -> flow {
                    lateCollecting.complete(Unit)
                    gate.await()
                    emit(NetworkResult.Success(groups("slow-addon")))
                }
                else -> never
            }
        }

        StreamPrefetchCache.prefetch(
            repo, "movie", "late-x", null, null, source = "details_hero",
            capMs = CAP_MS, republishOnDedup = true,
            rank = { pool ->
                pools += pool
                if (pool.isEmpty()) sawEmpty.complete(Unit)
                null
            }
        )
        withTimeout(WAIT_MS) { sawEmpty.await() }
        withTimeout(WAIT_MS) { lateCollecting.await() }
        // The user moved to another target: the left one must not rank (or prewarm) late.
        StreamPrefetchCache.prefetch(repo, "movie", "late-y", null, null, source = "details_hero")
        gate.complete(Unit)

        delay(GRACE_MS)
        assertEquals(listOf(emptyList<AddonStreams>()), pools.toList())
    }

    @Test
    fun `cancelling a joined collector does not start the live fallback`() = runBlocking {
        val calls = AtomicInteger()
        val prefetchCalled = CompletableDeferred<Unit>()
        val partialsCalled = CompletableDeferred<Unit>()
        val repo = FakeRepo { _, _ ->
            if (calls.incrementAndGet() == 2) partialsCalled.complete(Unit)
            prefetchCalled.complete(Unit)
            never
        }
        StreamPrefetchCache.prefetch(repo, "movie", "cancel-join", null, null, source = "test")
        withTimeout(WAIT_MS) { prefetchCalled.await() }

        val atJoin = CompletableDeferred<Unit>()
        val collector = launch {
            StreamPrefetchCache.streamsFor(repo, "movie", "cancel-join", null, null)
                .collect { atJoin.complete(Unit) }
        }
        withTimeout(WAIT_MS) { atJoin.await() }
        // Call 2 is the join's partials collector (the shared session in production).
        withTimeout(WAIT_MS) { partialsCalled.await() }
        collector.cancelAndJoin()
        delay(GRACE_MS)

        assertEquals(2, calls.get())
    }

    private companion object {
        const val WAIT_MS = 5_000L
        /** Grace for a wrongly launched rank to reach the IO dispatcher. */
        const val GRACE_MS = 200L
        /** Prefetch completion cap for the F-7 tests; the capped collect sees nothing. */
        const val CAP_MS = 50L
    }
}
