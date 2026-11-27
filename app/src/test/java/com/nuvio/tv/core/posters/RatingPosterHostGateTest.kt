package com.nuvio.tv.core.posters

import com.nuvio.tv.core.network.AddonHostGuard
import com.nuvio.tv.core.network.AddonHostGuardInterceptor
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class RatingPosterHostGateTest {

    private val template = (RatingPosterTemplate.parse(
        "https://pp.example.com:8443/poster?imdb_id={imdb_id}&access_key=SECRET"
    ) as RatingPosterTemplate.Parsed.Valid).template

    private val guarded = mockk<Interceptor>()

    private fun chainFor(url: String): Interceptor.Chain {
        val sent = Request.Builder().url(url).build()
        val response = Response.Builder().request(sent).protocol(Protocol.HTTP_1_1).code(200).message("OK").build()
        return mockk<Interceptor.Chain> {
            every { request() } returns sent
            every { proceed(any()) } returns response
        }.also { chain -> every { guarded.intercept(chain) } returns response }
    }

    @Test
    fun `the template's host and port go through the guard`() {
        val chain = chainFor("https://pp.example.com:8443/poster?imdb_id=tt1")
        RatingPosterHostGate(guarded) { template }.intercept(chain)
        verify(exactly = 1) { guarded.intercept(chain) }
        verify(exactly = 0) { chain.proceed(any()) }
    }

    @Test
    fun `other hosts, another port on the same host, or the setting off just proceed`() {
        for ((url, active) in listOf(
            "https://image.tmdb.org/t/p/w342/a.jpg" to template,
            "https://pp.example.com/poster" to template,
            "https://pp.example.com:8443/poster" to null,
        )) {
            val chain = chainFor(url)
            RatingPosterHostGate(guarded) { active }.intercept(chain)
            verify(exactly = 0) { guarded.intercept(chain) }
            verify(exactly = 1) { chain.proceed(any()) }
        }
    }

    @Test
    fun `with the real guard an open breaker answers 503 without a network call`() {
        val gate = RatingPosterHostGate(AddonHostGuardInterceptor(AddonHostGuard(nowMs = { 0L }))) { template }
        val url = "https://pp.example.com:8443/poster?imdb_id=tt1"
        repeat(AddonHostGuard.FAILURES_TO_OPEN) {
            val failing = mockk<Interceptor.Chain> {
                every { request() } returns Request.Builder().url(url).build()
                every { proceed(any()) } throws IOException("down")
                every { call().isCanceled() } returns false
            }
            runCatching { gate.intercept(failing) }
        }
        val sent = Request.Builder().url(url).build()
        val skipped = Response.Builder().request(sent).protocol(Protocol.HTTP_1_1).code(504).message("x").build()
        val chain = mockk<Interceptor.Chain> {
            every { request() } returns sent
            // The guard's only-if-cached re-issue: nothing is cached on the @Image client.
            every { proceed(any()) } returns skipped
        }
        assertEquals(503, gate.intercept(chain).code)
        verify(exactly = 1) { chain.proceed(match { it.cacheControl.onlyIfCached }) }
    }
}
