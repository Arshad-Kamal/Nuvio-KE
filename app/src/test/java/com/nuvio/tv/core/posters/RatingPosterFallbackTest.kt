package com.nuvio.tv.core.posters

import android.content.Context
import android.graphics.ImageDecoder
import coil3.intercept.Interceptor
import coil3.network.HttpException
import coil3.network.NetworkResponse
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.size.Size
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class RatingPosterFallbackTest {

    private var now = 1_000_000L
    private val memory = RatingPosterFailureMemory { now }

    private fun http(code: Int) = HttpException(NetworkResponse(code = code))

    @Test
    fun `failures are classified by answer`() {
        assertEquals(RatingPosterFailure.UNAUTHORIZED, ratingPosterFailureOf(http(401)))
        assertEquals(RatingPosterFailure.FORBIDDEN, ratingPosterFailureOf(http(403)))
        for (code in listOf(400, 404, 410)) assertEquals(RatingPosterFailure.TERMINAL, ratingPosterFailureOf(http(code)))
        // 5xx and the host gate's synthetic 429/503.
        for (code in listOf(429, 500, 502, 503)) assertEquals(RatingPosterFailure.TRANSIENT, ratingPosterFailureOf(http(code)))
        assertEquals(RatingPosterFailure.TRANSIENT, ratingPosterFailureOf(SocketTimeoutException()))
        assertEquals(RatingPosterFailure.TRANSIENT, ratingPosterFailureOf(IOException()))
        // Bytes that are not an image: BitmapFactory below API 29, ImageDecoder (an IOException) above.
        assertEquals(RatingPosterFailure.TERMINAL, ratingPosterFailureOf(IllegalStateException("BitmapFactory returned a null bitmap")))
        assertEquals(RatingPosterFailure.TERMINAL, ratingPosterFailureOf(mockk<ImageDecoder.DecodeException>()))
        // Anything unexpected is retried soon, not held for hours.
        assertEquals(RatingPosterFailure.TRANSIENT, ratingPosterFailureOf(OutOfMemoryError()))
        assertEquals(RatingPosterFailure.TRANSIENT, ratingPosterFailureOf(SecurityException()))
    }

    @Test
    fun `terminal urls stay out for 6 hours, transient ones for 2 minutes`() {
        memory.record("https://p/a", "fp", RatingPosterFailure.TERMINAL, "fp")
        memory.record("https://p/b", "fp", RatingPosterFailure.TRANSIENT, "fp")
        assertTrue("https://p/a" in memory)
        assertTrue("https://p/b" in memory)
        now += RatingPosterFailureMemory.TRANSIENT_TTL_MS + 1
        assertTrue("https://p/a" in memory)
        assertFalse("https://p/b" in memory)
        now += RatingPosterFailureMemory.TERMINAL_TTL_MS
        assertFalse("https://p/a" in memory)
    }

    @Test
    fun `a 401 rejects the active template's key, only while it is active, for 6 hours`() {
        memory.record("https://p/a", "fp", RatingPosterFailure.UNAUTHORIZED, activeFingerprint = "fp")
        assertTrue(memory.isKeyRejected("fp"))
        assertFalse(memory.isKeyRejected("other"))
        now += RatingPosterFailureMemory.TERMINAL_TTL_MS
        assertFalse(memory.isKeyRejected("fp"))
    }

    @Test
    fun `a late 401 for a replaced template says nothing about the key`() {
        memory.record("https://p/a", "old", RatingPosterFailure.UNAUTHORIZED, activeFingerprint = "new")
        assertFalse(memory.isKeyRejected("old"))
        assertFalse(memory.isKeyRejected("new"))
        assertTrue("https://p/a" in memory)
    }

    @Test
    fun `one 403 blacklists its url, 403s from two urls within 5 minutes reject the key`() {
        memory.record("https://p/a", "fp", RatingPosterFailure.FORBIDDEN, "fp")
        assertTrue("https://p/a" in memory)
        assertFalse(memory.isKeyRejected("fp"))
        // The same URL again is still one URL.
        memory.record("https://p/a", "fp", RatingPosterFailure.FORBIDDEN, "fp")
        assertFalse(memory.isKeyRejected("fp"))
        memory.record("https://p/b", "fp", RatingPosterFailure.FORBIDDEN, "fp")
        assertTrue(memory.isKeyRejected("fp"))
    }

    @Test
    fun `403s further apart than 5 minutes, or from different templates, don't add up`() {
        memory.record("https://p/a", "fp", RatingPosterFailure.FORBIDDEN, "fp")
        now += RatingPosterFailureMemory.FORBIDDEN_WINDOW_MS + 1
        memory.record("https://p/b", "fp", RatingPosterFailure.FORBIDDEN, "fp")
        assertFalse(memory.isKeyRejected("fp"))
        memory.record("https://p/c", "fp2", RatingPosterFailure.FORBIDDEN, "fp2")
        assertFalse(memory.isKeyRejected("fp2"))
        // A 403 of an inactive template never rejects.
        memory.record("https://p/d", "fp", RatingPosterFailure.FORBIDDEN, "fp2")
        assertFalse(memory.isKeyRejected("fp"))
    }

    // --- interceptor ---

    private val context = mockk<Context>(relaxed = true)
    private val fallback = RatingPosterFallback("https://addon/p.jpg", "https://addon/p.jpg_100x150", "fp")

    private fun ratingRequest(withFallback: Boolean = true): ImageRequest =
        ImageRequest.Builder(context)
            .data("https://pp.example.com/poster?imdb_id=tt1&access_key=SECRET")
            .memoryCacheKey("https://pp.example.com/poster?imdb_id=tt1&access_key=SECRET_100x150")
            .size(100, 150)
            .apply { if (withFallback) extras[RatingPosterFallback.KEY] = fallback }
            .build()

    /** Answers each request with [answer]; records what it was asked. */
    private class FakeChain(
        override val request: ImageRequest,
        private val answer: (ImageRequest) -> ImageResult,
        val asked: MutableList<ImageRequest> = mutableListOf(),
    ) : Interceptor.Chain {
        override val size: Size = Size(100, 150)
        override fun withRequest(request: ImageRequest): Interceptor.Chain = FakeChain(request, answer, asked)
        override fun withSize(size: Size): Interceptor.Chain = this
        override suspend fun proceed(): ImageResult = answer(request).also { asked += request }
    }

    private val success = mockk<SuccessResult>()
    private fun error(request: ImageRequest, throwable: Throwable) = ErrorResult(null, request, throwable)

    private fun interceptor(active: String? = "fp") = RatingPosterFallbackInterceptor(memory) { active }

    @Test
    fun `a request without the extra passes through`() = runTest {
        val chain = FakeChain(ratingRequest(withFallback = false), { success })
        assertSame(success, interceptor().intercept(chain))
        assertEquals(1, chain.asked.size)
    }

    @Test
    fun `a loaded rating poster is returned as is`() = runTest {
        val chain = FakeChain(ratingRequest(), { success })
        assertSame(success, interceptor().intercept(chain))
        assertEquals(listOf(ratingRequest().data), chain.asked.map { it.data })
    }

    @Test
    fun `a failed rating poster falls back once to the addon's poster under its key, extra stripped`() = runTest {
        val request = ratingRequest()
        val chain = FakeChain(request, { if (it.data == request.data) error(it, http(404)) else success })
        assertSame(success, interceptor().intercept(chain))
        assertEquals(2, chain.asked.size)
        val retry = chain.asked.last()
        assertEquals("https://addon/p.jpg", retry.data)
        assertEquals("https://addon/p.jpg_100x150", retry.memoryCacheKey)
        assertNull(retry.extras[RatingPosterFallback.KEY])
        // Coil's real chain refuses a request with another context, target or size resolver.
        assertSame(request.context, retry.context)
        assertSame(request.target, retry.target)
        assertSame(request.sizeResolver, retry.sizeResolver)
        assertTrue(request.data as String in memory)
    }

    @Test
    fun `when both fail the addon's error comes back after one retry`() = runTest {
        val request = ratingRequest()
        val chain = FakeChain(request, { error(it, http(404)) })
        val result = interceptor().intercept(chain)
        assertEquals(2, chain.asked.size)
        assertEquals("https://addon/p.jpg", (result as ErrorResult).request.data)
    }

    @Test
    fun `a 401 marks the key rejected`() = runTest {
        val request = ratingRequest()
        val chain = FakeChain(request, { if (it.data == request.data) error(it, http(401)) else success })
        interceptor(active = "fp").intercept(chain)
        assertTrue(memory.isKeyRejected("fp"))
    }

    @Test
    fun `a 401 for a template no longer active only blacklists its url`() = runTest {
        val request = ratingRequest()
        val chain = FakeChain(request, { if (it.data == request.data) error(it, http(401)) else success })
        interceptor(active = "new").intercept(chain)
        assertFalse(memory.isKeyRejected("fp"))
        assertTrue(request.data as String in memory)
    }

    @Test
    fun `a known-bad url goes straight to the addon poster`() = runTest {
        val request = ratingRequest()
        memory.record(request.data as String, "fp", RatingPosterFailure.TRANSIENT, "fp")
        val chain = FakeChain(request, { success })
        interceptor().intercept(chain)
        assertEquals(listOf<Any>("https://addon/p.jpg"), chain.asked.map { it.data })
    }

    @Test
    fun `a rejected key goes straight to the addon poster`() = runTest {
        memory.record("https://other", "fp", RatingPosterFailure.UNAUTHORIZED, "fp")
        val chain = FakeChain(ratingRequest(), { success })
        interceptor().intercept(chain)
        assertEquals(listOf<Any>("https://addon/p.jpg"), chain.asked.map { it.data })
    }

    @Test
    fun `cancellation is never a fallback`() = runTest {
        val request = ratingRequest()
        val asked = mutableListOf<Any?>()
        val chain = FakeChain(request, {
            asked += it.data
            if (it.data == request.data) throw CancellationException("gone") else success
        })
        val thrown = runCatching { interceptor().intercept(chain) }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
        assertEquals(listOf<Any?>(request.data), asked)
        assertFalse(request.data as String in memory)
    }

    @Test
    fun `the fallback extra never prints the address`() {
        assertEquals("RatingPosterFallback", fallback.toString())
    }
}
