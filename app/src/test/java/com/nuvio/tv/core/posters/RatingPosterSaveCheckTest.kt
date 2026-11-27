package com.nuvio.tv.core.posters

import com.nuvio.tv.core.network.AddonHostGuard
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 114 P0-7: the save check's resolve, plain GET past the host gate, verdicts from the code only, and the save. */
class RatingPosterSaveCheckTest {

    private val server = MockWebServer().apply { start() }
    private val guard = AddonHostGuard(nowMs = { 0L })
    private val saved = mutableListOf<Pair<Int, RatingPosterTemplate>>()
    private var decodes = true
    private val failures = RatingPosterFailureMemory()
    private val dispatcher = okhttp3.Dispatcher()

    @After
    fun stop() = server.shutdown()

    /**
     * The `@Image` client's shape: the host gate first (here one that fails the test if it is ever asked), then
     * a hop that sends the template's home-network address to the mock server, path and query kept.
     */
    private fun check(timeoutMs: Long = 2_000): RatingPosterSaveCheck {
        val client = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .addInterceptor(RatingPosterHostGate(Interceptor { throw AssertionError("the gate was asked") }) { TEMPLATE })
            .addInterceptor { chain ->
                val url = chain.request().url
                val target = server.url(url.encodedPath).newBuilder().encodedQuery(url.encodedQuery).build()
                chain.proceed(chain.request().newBuilder().url(target).build())
            }
            .build()
        return RatingPosterSaveCheck(
            client = client,
            guard = guard,
            save = { profileId, template -> saved += profileId to template },
            decodesAsImage = { decodes },
            timeoutMs = timeoutMs,
            failures = failures,
        )
    }

    private fun image(code: Int = 200, type: String = "image/jpeg") =
        MockResponse().setResponseCode(code).setHeader("Content-Type", type).setBody(Buffer().write(ByteArray(64)))

    private fun run(
        input: String = ADDRESS,
        profileId: Int = 3,
        timeoutMs: Long = 2_000,
        stillWanted: () -> Boolean = { true },
    ) = runBlocking { check(timeoutMs).checkAndSave(input, profileId, stillWanted) }

    @Test
    fun `a poster that loads saves for the captured profile, asked for Big Buck Bunny`() {
        server.enqueue(image())
        val result = run(profileId = 7)
        assertEquals(RatingPosterCheck.Saved("192.168.1.20:8000", isHttp = true), result)
        assertEquals(7, saved.single().first)
        assertEquals(ADDRESS, saved.single().second.raw)
        val sent = server.takeRequest().requestUrl!!
        assertEquals("tt1254207", sent.queryParameter("imdb_id"))
        assertEquals("10378", sent.queryParameter("tmdb_id"))
        assertEquals("movie", sent.queryParameter("type"))
    }

    @Test
    fun `success closes the host's breaker and resets the failure memory`() {
        val key = "192.168.1.20:8000"
        repeat(AddonHostGuard.FAILURES_TO_OPEN) { guard.onNetworkFailure(key, probe = false) }
        assertEquals(AddonHostGuard.Admission.SKIP_BREAKER_OPEN, guard.admit(key))
        val fingerprint = TEMPLATE.fingerprint
        failures.record("u1", fingerprint, RatingPosterFailure.FORBIDDEN, fingerprint)
        failures.record("u2", fingerprint, RatingPosterFailure.FORBIDDEN, fingerprint)
        failures.record("u3", fingerprint, RatingPosterFailure.TRANSIENT, fingerprint)
        assertTrue(failures.isKeyRejected(fingerprint))
        assertTrue("u1" in failures && "u3" in failures)

        server.enqueue(image())
        assertTrue(run() is RatingPosterCheck.Saved)

        assertEquals(AddonHostGuard.Admission.ALLOW, guard.admit(key))
        assertFalse(failures.isKeyRejected(fingerprint))
        assertFalse("u1" in failures || "u2" in failures || "u3" in failures)
        // The 403 window starts over: one new 403 is not two URLs.
        failures.record("u4", fingerprint, RatingPosterFailure.FORBIDDEN, fingerprint)
        assertFalse(failures.isKeyRejected(fingerprint))
    }

