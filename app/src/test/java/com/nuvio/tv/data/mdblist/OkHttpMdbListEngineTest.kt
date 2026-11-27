package com.nuvio.tv.data.mdblist

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec 105 S1a: the real OkHttp transport sends a write once, whatever OkHttp's own follow-ups would do. */
class OkHttpMdbListEngineTest {

    private val server = MockWebServer()
    private val engine = OkHttpMdbListEngine(OkHttpClient())

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test(timeout = 10_000)
    fun `a write answered 503 with Retry-After 0 is not replayed by OkHttp`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val raw = engine.execute(MdbListHttpMethod.POST, server.url("/scrobble/stop").toString(), emptyMap(), """{"progress":90}""")
        assertEquals(503, raw.status)
        assertEquals(1, server.requestCount)
        assertEquals("""{"progress":90}""", server.takeRequest().body.readUtf8())
    }

    @Test(timeout = 10_000)
    fun `a write redirected with 307 is not resent`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "/elsewhere"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val raw = engine.execute(MdbListHttpMethod.POST, server.url("/sync/watched").toString(), emptyMap(), "{}")
        assertEquals(307, raw.status)
        assertEquals(1, server.requestCount)
    }

    @Test(timeout = 10_000)
    fun `a read comes back with lower-case headers`() = runBlocking {
        server.enqueue(MockResponse().setHeader("X-RateLimit-Remaining", "12").setBody("""{"username":"a"}"""))
        val raw = engine.execute(MdbListHttpMethod.GET, server.url("/user").toString(), mapOf("User-Agent" to "NuvioKE/1"), null)
        assertEquals(200, raw.status)
        assertEquals("12", raw.headers["x-ratelimit-remaining"])
        assertEquals("NuvioKE/1", server.takeRequest().getHeader("User-Agent"))
    }
}
