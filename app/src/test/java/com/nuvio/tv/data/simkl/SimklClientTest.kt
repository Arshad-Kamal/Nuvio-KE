package com.nuvio.tv.data.simkl

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 106 S1a: identity on every call, pacing, and which failures are retried. */
class SimklClientTest {

    private val engine = FakeSimklEngine()
    private val clock = FakeClock()
    private val client = simklClient(engine, clock)
    private val get = SimklRequest(SimklHttpMethod.GET, "/sync/activities")
    private val post = SimklRequest(SimklHttpMethod.POST, "/sync/history", jsonBody = "{}")

    @Test
    fun `every call carries the app identity and only a user call carries a token`() = runTest {
        engine.reply(200)
        engine.reply(200)
        client.execute(SimklRequest(SimklHttpMethod.GET, "/movies/53736", query = mapOf("client_id" to "spoofed")), null)
        client.execute(get, "simkl_at_token")

        val (catalog, user) = engine.calls
        assertEquals("/movies/53736", catalog.path)
        assertEquals("client-v2", catalog.query("client_id"))
        assertEquals("nuvio-ke", catalog.query("app-name"))
        assertEquals("1.3.0-ke100", catalog.query("app-version"))
        assertEquals("NuvioKE/1.3.0-ke100", catalog.headers["User-Agent"])
        assertNull(catalog.headers["Authorization"])
        assertEquals("Bearer simkl_at_token", user.headers["Authorization"])
    }

    @Test
    fun `writes are spaced a second apart and reads a tenth`() = runTest {
        repeat(4) { engine.reply(200) }
        client.execute(post, "t")
        client.execute(post, "t")
        client.execute(get, "t")
        client.execute(get, "t")
        assertEquals(listOf(1_000L, 100L), clock.sleeps)
    }

    @Test
    fun `a redirect is the answer only when the request says so`() = runTest {
        val location = mapOf("Location" to "https://simkl.com/movies/472214/inception")
        engine.reply(301, "", location)
        engine.reply(301, "", location)
        val redirect = SimklRequest(SimklHttpMethod.GET, "/redirect", mapOf("imdb" to "tt1375666"), redirectIsAnswer = true)
        val answer = client.execute(redirect, null)
        assertEquals(301, answer.status)
        assertEquals("https://simkl.com/movies/472214/inception", answer.headers["location"])

        val refused = assertFailsSuspend<SimklApiException> { client.execute(redirect.copy(redirectIsAnswer = false), null) }
        assertEquals(301, refused.status)
        assertEquals(2, engine.calls.size)
    }

    @Test
    fun `server errors are retried with backoff`() = runTest {
        engine.reply(503)
        engine.reply(502)
        engine.reply(200, """{"ok":true}""")
        assertEquals("""{"ok":true}""", client.execute(get, "t").body)
        assertEquals(listOf(1_000L, 2_000L), clock.sleeps.filter { it >= 1_000L })
    }

    @Test
    fun `a per-second limit is retried after about a second`() = runTest {
        engine.reply(429, """{"error":"rate_limit"}""")
        engine.reply(201)
        assertEquals(201, client.execute(post, "t").status)
        assertTrue(1_100L in clock.sleeps)
    }

    @Test
    fun `the daily limit and request errors are not retried`() = runTest {
        engine.reply(429, """{"error":"user_limit_exceeded","message":"x"}""", mapOf("Retry-After" to "3600", "X-RateLimit-Limit" to "500"))
        val daily = assertFailsSuspend<SimklApiException> { client.execute(get, "t") }
        assertEquals(429, daily.status)
        assertEquals("user_limit_exceeded", daily.error)
        assertEquals(3_600L, daily.retryAfterSeconds)
        assertEquals(500, daily.rateLimitLimit)

        for (status in listOf(400, 401, 403, 404, 409, 412)) engine.reply(status, """{"error":"e$status"}""")
        repeat(6) { assertFailsSuspend<SimklApiException> { client.execute(get, "t") } }
        assertEquals(7, engine.calls.size)
    }

