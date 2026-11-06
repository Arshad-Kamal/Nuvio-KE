package com.nuvio.tv.core.server

import fi.iki.elonen.NanoHTTPD.IHTTPSession
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Pins the trust-boundary contract of [readCappedUtf8Body]: missing header reads
 * as empty, a client-declared length above the cap is rejected with null (before
 * any allocation), and a short/truncated stream returns what it has without
 * hanging.
 */
class ConfigServerGuardBodyTest {

    private fun session(contentLength: String?, body: ByteArray = ByteArray(0)): IHTTPSession =
        mockk {
            every { headers } returns buildMap {
                if (contentLength != null) put("content-length", contentLength)
            }
            every { inputStream } returns ByteArrayInputStream(body)
        }

    @Test
    fun `missing content-length reads as empty`() {
        assertEquals("", session(null).readCappedUtf8Body())
    }

    @Test
    fun `zero or negative content-length reads as empty`() {
        assertEquals("", session("0").readCappedUtf8Body())
        assertEquals("", session("-5").readCappedUtf8Body())
    }

    @Test
    fun `body under the cap is read fully`() {
        val session = session("5", "hello".toByteArray())
        assertEquals("hello", session.readCappedUtf8Body(maxBytes = 1024))
    }

    @Test
    fun `declared length over the cap is rejected before allocating`() {
        assertNull(session((MAX_POST_BODY_BYTES + 1).toString(), "x".toByteArray()).readCappedUtf8Body())
    }

    @Test
    fun `truncated stream returns the partial body without hanging`() {
        val session = session("10", "hell".toByteArray())
        assertEquals("hell", session.readCappedUtf8Body(maxBytes = 1024))
    }

    @Test
    fun `declared length is trimmed, and a length past Int is over the cap, not empty`() {
        assertEquals(5L, session(" 5 ").declaredBodyLength())
        assertNull(session("-1").declaredBodyLength())
        assertNull(session("abc").declaredBodyLength())
        assertNull(session("3000000000").readCappedUtf8Body())
    }
}