    @Test
    fun `a failed connection is no poster, and nothing is saved`() {
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START))
        assertEquals(RatingPosterCheck.NoPoster("192.168.1.20:8000"), run())
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `verdicts come from the HTTP code, and a failure saves, trusts and resets nothing`() {
        val key = "192.168.1.20:8000"
        repeat(AddonHostGuard.FAILURES_TO_OPEN) { guard.onNetworkFailure(key, probe = false) }
        failures.record("u1", "f", RatingPosterFailure.TERMINAL, "f")
        val expected = mapOf(
            401 to RatingPosterCheck.KeyRejected("192.168.1.20:8000", 401),
            403 to RatingPosterCheck.KeyRejected("192.168.1.20:8000", 403),
            404 to RatingPosterCheck.NotFound("192.168.1.20:8000"),
            400 to RatingPosterCheck.NoPoster("192.168.1.20:8000"),
            500 to RatingPosterCheck.NoPoster("192.168.1.20:8000"),
        )
        for ((code, verdict) in expected) {
            server.enqueue(image(code = code))
            assertEquals("$code", verdict, run())
        }
        assertTrue(saved.isEmpty())
        assertEquals(AddonHostGuard.Admission.SKIP_BREAKER_OPEN, guard.admit(key))
        assertTrue("u1" in failures)
    }

    @Test
    fun `a 2xx that is not an image, or does not decode, is no poster`() {
        server.enqueue(image(type = "text/html"))
        assertEquals(RatingPosterCheck.NoPoster("192.168.1.20:8000"), run())
        server.enqueue(MockResponse().setBody("x"))
        assertEquals(RatingPosterCheck.NoPoster("192.168.1.20:8000"), run())
        decodes = false
        server.enqueue(image())
        assertEquals(RatingPosterCheck.NoPoster("192.168.1.20:8000"), run())
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `the time limit is no poster and cancels the call`() {
        server.enqueue(image().setHeadersDelay(3, TimeUnit.SECONDS))
        assertEquals(RatingPosterCheck.NoPoster("192.168.1.20:8000"), run(timeoutMs = 300))
        assertTrue(saved.isEmpty())
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (dispatcher.runningCallsCount() > 0 && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(0, dispatcher.runningCallsCount())
    }

    @Test
    fun `the panel is asked after the poster came back, and closed saves nothing`() {
        server.enqueue(image())
        var askedAfter = -1
        val result = run(stillWanted = { askedAfter = server.requestCount; false })
        assertEquals(RatingPosterCheck.Closed, result)
        assertEquals(1, askedAfter)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `refusals and an untestable template make no request`() {
        assertEquals(
            RatingPosterCheck.Refused(RatingPosterTemplate.Refusal.HTTP_NOT_HOME_NETWORK),
            run("http://posters.example.net/poster?imdb_id={imdb_id}"),
        )
        assertEquals(
            RatingPosterCheck.Refused(RatingPosterTemplate.Refusal.NO_ID_PLACEHOLDER),
            run("https://posters.example.net/poster?key=abc"),
        )
        assertEquals(RatingPosterCheck.Untestable, run("https://posters.example.net/poster/{tvdb_id}.jpg?key=abc"))
        assertEquals(0, server.requestCount)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `no verdict carries the address or its key`() {
        server.enqueue(image(code = 401))
        val verdicts = listOf(run(), RatingPosterCheck.Saved(TEMPLATE.displayHost, true))
        verdicts.forEach { assertFalse(it.toString(), "SECRETKEY" in it.toString()) }
    }

    private companion object {
        const val ADDRESS =
            "http://192.168.1.20:8000/poster?imdb_id={imdb_id?}&tmdb_id={tmdb_id?}&type={type}&access_key=SECRETKEY1234"
        val TEMPLATE = (RatingPosterTemplate.parse(ADDRESS) as RatingPosterTemplate.Parsed.Valid).template
    }
}