    @Test
    fun `a dropped connection is retried for a read but never for a write`() = runTest {
        engine.fail()
        engine.reply(200)
        assertEquals(200, client.execute(get, "t").status)

        engine.fail()
        assertFailsSuspend<SimklException> { client.execute(post, "t") }
        assertEquals(3, engine.calls.size)
    }

    @Test
    fun `retries give up after three, backing off 1, 2 and 4 seconds`() = runTest {
        repeat(4) { engine.reply(500) }
        val failure = assertFailsSuspend<SimklApiException> { client.execute(get, "t") }
        assertEquals(500, failure.status)
        assertEquals(4, engine.calls.size)
        assertEquals(listOf(1_000L, 2_000L, 4_000L), clock.sleeps.filter { it >= 1_000L })

        repeat(4) { engine.fail() }
        assertFailsSuspend<SimklException> { client.execute(get, "t") }
        assertEquals(8, engine.calls.size)
    }

    @Test
    fun `an error body that is not Simkl's envelope gives no code and never echoes the body`() = runTest {
        engine.reply(400, "<html>simkl_at_secret</html>")
        val failure = assertFailsSuspend<SimklApiException> { client.execute(get, "t") }
        assertNull(failure.error)
        assertFalse(failure.message!!.contains("secret"))
    }

    @Test
    fun `a reply over the size cap is not downloaded again`() = runTest {
        val tooLarge = object : SimklHttpEngine {
            var calls = 0
            override suspend fun execute(method: SimklHttpMethod, url: String, headers: Map<String, String>, body: String?): SimklRawResponse {
                calls++
                throw SimklReplyTooLargeException()
            }
        }
        val client = SimklClient(tooLarge, TEST_CONFIG, monotonicMs = { clock.now }, sleep = clock.sleep, jitterMs = { 0L })
        assertFailsSuspend<SimklReplyTooLargeException> { client.execute(get, "t") }
        assertEquals(1, tooLarge.calls)
    }

    @Test
    fun `the body reader stops at the cap`() {
        val exact = okio.Buffer().writeUtf8("x".repeat(10))
        assertEquals(10, readSimklBody(exact, -1L, maxBytes = 10).length)
        assertFailsSuspend<SimklReplyTooLargeException> { readSimklBody(okio.Buffer().writeUtf8("x".repeat(11)), -1L, maxBytes = 10) }
        assertFailsSuspend<SimklReplyTooLargeException> { readSimklBody(okio.Buffer(), 11L, maxBytes = 10) }
    }

    @Test
    fun `beforeSend runs under the lock and can stop the call`() = runTest {
        assertFailsSuspend<SimklQuotaExceededException> {
            client.execute(get, "t", beforeSend = { throw SimklQuotaExceededException(1L) })
        }
        assertTrue(engine.calls.isEmpty())
    }

    @Test
    fun `only one call is in flight at a time`() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        engine.hold = gate
        repeat(3) { engine.reply(200) }
        val jobs = List(3) { launch { client.execute(get, "t") } }
        testScheduler.runCurrent()
        assertEquals(1, engine.calls.size)
        gate.complete(Unit)
        jobs.forEach { it.join() }
        assertEquals(1, engine.maxInFlight)
        assertEquals(3, engine.calls.size)
    }

    @Test
    fun `a path can not climb to another endpoint and requests never print their body`() {
        for (bad in listOf("/movies/../sync/history", "/tv/./1", "/tv/%2e%2e", "/tv/1?x=1", "/tv/1#a", "/tv/..\\sync", "/tv\\..", "tv/1", " /tv/1", "/tv//1", "/")) {
            assertFailsSuspend<IllegalArgumentException> { SimklRequest(SimklHttpMethod.GET, bad) }
        }
        val secret = SimklRequest(SimklHttpMethod.POST, "/oauth2/token", jsonBody = """{"refresh_token":"simkl_rt_secret"}""")
        assertFalse(secret.toString().contains("simkl_rt_secret"))
        assertFalse(SimklResponse(200, """{"access_token":"simkl_at_secret"}""", emptyMap()).toString().contains("secret"))
    }
}
