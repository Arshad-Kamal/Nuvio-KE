package com.nuvio.tv.core.di

import java.io.IOException
import java.util.zip.GZIPOutputStream
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * W2.2 untrusted-response guard: the @Api client's responses from untrusted
 * addon hosts must be JSON-family on success and capped at
 * [UNTRUSTED_JSON_BODY_CAP_BYTES] counting DECOMPRESSED bytes — including
 * error bodies, which Retrofit 2.x would otherwise heap-buffer uncapped
 * before app code runs (Utils.buffer).
 */
class UntrustedResponseGuardTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .addInterceptor(untrustedResponseGuardInterceptor())
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun execute() =
        client.newCall(Request.Builder().url(server.url("/catalog")).build()).execute()

    /** JSON whitespace: passes the depth counter (which rejects NUL bytes), so only the byte cap fires. */
    private fun jsonWhitespace(size: Long) = ByteArray(size.toInt()) { ' '.code.toByte() }

    @Test
    fun `oversized json body is rejected mid-read`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(Buffer().write(jsonWhitespace(UNTRUSTED_JSON_BODY_CAP_BYTES + 100_000)))
        )
        val failure = runCatching { execute().use { it.body.string() } }.exceptionOrNull()
        assertTrue("expected IOException, got $failure", failure is IOException)
        assertTrue(failure!!.message!!.contains("cap"))
    }

    @Test
    fun `error bodies are capped too, not just successes`() {
        // The Retrofit error-body vector: a hostile host answering 500 + huge
        // would be buffered into the heap before any catch ran.
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "text/html")
                .setBody(Buffer().write(ByteArray((UNTRUSTED_JSON_BODY_CAP_BYTES + 100_000).toInt())))
        )
        val failure = runCatching { execute().use { it.body.string() } }.exceptionOrNull()
        assertTrue("expected IOException, got $failure", failure is IOException)
        assertTrue(failure!!.message!!.contains("cap"))
    }

    @Test
    fun `non-json success responses are rejected`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/html")
                .setBody("<html><body>error page</body></html>")
        )
        val failure = runCatching { execute() }.exceptionOrNull()
        assertTrue("expected IOException, got $failure", failure is IOException)
        assertTrue(failure!!.message!!.contains("non-JSON"))
    }

    @Test
    fun `gzip responses are counted post-decompression`() {
        // ~2.5MB of spaces compresses to a few KB — the cap must fire on the
        // DECOMPRESSED size (application layer sees the unwrapped body).
        val gzipped = Buffer()
        GZIPOutputStream(gzipped.outputStream()).use {
            it.write(jsonWhitespace(UNTRUSTED_JSON_BODY_CAP_BYTES + 500_000))
        }
        assertTrue("fixture must compress under the cap to prove the point", gzipped.size < UNTRUSTED_JSON_BODY_CAP_BYTES)
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setHeader("Content-Encoding", "gzip")
                .setBody(gzipped)
        )
        val failure = runCatching { execute().use { it.body.string() } }.exceptionOrNull()
        assertTrue("expected IOException, got $failure", failure is IOException)
        assertTrue(failure!!.message!!.contains("cap"))
    }

    @Test
    fun `json under the cap and vendor json types pass through`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"metas\":[]}")
        )
        execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("{\"metas\":[]}", response.body.string())
        }
        // +json vendor suffix (Stremio-style hosts) is allowed.
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/ld+json; charset=utf-8")
                .setBody("{}")
        )
        execute().use { response ->
            assertEquals("{}", response.body.string())
        }
    }

    @Test
    fun `json nested past the depth cap is rejected before decoding`() {
        // Deep arrays overflow kotlinx's JsonElement reader (a StackOverflowError no fetch
        // path catches); the guard turns them into an IOException mid-read.
        val deep = "[".repeat(10_000) + "]".repeat(10_000)
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"streams\":[{\"streamData\":$deep}]}")
        )
        val failure = runCatching { execute().use { it.body.string() } }.exceptionOrNull()
        assertTrue("expected IOException, got $failure", failure is IOException)
        assertTrue(failure!!.message!!.contains("nests deeper"))
    }

    @Test
    fun `depth counts structure only, not brackets inside strings`() {
        val atCap = "[".repeat(UNTRUSTED_JSON_MAX_DEPTH) + "]".repeat(UNTRUSTED_JSON_MAX_DEPTH)
        val bracketsInString = "{\"name\":\"" + "[{".repeat(5_000) + "\\\"[\"}"
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(atCap))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(bracketsInString))
        execute().use { assertEquals(atCap, it.body.string()) }
        execute().use { assertEquals(bracketsInString, it.body.string()) }
    }

    @Test
    fun `an escaped backslash ends the string before structural brackets`() {
        val deep = "[".repeat(UNTRUSTED_JSON_MAX_DEPTH + 1) + "]".repeat(UNTRUSTED_JSON_MAX_DEPTH + 1)
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"a\":\"\\\\\",\"b\":$deep}")
        )
        val failure = runCatching { execute().use { it.body.string() } }.exceptionOrNull()
        assertTrue("expected IOException, got $failure", failure is IOException)
        assertTrue(failure!!.message!!.contains("nests deeper"))
    }

    @Test
    fun `utf-16 bodies cannot desync the depth counter`() {
        // U+2200 inside a string is bytes 00 22 in UTF-16LE: a byte-wise counter would read a
        // quote and stop counting while ResponseBody.string() decodes the BOM correctly.
        val deep = "[".repeat(10_000) + "]".repeat(10_000)
        val utf16 = Buffer().write(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
            .writeString("{\"a\":\"\u2200\",\"b\":$deep}", Charsets.UTF_16LE)
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(utf16))
        val bom = runCatching { execute().use { it.body.string() } }.exceptionOrNull()
        assertTrue("expected IOException, got $bom", bom is IOException)

        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json; charset=Shift_JIS")
                .setBody("{}")
        )
        val declared = runCatching { execute() }.exceptionOrNull()
        assertTrue("expected IOException, got $declared", declared is IOException)
        assertTrue(declared!!.message!!.contains("charset"))

        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json; charset=ISO-8859-1")
                .setBody("{\"metas\":[]}")
        )
        execute().use { assertEquals("{\"metas\":[]}", it.body.string()) }
    }

    @Test
    fun `non-json error bodies are not depth-checked`() {
        // An HTML error page keeps its HTTP status for the caller; only the byte cap applies.
        val page = "<script>" + "[".repeat(1_000) + "</script>"
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", "text/html")
                .setBody(page)
        )
        execute().use { response ->
            assertEquals(404, response.code)
            assertEquals(page, response.body.string())
        }
    }
}
