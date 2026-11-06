package com.nuvio.tv.core.network

import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FR-10/25: aborting a sweep must stop the probes' blocking OkHttp I/O
 * (Call.executeCancellable), not leave it running to the 15 s read timeout or
 * the 8 s measure deadline. Real time on Dispatchers.IO by necessity: the
 * canceller must run on another thread while the probe's thread is blocked in
 * a socket read, which a single-threaded virtual-time dispatcher cannot do.
 */
class StreamSpeedTesterCancellationTest {

    private val server = MockWebServer()

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test(timeout = 10_000)
    fun `baseline probe cancelled mid-body stops promptly`() = runBlocking {
        // One byte arrives, then the body stalls far past every bound below.
        server.enqueue(
            MockResponse()
                .setBody(Buffer().write(ByteArray(2)))
                .throttleBody(1, STALL_SECONDS, TimeUnit.SECONDS)
        )
        val url = server.url("/stream").toString()

        assertCancelsPromptly { StreamSpeedTester.runBaselineTest(url, emptyMap()) }
    }

    @Test(timeout = 10_000)
    fun `content-length probe cancelled while awaiting HEAD headers stops promptly`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val url = server.url("/stream").toString()

        assertCancelsPromptly { StreamSpeedTester.getStreamContentLength(url, emptyMap()) }
    }

    private suspend fun assertCancelsPromptly(probe: suspend () -> Any) = kotlinx.coroutines.coroutineScope {
        val running = async(Dispatchers.IO) { probe() }
        // The server has the request: the probe is (about to be) blocked on the socket.
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        delay(SETTLE_MS)

        val cancelMs = measureTimeMillis { running.cancelAndJoin() }

        assertTrue(running.isCancelled)
        assertTrue("cancel took ${cancelMs}ms", cancelMs < CANCEL_BOUND_MS)
    }

    private companion object {
        const val STALL_SECONDS = 30L
        const val SETTLE_MS = 300L
        // Far below the probes' 8 s measure window and 15 s read timeout.
        const val CANCEL_BOUND_MS = 2_000L
    }
}
