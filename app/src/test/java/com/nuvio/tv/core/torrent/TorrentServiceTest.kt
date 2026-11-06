package com.nuvio.tv.core.torrent

import android.content.Context
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * B21/B22: stopStream()/shutdown() are called from the main thread, so the TorrServer HTTP
 * drop/stop must run off the caller; a following startStream() must still wait for it.
 * T-TS: a session is reserved by beginSession() before the start suspends, so its owner's
 * teardown reaches whatever the start added, and stale owners never touch a newer session.
 */
class TorrentServiceTest {

    private val context = mockk<Context>(relaxed = true)
    private val binary = mockk<TorrServerBinary>()
    private val api = mockk<TorrServerApi>()
    private val torrentSettings = mockk<TorrentSettings>()
    private val service = TorrentService(context, binary, api, torrentSettings)

    private val stats = TorrServerStats(
        downloadSpeed = 0, uploadSpeed = 0, peers = 0, seeds = 0, preloadedBytes = 0,
        loadedSize = 0, torrentSize = 0,
        files = listOf(TorrServerFile(id = 1, path = "movie.mkv", length = 100))
    )

    init {
        every { torrentSettings.settings } returns flowOf(TorrentSettingsData(p2pEnabled = true))
        coJustRun { binary.start() }
        coJustRun { binary.stop() }
        coEvery { api.addTorrent(any()) } returns HASH
        coEvery { api.getTorrentStats(any()) } returns stats
        every { api.getStreamUrl(any(), any()) } returns URL
    }

    private suspend fun start(hash: String = HASH): Long {
        val session = service.beginSession()
        service.startStream(session, hash, fileIdx = 0)
        return session
    }

    @Test
    fun `stopStream returns without waiting for the torrent drop`() = runBlocking {
        val session = start()
        val release = CountDownLatch(1)
        coEvery { api.dropTorrent(HASH) } coAnswers { release.await() }

        val startNs = System.nanoTime()
        service.stopStream(session)
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)

