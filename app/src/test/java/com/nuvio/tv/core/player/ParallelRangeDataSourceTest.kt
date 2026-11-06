package com.nuvio.tv.core.player

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ParallelRangeDataSourceTest {

    // Releases any fake upstream a test left blocked on a download worker.
    private val upstreamRelease = CountDownLatch(1)

    // The chunk session and buffer pool are process-wide (companion) state:
    // every test starts and ends from a shut-down player.
    @Before
    fun startFromShutdown() {
        ParallelRangeDataSource.releaseRetainedSession()
    }

    @After
    fun shutDown() {
        upstreamRelease.countDown()
        ParallelRangeDataSource.releaseRetainedSession()
    }

    @Test
    fun `parseRetryAfterHeaderMs reads delta-seconds`() {
        assertEquals(30_000L, ParallelRangeRetryAfter.parseHeaderMs("30"))
        assertEquals(0L, ParallelRangeRetryAfter.parseHeaderMs("0"))
        assertEquals(1_000L, ParallelRangeRetryAfter.parseHeaderMs("1"))
    }

    @Test
    fun `parseRetryAfterHeaderMs reads RFC1123 HTTP-date`() {
        val now = 1_700_000_000_000L // fixed epoch for determinism
        val target = Instant.ofEpochMilli(now + 45_000L)
        val header = DateTimeFormatter.RFC_1123_DATE_TIME
            .withLocale(Locale.US)
            .withZone(ZoneOffset.UTC)
            .format(target)

        assertEquals(45_000L, ParallelRangeRetryAfter.parseHeaderMs(header, nowEpochMs = now))
    }

    @Test
    fun `parseRetryAfterHeaderMs returns null for missing or garbage`() {
        assertNull(ParallelRangeRetryAfter.parseHeaderMs(null))
        assertNull(ParallelRangeRetryAfter.parseHeaderMs(""))
        assertNull(ParallelRangeRetryAfter.parseHeaderMs("not-a-date"))
    }

    @Test
    fun `parseRetryAfterHeaderMs clamps past HTTP-date to zero`() {
        val now = 1_700_000_000_000L
        val past = Instant.ofEpochMilli(now - 60_000L)
        val header = DateTimeFormatter.RFC_1123_DATE_TIME
            .withLocale(Locale.US)
            .withZone(ZoneOffset.UTC)
            .format(past)

        assertEquals(0L, ParallelRangeRetryAfter.parseHeaderMs(header, nowEpochMs = now))
    }

    // -- chunk-buffer disposition truth table --

    @Test
    fun `a closed pool frees whatever cap applies`() {
        assertFalse(chunkBufferDisposition(poolOpen = false, sessionCap = null, liveCap = 7, poolSize = 0))
        assertFalse(chunkBufferDisposition(poolOpen = false, sessionCap = 5, liveCap = 7, poolSize = 0))
    }

    @Test
    fun `buffer released after player-shutdown teardown is freed even into an empty pool`() {
        assertFalse(chunkBufferDisposition(poolOpen = true, sessionCap = 0, liveCap = 7, poolSize = 0))
    }

    @Test
    fun `stream-change teardown cap wins over the live instance cap`() {
        assertTrue(chunkBufferDisposition(poolOpen = true, sessionCap = 5, liveCap = 7, poolSize = 4))
        assertFalse(chunkBufferDisposition(poolOpen = true, sessionCap = 5, liveCap = 7, poolSize = 5))
    }

    @Test
    fun `live session recycles under the instance cap until the pool is full`() {
        assertTrue(chunkBufferDisposition(poolOpen = true, sessionCap = null, liveCap = 7, poolSize = 6))
        assertFalse(chunkBufferDisposition(poolOpen = true, sessionCap = null, liveCap = 7, poolSize = 7))
        assertFalse(chunkBufferDisposition(poolOpen = true, sessionCap = null, liveCap = 0, poolSize = 0))
    }

    // -- FR-11: the real session/release wiring, driven with heap buffers --

    @Test
    fun `releaseRetainedSession empties the pool and closes it`() {
        val session = ParallelRangeDataSource.obtainSessionForTest(mockk<Uri>(), CHUNK, poolCap = 5)
        ParallelRangeDataSource.releaseHeapBufferForTest(session, livePoolCap = 5)
        ParallelRangeDataSource.releaseHeapBufferForTest(session, livePoolCap = 5)
        assertEquals(2, ParallelRangeDataSource.idleBufferCount(CHUNK))

        ParallelRangeDataSource.releaseRetainedSession()

        assertEquals(0, ParallelRangeDataSource.idleBufferCount(CHUNK))
        assertFalse(ParallelRangeDataSource.poolOpen)
    }

    @Test
    fun `a late release from a session torn down on an earlier stream change frees after shutdown`() {
        val outgoing = ParallelRangeDataSource.obtainSessionForTest(mockk<Uri>(), CHUNK, poolCap = 5)
        // A different URI tears the outgoing session down with the opener's cap (5).
        ParallelRangeDataSource.obtainSessionForTest(mockk<Uri>(), CHUNK, poolCap = 5)
        ParallelRangeDataSource.releaseRetainedSession()

        ParallelRangeDataSource.releaseHeapBufferForTest(outgoing, livePoolCap = 5)

        assertEquals(0, ParallelRangeDataSource.idleBufferCount(CHUNK))
    }

    @Test
    fun `a release into a live session pools up to the cap`() {
        val session = ParallelRangeDataSource.obtainSessionForTest(mockk<Uri>(), CHUNK, poolCap = 5)

        repeat(6) { ParallelRangeDataSource.releaseHeapBufferForTest(session, livePoolCap = 5) }

        assertTrue(ParallelRangeDataSource.poolOpen)
        assertEquals(5, ParallelRangeDataSource.idleBufferCount(CHUNK))
    }

    @Test
    fun `a session torn down with cap 0 never pools, even after a new session reopens the pool`() {
        val shutDown = ParallelRangeDataSource.obtainSessionForTest(mockk<Uri>(), CHUNK, poolCap = 5)
        ParallelRangeDataSource.releaseRetainedSession()
        ParallelRangeDataSource.obtainSessionForTest(mockk<Uri>(), CHUNK, poolCap = 5)
        assertTrue(ParallelRangeDataSource.poolOpen)

        ParallelRangeDataSource.releaseHeapBufferForTest(shutDown, livePoolCap = 5)

        assertEquals(0, ParallelRangeDataSource.idleBufferCount(CHUNK))
    }

    @Test
    fun `eviction from a torn-down session honours its recorded cap, not the evicting instance cap`() {
        val shutDown = ParallelRangeDataSource.obtainSessionForTest(mockk<Uri>(), CHUNK, poolCap = 5)
        ParallelRangeDataSource.releaseRetainedSession()
        ParallelRangeDataSource.obtainSessionForTest(mockk<Uri>(), CHUNK, poolCap = 5)

        ParallelRangeDataSource.evictCompletedHeapChunkForTest(shutDown, chunkIndex = 0L, livePoolCap = 5)

        assertEquals(0, ParallelRangeDataSource.idleBufferCount(CHUNK))
    }

    // -- Spec 56 A2: a chunk-size change never strands buffers in the old size's pool --

    @Test
    fun `a session replaced by one of another chunk size frees its late releases`() {
        val outgoing = ParallelRangeDataSource.obtainSessionForTest(mockk<Uri>(), CHUNK, poolCap = 5)
        ParallelRangeDataSource.obtainSessionForTest(mockk<Uri>(), CHUNK * 2, poolCap = 5)

        ParallelRangeDataSource.releaseHeapBufferForTest(outgoing, livePoolCap = 5)

        assertEquals(0, ParallelRangeDataSource.idleBufferCount(CHUNK))
    }

    @Test
    fun `a new chunk size drains buffers idle under the old size, a same-size switch keeps them`() {
        val first = ParallelRangeDataSource.obtainSessionForTest(mockk<Uri>(), CHUNK, poolCap = 5)
        repeat(3) { ParallelRangeDataSource.releaseHeapBufferForTest(first, livePoolCap = 5) }

        ParallelRangeDataSource.obtainSessionForTest(mockk<Uri>(), CHUNK, poolCap = 5)
        assertEquals(3, ParallelRangeDataSource.idleBufferCount(CHUNK))

        ParallelRangeDataSource.obtainSessionForTest(mockk<Uri>(), CHUNK * 2, poolCap = 5)
        assertEquals(0, ParallelRangeDataSource.idleBufferCount(CHUNK))
    }

    // -- Spec 56 A2 on the pre-start (pending) session paths --

    @Test
    fun `a pre-start replaced by one of another chunk size frees its late releases, a same-size one pools them`() {
        val first = ParallelRangeDataSource.obtainPendingSessionForTest(mockk<Uri>(), CHUNK, poolCap = 5)!!
        val second = ParallelRangeDataSource.obtainPendingSessionForTest(mockk<Uri>(), CHUNK, poolCap = 5)!!
        ParallelRangeDataSource.releaseHeapBufferForTest(first, livePoolCap = 5)
        assertEquals(1, ParallelRangeDataSource.idleBufferCount(CHUNK))

        ParallelRangeDataSource.obtainPendingSessionForTest(mockk<Uri>(), CHUNK * 2, poolCap = 5)
        ParallelRangeDataSource.releaseHeapBufferForTest(second, livePoolCap = 5)

        assertEquals(1, ParallelRangeDataSource.idleBufferCount(CHUNK))
    }

    @Test
    fun `adopting a pre-start drains buffers idle under another chunk size`() {
        val live = ParallelRangeDataSource.obtainSessionForTest(mockk<Uri>(), CHUNK * 2, poolCap = 5)
        repeat(2) { ParallelRangeDataSource.releaseHeapBufferForTest(live, livePoolCap = 5) }
        val uri = mockk<Uri>()
        val pending = ParallelRangeDataSource.obtainPendingSessionForTest(uri, CHUNK, poolCap = 5)
        assertEquals(2, ParallelRangeDataSource.idleBufferCount(CHUNK * 2))

        val opened = ParallelRangeDataSource.obtainSessionForTest(uri, CHUNK, poolCap = 5)

        assertSame(pending, opened)
        assertEquals(0, ParallelRangeDataSource.idleBufferCount(CHUNK * 2))
    }

    @Test
    fun `a pre-start discarded at open for its chunk size frees its late releases`() {
        val uri = mockk<Uri>()
        val pending = ParallelRangeDataSource.obtainPendingSessionForTest(uri, CHUNK * 2, poolCap = 5)!!

        val opened = ParallelRangeDataSource.obtainSessionForTest(uri, CHUNK, poolCap = 5)
        ParallelRangeDataSource.releaseHeapBufferForTest(pending, livePoolCap = 5)

        assertNotSame(pending, opened)
        assertEquals(0, ParallelRangeDataSource.idleBufferCount(CHUNK * 2))
    }

    // -- FR-12 / FR-13: read and open paths over a fake upstream --

    @Test
    fun `an interrupted chunk wait throws InterruptedIOException and leaves the session download running`() {
        val uri = mockk<Uri>(relaxed = true)
        val upstream = mockk<OkHttpDataSource>(relaxed = true)
        every { upstream.open(any()) } answers {
            upstreamRelease.await(10, TimeUnit.SECONDS)
            throw IOException("released")
        }
        val factory = mockk<OkHttpDataSource.Factory>()
        every { factory.createDataSource() } returns upstream
        val source = ParallelRangeDataSource(
            upstreamFactory = factory,
            chunkSize = CHUNK,
            shouldAllowBackgroundPrefetch = { false },
            consumeBootstrapCache = { warmEntry(uri, totalLength = CHUNK * 4) },
            allowContinuationReopen = false
        )
        source.open(DataSpec(uri))
        var thrown: Throwable? = null
        var interruptedAfter = false

        val reader = Thread {
            // Interrupt first: the in-flight wait and future.get() both see it at once.
            Thread.currentThread().interrupt()
            try {
                source.read(ByteArray(16), 0, 16)
            } catch (t: Throwable) {
                thrown = t
            }
            interruptedAfter = Thread.currentThread().isInterrupted
        }
        reader.start()
        reader.join(5_000)

        assertTrue("expected InterruptedIOException, got $thrown", thrown is InterruptedIOException)
        assertTrue(interruptedAfter)
        val future = ParallelRangeDataSource.currentSessionFutureForTest(0L)
        assertNotNull(future)
        assertFalse(future!!.isCancelled)
        assertFalse(future.isDone)
    }

    @Test
    fun `the probe source is closed when the bootstrap read throws`() {
        val uri = mockk<Uri>(relaxed = true)
        val probe = mockk<OkHttpDataSource>(relaxed = true)
        every { probe.open(any()) } returns 262_144L
        every { probe.uri } returns uri
        every { probe.responseCode } returns 206
        every { probe.responseHeaders } returns mapOf("Content-Range" to listOf("bytes 0-262143/1000000"))
        every { probe.read(any(), any(), any()) } throws IOException("bootstrap read failed")
        val factory = mockk<OkHttpDataSource.Factory>()
        every { factory.createDataSource() } returns probe
        val source = ParallelRangeDataSource(upstreamFactory = factory, chunkSize = CHUNK)

        assertThrows(IOException::class.java) { source.open(DataSpec(uri)) }

        verify(exactly = 1) { probe.close() }
    }

    private fun probeSource(
        codes: List<Int>,
        headers: List<Map<String, List<String>>>
    ): Pair<OkHttpDataSource, OkHttpDataSource.Factory> {
        val probe = mockk<OkHttpDataSource>(relaxed = true)
        every { probe.open(any()) } returns 1_000_000L
        every { probe.uri } returns mockk<Uri>(relaxed = true)
        every { probe.responseCode } returnsMany codes
        every { probe.responseHeaders } returnsMany headers
        val factory = mockk<OkHttpDataSource.Factory>()
        every { factory.createDataSource() } returns probe
        return probe to factory
    }

    @Test
    fun `an ignored bounded Range stays single even when Accept-Ranges is advertised`() {
        val acceptRanges = mapOf("Accept-Ranges" to listOf("bytes"))
        val (probe, factory) = probeSource(listOf(200, 200), listOf(acceptRanges, acceptRanges))
        val source = ParallelRangeDataSource(upstreamFactory = factory, chunkSize = CHUNK)

        assertEquals(1_000_000L, source.open(DataSpec(mockk<Uri>(relaxed = true))))

        // Bounded probe then one unbounded reopen; no bootstrap read = no parallel mode.
        verify(exactly = 2) { probe.open(any()) }
        verify(exactly = 0) { probe.read(any(), any(), any()) }
    }

    @Test
    fun `a probe 206 for another window throws fast-fail and closes the probe`() {
        val (probe, factory) = probeSource(
            listOf(206),
            listOf(mapOf("Content-Range" to listOf("bytes 4096-266239/1000000")))
        )
        val source = ParallelRangeDataSource(upstreamFactory = factory, chunkSize = CHUNK)

        assertThrows(InvalidRangeResponseException::class.java) { source.open(DataSpec(mockk<Uri>(relaxed = true))) }

        verify(exactly = 1) { probe.close() }
    }

    @Test
    fun `the unbounded reopen is validated too`() {
        val (probe, factory) = probeSource(
            listOf(200, 206),
            listOf(emptyMap(), mapOf("Content-Range" to listOf("bytes 10-999999/1000000")))
        )
        val source = ParallelRangeDataSource(upstreamFactory = factory, chunkSize = CHUNK)

        assertThrows(InvalidRangeResponseException::class.java) { source.open(DataSpec(mockk<Uri>(relaxed = true))) }

        verify(exactly = 2) { probe.open(any()) }
    }

    private fun warmEntry(uri: Uri, totalLength: Long) = ParallelRangeDataSource.BootstrapCacheEntry(
        requestUri = uri,
        startPosition = 0L,
        resolvedUri = null,
        openLength = totalLength,
        totalFileLength = totalLength,
        bootstrapData = ByteArray(0),
        bootstrapSize = 0,
        createdAtUptimeMs = 0L
    )

    private companion object {
        const val CHUNK = 4_096L
    }
}
