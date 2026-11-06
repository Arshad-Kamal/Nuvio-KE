package com.nuvio.tv.core.network

import com.nuvio.tv.core.player.LastPlaybackDiagnostics
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** K9: the one bitrate estimate behind Device assessment and Advanced settings' stream test. */
class StreamSweepEngineEstimateBitrateTest {

    private val server = MockWebServer()

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `format bitrate wins without probing`() = runBlocking {
        val diagnostics = LastPlaybackDiagnostics(
            streamUrl = server.url("/stream").toString(),
            videoBitrate = 42_000_000,
            durationMs = 100_000L
        )

        assertEquals(42_000_000L, StreamSweepEngine.estimateBitrate(diagnostics, emptyMap()))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `no stream url or no duration gives null`() = runBlocking {
        assertNull(StreamSweepEngine.estimateBitrate(LastPlaybackDiagnostics(durationMs = 100_000L), emptyMap()))
        assertNull(
            StreamSweepEngine.estimateBitrate(
                LastPlaybackDiagnostics(streamUrl = " ", durationMs = 100_000L),
                emptyMap()
            )
        )
        assertNull(
            StreamSweepEngine.estimateBitrate(
                LastPlaybackDiagnostics(streamUrl = server.url("/stream").toString()),
                emptyMap()
            )
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `size over duration from the HEAD content length`() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Length", "125000000"))
        val diagnostics = LastPlaybackDiagnostics(
            streamUrl = server.url("/stream").toString(),
            durationMs = 100_000L
        )

        // 125 MB * 8 bits over 100 s.
        assertEquals(10_000_000L, StreamSweepEngine.estimateBitrate(diagnostics, emptyMap()))
    }

    @Test
    fun `failed size probe gives null`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(404))
        val diagnostics = LastPlaybackDiagnostics(
            streamUrl = server.url("/stream").toString(),
            durationMs = 100_000L
        )

        assertNull(StreamSweepEngine.estimateBitrate(diagnostics, emptyMap()))
    }
}
