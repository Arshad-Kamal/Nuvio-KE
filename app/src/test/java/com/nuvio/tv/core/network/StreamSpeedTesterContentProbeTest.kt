package com.nuvio.tv.core.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec 33 D4: the content-length probe also reports the URL that answered after redirects. */
class StreamSpeedTesterContentProbeTest {

    private val server = MockWebServer()

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `HEAD after a redirect reports the serving URL`() = runBlocking {
        val serving = server.url("/cdn/file.mkv").toString()
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", serving))
        server.enqueue(MockResponse().setHeader("Content-Length", "125000000"))

        val probe = StreamSpeedTester.getStreamContentLength(server.url("/resolve/abc").toString(), emptyMap())

        assertEquals(StreamSpeedTester.ContentProbe(125_000_000L, serving), probe)
    }

    @Test
    fun `GET fallback reports the serving URL`() = runBlocking {
        val serving = server.url("/cdn/file.mkv").toString()
        server.enqueue(MockResponse().setResponseCode(405))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", serving))
        server.enqueue(MockResponse().setBody("0123456789"))

        val probe = StreamSpeedTester.getStreamContentLength(server.url("/resolve/abc").toString(), emptyMap())

        assertEquals(StreamSpeedTester.ContentProbe(10L, serving), probe)
    }

    @Test
    fun `failed probe reports zero and the request URL`() = runBlocking {
        val url = server.url("/gone").toString()
        server.shutdown() // nothing listens on the port now

        assertEquals(StreamSpeedTester.ContentProbe(0L, url), StreamSpeedTester.getStreamContentLength(url, emptyMap()))
    }
}
