package com.nuvio.tv.data.mdblist

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 105 S1a: the request shape, what is retried and what fails at once. */
class MdbListClientTest {

    private val engine = FakeMdbListEngine()
    private val clock = FakeClock()
    private val client = mdbListClient(engine, clock)
    private val get = MdbListRequest(MdbListHttpMethod.GET, "/sync/watched")
    private val post = MdbListRequest(MdbListHttpMethod.POST, "/scrobble/start", jsonBody = """{"progress":1}""")

    @Test
    fun `the key goes as apikey and a caller's own apikey query is dropped`() = runTest {
        engine.reply(200)
        client.execute(MdbListRequest(MdbListHttpMethod.GET, "/user", query = mapOf("apikey" to "other", "page" to "2")), apiKey = { "key-1234567" })
        val call = engine.calls.single()
        assertEquals("/user", call.path)
        assertEquals("key-1234567", call.query("apikey"))
        assertEquals("2", call.query("page"))
        assertEquals(1, call.url.split("apikey=").size - 1)
        assertEquals("NuvioKE/1.3.0-ke100", call.headers["User-Agent"])
        assertNull(call.headers["Content-Type"])
    }

    @Test
    fun `a request never prints its body and an unsafe path is refused`() {
        assertEquals("MdbListRequest(POST /scrobble/start)", post.toString())
        listOf("/user/../lists", "/user?x=1", "user", "/a/b.json", "//user", "/user/%2e").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { MdbListRequest(MdbListHttpMethod.GET, path) }
        }
        MdbListRequest(MdbListHttpMethod.GET, "/oauth/token/")
    }

    @Test
    fun `server errors are retried with backoff then fail`() = runTest {
        repeat(4) { engine.reply(503) }
        val failure = failsWith<MdbListApiException> { client.execute(get, apiKey = { "key-1234567" }) }
        assertEquals(503, failure.status)
        assertEquals(listOf(1_000L, 2_000L, 4_000L), clock.sleeps)
        assertEquals(4, engine.calls.size)
    }

    @Test
    fun `a dropped connection is retried for a read but never for a write, and the url stays out of the error`() = runTest {
        engine.fail()
        engine.reply(200)
        client.execute(get, apiKey = { "key-1234567" })
        assertEquals(2, engine.calls.size)

        engine.fail()
        val failure = failsWith<MdbListException> { client.execute(post, apiKey = { "key-1234567" }) }
        assertEquals(3, engine.calls.size)
        assertFalse(failure.message.orEmpty().contains("apikey"))
        assertNull(failure.cause)
    }

    @Test
    fun `the five-minute limit waits Retry-After when short and fails when long`() = runTest {
        engine.reply(429, RATE_LIMIT_BODY, mapOf("Retry-After" to "12"))
        engine.reply(200)
        client.execute(get, apiKey = { "key-1234567" })
        assertEquals(listOf(12_000L), clock.sleeps)

        engine.reply(429, RATE_LIMIT_BODY, mapOf("Retry-After" to "240"))
        val failure = failsWith<MdbListApiException> { client.execute(get, apiKey = { "key-1234567" }) }
        assertFalse(failure.dailyLimit)
        assertEquals(240L, failure.retryAfterSeconds)
    }

    @Test
    fun `the daily limit fails at once and says so`() = runTest {
        engine.reply(429, DAILY_LIMIT_BODY, mapOf("Retry-After" to "5", "X-RateLimit-Limit" to "1000", "X-RateLimit-Reset" to "1763078400"))
        val failure = failsWith<MdbListApiException> { client.execute(get, apiKey = { "key-1234567" }) }
        assertTrue(failure.dailyLimit)
        assertEquals(1000, failure.rateLimitLimit)
        assertEquals(1_763_078_400L, failure.rateLimitResetEpochSeconds)
        assertEquals(1, engine.calls.size)
    }

    @Test
    fun `client errors fail at once and the hooks see the answer`() = runTest {
        engine.reply(401, """{"error":"Invalid API key"}""")
        var seen: MdbListApiException? = null
        failsWith<MdbListApiException> { client.execute(get, apiKey = { "key-1234567" }, onAnswer = { _, _, failure -> seen = failure }) }
        assertEquals(401, seen?.status)
        assertEquals(1, engine.calls.size)
    }

    @Test
    fun `a reply over the cap is refused`() {
        val source = okio.Buffer().write(ByteArray(64))
        assertThrows(MdbListReplyTooLargeException::class.java) { readMdbListBody(source, -1, maxBytes = 32) }
        assertThrows(MdbListReplyTooLargeException::class.java) { readMdbListBody(okio.Buffer(), 33, maxBytes = 32) }
        assertEquals("ok", readMdbListBody(okio.Buffer().writeUtf8("ok"), 2, maxBytes = 32))
    }

    @Test
    fun `a write is sent once on a server error`() = runTest {
        engine.reply(503)
        val failure = failsWith<MdbListApiException> { client.execute(post, apiKey = { "key-1234567" }) }
        assertEquals(503, failure.status)
        assertEquals(1, engine.calls.size)
        assertTrue(clock.sleeps.isEmpty())
    }

    @Test
    fun `the five-minute waits are capped at a minute per call, and a 429 without Retry-After fails at once`() = runTest {
        engine.reply(429, RATE_LIMIT_BODY, mapOf("Retry-After" to "40"))
        engine.reply(429, RATE_LIMIT_BODY, mapOf("Retry-After" to "40"))
        failsWith<MdbListApiException> { client.execute(get, apiKey = { "key-1234567" }) }
        assertEquals(listOf(40_000L), clock.sleeps)
        assertEquals(2, engine.calls.size)

        engine.reply(429, RATE_LIMIT_BODY)
        failsWith<MdbListApiException> { client.execute(get, apiKey = { "key-1234567" }) }
        assertEquals(3, engine.calls.size)
    }
}
