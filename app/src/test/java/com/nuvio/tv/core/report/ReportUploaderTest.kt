package com.nuvio.tv.core.report

import com.nuvio.tv.core.di.appUserAgent
import com.nuvio.tv.core.network.dns.AppDns
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ReportUploaderTest {

    private lateinit var server: MockWebServer
    private val bytes = ("Nuvio KE report v1\n" + "é line\n".repeat(3000)).toByteArray()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun uploader(online: Boolean = true) = ReportUploader(server.url("/").toString(), isOnline = { online })

    private fun replyWith(response: MockResponse): UploadResult {
        server.enqueue(response)
        return runBlocking { uploader().upload(bytes) }
    }

    private fun created(body: String) = MockResponse().setResponseCode(201).setBody(body)

    @Test
    fun `201 with a valid code is sent, and the request carries exactly the bytes`() {
        val progress = mutableListOf<Long>()
        server.enqueue(created("""{"code":"KE-7F3QM2"}"""))
        val result = runBlocking { uploader().upload(bytes) { sent, total -> assertEquals(bytes.size.toLong(), total); progress += sent } }
        assertEquals(UploadResult.Sent("KE-7F3QM2"), result)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/reports", request.path)
        assertEquals("application/x-nuvio-report", request.getHeader("Content-Type"))
        assertEquals(bytes.size.toString(), request.getHeader("Content-Length"))
        assertEquals(appUserAgent(), request.getHeader("User-Agent"))
        assertArrayEquals(bytes, request.body.readByteArray())
        assertEquals(bytes.size.toLong(), progress.last())
        assertEquals(progress.sorted(), progress)
    }

    @Test
    fun `a trailing slash on the base URL does not double the path`() {
        server.enqueue(created("""{"code":"KE-7F3QM2"}"""))
        runBlocking { ReportUploader(server.url("/").toString() + "/", isOnline = { true }).upload(bytes) }
        assertEquals("/v1/reports", server.takeRequest().path)
    }

    @Test
    fun `a malformed or off-alphabet code is not accepted`() {
        for (body in listOf(
            """{"code":"KE-7F3QI2"}""",  // I is not Crockford
            """{"code":"ke-7f3qm2"}""",
            """{"code":"KE-7F3QM2X"}""",
            """{"code":"KE-7F3QM2\n"}""",
            """{"code":123}""",
            """{"other":"KE-7F3QM2"}""",
            """["KE-7F3QM2"]""",
            "KE-7F3QM2",
            """{"code":"KE-7F3QM2","pad":"${"x".repeat(2000)}"}""",
        )) {
            assertEquals(body.take(40), UploadResult.ServerUnavailable, replyWith(created(body)))
        }
    }

    @Test
    fun `200 with a valid code is not a created report`() {
        assertEquals(UploadResult.ServerUnavailable, replyWith(MockResponse().setResponseCode(200).setBody("""{"code":"KE-7F3QM2"}""")))
    }

    @Test
    fun `status paths map to the E1 results`() {
        assertEquals(UploadResult.RateLimited, replyWith(MockResponse().setResponseCode(429)))
        for (code in listOf(400, 404, 413, 415)) {
            assertEquals("$code", UploadResult.Refused, replyWith(MockResponse().setResponseCode(code)))
        }
        for (code in listOf(500, 502, 503)) {
            assertEquals("$code", UploadResult.ServerUnavailable, replyWith(MockResponse().setResponseCode(code)))
        }
    }

    @Test
    fun `a redirect is not followed`() {
        assertEquals(
            UploadResult.ServerUnavailable,
            replyWith(MockResponse().setResponseCode(307).setHeader("Location", server.url("/elsewhere")))
        )
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a dropped connection is server unavailable`() {
        assertEquals(UploadResult.ServerUnavailable, replyWith(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)))
    }

    @Test
    fun `a failed call is no internet only when the TV is offline`() {
        val url = server.url("/").toString()
        server.shutdown()
        assertEquals(UploadResult.NoInternet, runBlocking { ReportUploader(url, isOnline = { false }).upload(bytes) })
        assertEquals(UploadResult.ServerUnavailable, runBlocking { ReportUploader(url, isOnline = { true }).upload(bytes) })
    }

    @Test
    fun `a real call timeout is server unavailable`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val client = ReportUploader.defaultClient().newBuilder().callTimeout(300, TimeUnit.MILLISECONDS).build()
        val result = runBlocking { ReportUploader(server.url("/").toString(), isOnline = { true }, client).upload(bytes) }
        assertEquals(UploadResult.ServerUnavailable, result)
    }

    @Test
    fun `cancelling the caller cancels the call promptly and stops progress`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val calls = AtomicInteger()
        val started = System.nanoTime()
        val result = runBlocking {
            withTimeoutOrNull(300) { uploader().upload(bytes) { _, _ -> calls.incrementAndGet() } }
        }
        assertNull(result)
        assertTrue("cancel took too long", System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5))
        val afterCancel = calls.get()
        Thread.sleep(200)
        assertEquals(afterCancel, calls.get())
    }

    @Test
    fun `the client is its own, on AppDns, 30 s, no redirects, no interceptors`() {
        val client = ReportUploader.defaultClient()
        assertSame(AppDns, client.dns)
        assertEquals(TimeUnit.SECONDS.toMillis(30).toInt(), client.callTimeoutMillis)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertEquals(0, client.interceptors.size + client.networkInterceptors.size)
    }
}
