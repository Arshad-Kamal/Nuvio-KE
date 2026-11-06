package com.nuvio.tv.core.player

import androidx.media3.common.C
import com.nuvio.tv.core.player.PrewarmWindowReader.Rejection
import okhttp3.Headers.Companion.headersOf
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

/** Register A2 P12: 206 responses must describe exactly the requested window. */
class PlaybackRangeResponseTest {

    private val unset = C.LENGTH_UNSET.toLong()

    // -- Content-Range parsing --

    @Test
    fun `parses a single well-formed Content-Range`() {
        assertEquals(ContentRange(0, 262_143, 9_402_232_472), parseSingleContentRange(listOf("bytes 0-262143/9402232472")))
        assertEquals(ContentRange(96, 99, null), parseSingleContentRange(listOf(" BYTES 96-99/* ")))
    }

    @Test
    fun `rejects absent repeated malformed or inconsistent Content-Range`() {
        assertNull(parseSingleContentRange(emptyList()))
        assertNull(parseSingleContentRange(listOf("bytes 0-9/100", "bytes 0-9/100")))
        assertNull(parseSingleContentRange(listOf("bytes 0-262143")))
        assertNull(parseSingleContentRange(listOf("bytes */100")))
        assertNull(parseSingleContentRange(listOf("bytes 10-9/100")))
        assertNull(parseSingleContentRange(listOf("bytes 0-100/100")))
    }

    // -- PRDS probe --

    private fun probe(position: Long, length: Long, code: Int = 206, vararg headers: Pair<String, String>) =
        validatedProbeTotal(position, length, code, headers.groupBy({ it.first }, { it.second }))

    @Test
    fun `valid probe 206 returns the total`() {
        assertEquals(1_000_000L, probe(0, 262_144, 206, "Content-Range" to "bytes 0-262143/1000000", "Content-Length" to "262144"))
        // Near the end the window is clipped to what remains.
        assertEquals(1_000_000L, probe(999_000, 262_144, 206, "content-range" to "bytes 999000-999999/1000000"))
        // Unbounded reopen: the 206 must run to the end.
        assertEquals(1_000_000L, probe(500_000, unset, 206, "Content-Range" to "bytes 500000-999999/1000000"))
    }

    @Test
    fun `non-206 or opaque total falls back to a single connection`() {
        assertEquals(unset, probe(0, 262_144, 200))
        assertEquals(unset, probe(0, 262_144, 206, "Content-Range" to "bytes 0-262143/*"))
    }

    @Test
    fun `longer span with the right start passes`() {
        assertEquals(1_000_000L, probe(0, 262_144, 206, "Content-Range" to "bytes 0-999999/1000000"))
    }

    @Test
    fun `probe 206 for another window throws`() {
        listOf(
            arrayOf("Content-Range" to "bytes 4096-266239/1000000"), // wrong start
            arrayOf("Content-Range" to "bytes 0-1023/1000000"), // short
            arrayOf<Pair<String, String>>(), // no Content-Range
            arrayOf("Content-Range" to "bytes 0-262143/1000000", "Content-Encoding" to "gzip"),
            arrayOf("Content-Range" to "bytes 0-262143/1000000", "Content-Length" to "1000"),
        ).forEach { headers ->
            assertThrows(IOException::class.java) { probe(0, 262_144, 206, *headers) }
        }
    }

    // -- prewarm reader --

    private fun response(code: Int, body: ByteArray, vararg headers: String): Response =
        Response.Builder()
            .request(Request.Builder().url("https://cdn.example/f.mkv").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("x")
            .headers(headersOf(*headers))
            .body(body.toResponseBody())
            .build()

    private fun bytes(n: Int) = ByteArray(n) { it.toByte() }

    @Test
    fun `head window is read exactly`() {
        val body = bytes(1024)
        val window = PrewarmWindowReader.read(
            response(206, body, "Content-Range", "bytes 0-1023/5000", "Content-Length", "1024"),
            maxBytes = 1024, start = 0
        )
        assertEquals(0L, window!!.start)
        assertEquals(5000L, window.total)
        assertArrayEquals(body, window.bytes)
    }

    @Test
    fun `suffix window starts at total minus window`() {
        val window = PrewarmWindowReader.read(
            response(206, bytes(1024), "Content-Range", "bytes 3976-4999/5000"),
            maxBytes = 1024, start = null
        )
        assertEquals(3976L, window!!.start)
    }

    @Test
    fun `small file head window is the whole file`() {
        val window = PrewarmWindowReader.read(
            response(206, bytes(100), "Content-Range", "bytes 0-99/100"),
            maxBytes = 1024, start = 0
        )
        assertEquals(100, window!!.bytes.size)
    }

    private fun rejection(resp: Response, start: Long? = 0, expectedTotal: Long? = null): Rejection? {
        var reason: Rejection? = null
        val window = PrewarmWindowReader.read(resp, maxBytes = 1024, start = start, expectedTotal = expectedTotal) { reason = it }
        assertNull(window)
        return reason
    }

    @Test
    fun `prewarm rejections`() {
        assertEquals(Rejection.HTTP_STATUS, rejection(response(200, bytes(1024))))
        assertEquals(Rejection.CONTENT_ENCODING, rejection(response(206, bytes(1024), "Content-Range", "bytes 0-1023/5000", "Content-Encoding", "gzip")))
        assertEquals(Rejection.CONTENT_RANGE, rejection(response(206, bytes(1024))))
        assertEquals(Rejection.CONTENT_RANGE, rejection(response(206, bytes(1024), "Content-Range", "bytes 0-1023/*")))
        assertEquals(Rejection.RANGE_START, rejection(response(206, bytes(1024), "Content-Range", "bytes 512-1535/5000")))
        assertEquals(Rejection.RANGE_LENGTH, rejection(response(206, bytes(512), "Content-Range", "bytes 0-511/5000")))
        assertEquals(Rejection.CONTENT_LENGTH, rejection(response(206, bytes(1024), "Content-Range", "bytes 0-1023/5000", "Content-Length", "1023")))
        assertEquals(Rejection.SHORT_BODY, rejection(response(206, bytes(1000), "Content-Range", "bytes 0-1023/5000")))
        assertEquals(Rejection.OVERLONG_BODY, rejection(response(206, bytes(4096), "Content-Range", "bytes 0-1023/5000")))
        assertEquals(Rejection.TOTAL_CHANGED, rejection(response(206, bytes(1024), "Content-Range", "bytes 3976-4999/5000"), start = 3976, expectedTotal = 6000))
    }
}