        assertTrue("stopStream blocked for ${elapsedMs}ms", elapsedMs < 1_000)
        assertEquals(TorrentState.Idle, service.state.value)
        release.countDown()
        coVerify(timeout = 2_000) { api.dropTorrent(HASH) }
    }

    @Test
    fun `startStream waits for a pending drop before re-adding the same torrent`() = runBlocking {
        val events = Collections.synchronizedList(mutableListOf<String>())
        coEvery { api.addTorrent(any()) } coAnswers { events += "add"; HASH }
        coEvery { api.dropTorrent(HASH) } coAnswers { Thread.sleep(200); events += "drop" }

        val first = start()
        service.stopStream(first)
        val second = start()

        assertEquals(listOf("add", "drop", "add"), events.toList())
        service.stopStream(second)
    }

    @Test
    fun `shutdown drops the torrent then stops the server without blocking`() = runBlocking {
        coJustRun { api.dropTorrent(HASH) }
        val session = start()

        service.shutdown(session)

        assertEquals(TorrentState.Idle, service.state.value)
        coVerify(timeout = 2_000) { binary.stop() }
        coVerifyOrder {
            api.dropTorrent(HASH)
            binary.stop()
        }
    }

    @Test
    fun `startStream after shutdown restarts the server once the stop finished`() = runBlocking {
        val events = Collections.synchronizedList(mutableListOf<String>())
        coEvery { binary.stop() } coAnswers { Thread.sleep(200); events += "stop" }
        coEvery { binary.start() } coAnswers { events += "start" }

        service.shutdown(service.beginSession())
        val session = start()

        assertEquals(listOf("stop", "start"), events.toList())
        coJustRun { api.dropTorrent(HASH) }
        service.stopStream(session)
    }

    @Test
    fun `stale session teardown does not touch a newer session`() = runBlocking {
        coJustRun { api.dropTorrent(any()) }
        val oldSession = start()
        val newSession = start()

        service.stopStream(oldSession)
        service.shutdown(oldSession)
        Thread.sleep(300)

        coVerify(exactly = 0) { binary.stop() }
        assertTrue(service.state.value is TorrentState.Streaming)
        service.shutdown(newSession)
        coVerify(timeout = 2_000) { binary.stop() }
    }

    @Test
    fun `start cancelled while waiting for metadata is torn down by its session`() = runBlocking {
        coJustRun { api.dropTorrent(HASH) }
        val inMetadata = CountDownLatch(1)
        coEvery { api.getTorrentStats(HASH) } coAnswers { inMetadata.countDown(); awaitCancellation() }

        // The player stores the session before launching the start (B22 regression: it used to
        // learn it only when startStream returned, so a cancelled start was never torn down).
        val session = service.beginSession()
        val startJob = launch(Dispatchers.IO) { service.startStream(session, HASH, fileIdx = 0) }
        assertTrue(inMetadata.await(2, TimeUnit.SECONDS))

        startJob.cancelAndJoin()
        service.stopStream(session)
        service.shutdown(session)

        coVerify(timeout = 2_000) { binary.stop() }
        coVerifyOrder {
            api.dropTorrent(HASH)
            binary.stop()
        }
        coVerify(exactly = 1) { api.dropTorrent(HASH) }
        assertEquals(TorrentState.Idle, service.state.value)
    }

    @Test
    fun `superseded start drops its own torrent and the newer start proceeds`() = runBlocking {
        coJustRun { api.dropTorrent(any()) }
        val events = Collections.synchronizedList(mutableListOf<String>())
        val addingOld = CountDownLatch(1)
        val releaseOldAdd = CompletableDeferred<Unit>()
        coEvery { api.addTorrent(match { HASH in it }) } coAnswers {
            addingOld.countDown()
            releaseOldAdd.await()
            events += "add-old"
            HASH
        }
        coEvery { api.addTorrent(match { OTHER_HASH in it }) } coAnswers {
            events += "add-new"
            OTHER_HASH
        }
        coEvery { api.dropTorrent(any()) } coAnswers { events += "drop-${firstArg<String>()}" }

        val oldSession = service.beginSession()
        val oldStart = async(Dispatchers.IO) { service.startStream(oldSession, HASH, fileIdx = 0) }
        assertTrue(addingOld.await(2, TimeUnit.SECONDS))

        val newSession = service.beginSession()
        val newStart = async(Dispatchers.IO) { service.startStream(newSession, OTHER_HASH, fileIdx = 0) }
        releaseOldAdd.complete(Unit)

        try {
            oldStart.await()
            fail("superseded start must not return a stream")
        } catch (expected: CancellationException) {
        }
        assertEquals(URL, withTimeout(2_000) { newStart.await() })

        // The old start's drop is ordered before the newer start's add (it may be the same hash).
        assertEquals(listOf("add-old", "drop-$HASH", "add-new"), events.toList())
        assertTrue(service.state.value is TorrentState.Streaming)

        // The old start never claimed currentHash: the owner's stop drops only the newer torrent.
        service.stopStream(newSession)
        coVerify(timeout = 2_000) { api.dropTorrent(OTHER_HASH) }
        coVerify(exactly = 1) { api.dropTorrent(HASH) }
    }

    @Test
    fun `stale shutdown during a start parked on a slow drop never stops the server`() = runBlocking {
        val releaseDrop = CountDownLatch(1)
        coEvery { api.dropTorrent(HASH) } coAnswers { releaseDrop.await() }
        val oldSession = start()

        val newSession = service.beginSession() // queues the slow drop of the old torrent
        val newStart = async(Dispatchers.IO) { service.startStream(newSession, HASH, fileIdx = 0) }
        Thread.sleep(100) // let the new start park on the pending drop
        service.shutdown(oldSession) // late release of the superseded owner
        releaseDrop.countDown()

        assertEquals(URL, withTimeout(2_000) { newStart.await() })
        Thread.sleep(200)
        coVerify(exactly = 0) { binary.stop() }
        assertTrue(service.state.value is TorrentState.Streaming)
        service.stopStream(newSession)
    }

    @Test
    fun `start cancelled during a slow add still drops the torrent the server added`() = runBlocking {
        coJustRun { api.dropTorrent(HASH) }
        val adding = CountDownLatch(1)
        val releaseAdd = CountDownLatch(1)
        // Like the real client: a blocking request on another dispatcher that completes on the
        // server even when the caller is cancelled (withContext then discards the result).
        coEvery { api.addTorrent(any()) } coAnswers {
            withContext(Dispatchers.Default) {
                adding.countDown()
                releaseAdd.await(2, TimeUnit.SECONDS)
                HASH
            }
        }

        val session = service.beginSession()
        val startJob = launch(Dispatchers.IO) { service.startStream(session, HASH, fileIdx = 0) }
        assertTrue(adding.await(2, TimeUnit.SECONDS))

        // The player's stopTorrentStream: cancel the start job, then stop its session.
        startJob.cancel()
        service.stopStream(session)
        releaseAdd.countDown()
        startJob.join()

        coVerify(timeout = 2_000) { api.dropTorrent(HASH) }
        coVerify(exactly = 1) { api.dropTorrent(HASH) }
        assertEquals(TorrentState.Idle, service.state.value)
    }

    @Test
    fun `owner shutdown racing the server start still stops the started server`() = runBlocking {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val starting = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        coEvery { binary.start() } coAnswers {
            starting.countDown()
            releaseStart.await(2, TimeUnit.SECONDS)
            events += "start-done"
        }
        // A stop that lands before the process exists is a no-op on the real binary.
        coEvery { binary.stop() } coAnswers { events += "stop" }
        coEvery { api.dropTorrent(HASH) } coAnswers { events += "drop" }

        val session = service.beginSession()
        val start = async(Dispatchers.IO) { service.startStream(session, HASH, fileIdx = 0) }
        assertTrue(starting.await(2, TimeUnit.SECONDS))
        service.shutdown(session)
        coVerify(timeout = 2_000) { binary.stop() }
        releaseStart.countDown()

        try {
            start.await()
            fail("a start whose session was shut down must not return a stream")
        } catch (expected: CancellationException) {
        }
        withTimeout(2_000) { while (events.size < 4) delay(10) }

        assertEquals(listOf("stop", "start-done", "drop", "stop"), events.toList())
        assertEquals(TorrentState.Idle, service.state.value)
    }

    @Test
    fun `failure after the torrent was added drops it and reports Error`() = runBlocking {
        coJustRun { api.dropTorrent(HASH) }
        every { api.getStreamUrl(any(), any()) } throws IllegalStateException("boom")

        val session = service.beginSession()
        try {
            service.startStream(session, HASH, fileIdx = 0)
            fail("startStream must rethrow the failure")
        } catch (expected: IllegalStateException) {
        }

        coVerify(timeout = 2_000) { api.dropTorrent(HASH) }
        assertEquals(TorrentState.Error("boom"), service.state.value)
    }

    @Test
    fun `startStream with P2P off throws before starting the server or adding a torrent`() = runBlocking {
        every { torrentSettings.settings } returns flowOf(TorrentSettingsData(p2pEnabled = false))

        val session = service.beginSession()
        try {
            service.startStream(session, HASH, fileIdx = 0)
            fail("startStream must refuse while P2P is off")
        } catch (expected: TorrentException) {
        }

        coVerify(exactly = 0) { binary.start() }
        coVerify(exactly = 0) { api.addTorrent(any()) }
        assertTrue(service.state.value is TorrentState.Error)
    }

    @Test
    fun `startStream rejects an info hash that is not a btih`() = runBlocking {
        for (bad in listOf("", "abc123", "$HASH&tr=udp://evil", "g".repeat(40), "1".repeat(32))) {
            try {
                service.startStream(service.beginSession(), bad, fileIdx = 0)
                fail("startStream must reject info hash '$bad'")
            } catch (expected: TorrentException) {
            }
        }

        coVerify(exactly = 0) { binary.start() }
        coVerify(exactly = 0) { api.addTorrent(any()) }
    }

    @Test
    fun `startStream accepts hex and base32 hashes and encodes each tracker`() = runBlocking {
        coJustRun { api.dropTorrent(any()) }
        val magnet = slot<String>()
        coEvery { api.addTorrent(capture(magnet)) } returns HASH

        val base32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val session = service.beginSession()
        service.startStream(session, base32, fileIdx = 0, trackers = listOf("udp://t.example:80/announce&xt=urn:btih:$OTHER_HASH"))
        service.stopStream(session)

        val params = magnet.captured.substringAfter('?').split('&')
        assertEquals(listOf("xt=urn:btih:$base32"), params.filter { it.startsWith("xt=") })
        assertTrue(params.drop(1).all { it.startsWith("tr=") && ':' !in it })
        assertTrue("tr=udp%3A%2F%2Ft.example%3A80%2Fannounce%26xt%3Durn%3Abtih%3A$OTHER_HASH" in params)

        start(HASH.uppercase())
        assertTrue(magnet.captured.startsWith("magnet:?xt=urn:btih:${HASH.uppercase()}&"))
    }

    @Test
    fun `totalProgress is the loaded fraction and 0 while the size is unknown`() {
        assertEquals(0.25f, stats.copy(loadedSize = 50, torrentSize = 200).totalProgress, 0f)
        assertEquals(0f, stats.copy(loadedSize = 50, torrentSize = 0).totalProgress, 0f)
        assertEquals(1f, stats.copy(loadedSize = 300, torrentSize = 200).totalProgress, 0f)
    }

    private companion object {
        const val HASH = "abcdef0123456789abcdef0123456789abcdef01"
        const val OTHER_HASH = "0123456789abcdef0123456789abcdef01234567"
        const val URL = "http://127.0.0.1:8091/stream?link=x&index=1&play"
    }
}
