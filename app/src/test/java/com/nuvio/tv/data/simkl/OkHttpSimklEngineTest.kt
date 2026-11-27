package com.nuvio.tv.data.simkl

import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 106 S1: the real OkHttp transport (real time: cancellation needs another thread, as in StreamSpeedTesterCancellationTest). */
class OkHttpSimklEngineTest {

    private val server = MockWebServer()
    private val engine = OkHttpSimklEngine(OkHttpClient())

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test(timeout = 10_000)
    fun `a reply of any status comes back with lower-case headers and the request as sent`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("X-RateLimit-Remaining", "0").setBody("""{"error":"x"}"""))
        val raw = engine.execute(SimklHttpMethod.POST, server.url("/sync/history").toString(), mapOf("User-Agent" to "NuvioKE/1"), "{}")

        assertEquals(429, raw.status)
        assertEquals("""{"error":"x"}""", raw.body)
        assertEquals("0", raw.headers["x-ratelimit-remaining"])
        val sent = server.takeRequest()
        assertEquals("POST", sent.method)
        assertEquals("NuvioKE/1", sent.getHeader("User-Agent"))
        assertEquals("{}", sent.body.readUtf8())
    }

    @Test(timeout = 10_000)
    fun `a declared length over the cap is refused`() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Length", SIMKL_MAX_BODY_BYTES + 1).setSocketPolicy(SocketPolicy.DISCONNECT_AT_END))
        assertFailsSuspend<SimklReplyTooLargeException> {
            engine.execute(SimklHttpMethod.GET, server.url("/sync/all-items").toString(), emptyMap(), null)
        }
        Unit
    }

    @Test(timeout = 10_000)
    fun `cancelling the caller stops a call Simkl never answers`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val running = async(Dispatchers.IO) {
            engine.execute(SimklHttpMethod.POST, server.url("/oauth2/revoke").toString(), emptyMap(), "{}")
        }
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        delay(300)
        val cancelMs = measureTimeMillis { running.cancelAndJoin() }
        assertTrue(running.isCancelled)
        assertTrue("cancel took ${cancelMs}ms", cancelMs < 2_000)
    }
}
