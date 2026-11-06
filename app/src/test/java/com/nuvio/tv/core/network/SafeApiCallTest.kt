package com.nuvio.tv.core.network

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * W2.3: the fetch boundary must classify decode garbage separately from
 * transport failures — a SerializationException is a RuntimeException that
 * would otherwise ride the generic Exception path carrying a message that
 * embeds a snippet of the hostile response.
 */
class SafeApiCallTest {

    private val context = mockk<Context>().also {
        every { it.getString(any<Int>()) } returns "str-from-res"
    }

    @Test
    fun `decode garbage is classified with a sanitized message`() = runTest {
        val result = safeApiCall<String>(context) {
            throw SerializationException("Unexpected JSON token at \$.metas[0].id: GARBAGE_SNIPPET")
        }
        assertTrue((result as NetworkResult.Error).garbage)
        assertEquals("str-from-res", result.message)
    }

    @Test
    fun `sanitized message never carries the embedded snippet`() {
        val e = SerializationException("path \$.id value HOSTILE_PAYLOAD")
        assertEquals("Malformed response from source", e.sanitizedFetchMessage())
        assertFalse(e.sanitizedFetchMessage().contains("HOSTILE_PAYLOAD"))
    }

    @Test
    fun `transport failures keep their message and are not garbage`() = runTest {
        val result = safeApiCall<String>(context) { throw IOException("connection reset") }
        val error = result as NetworkResult.Error
        assertFalse(error.garbage)
        assertEquals("connection reset", error.message)
    }

    @Test
    fun `cancellation propagates`() = runTest {
        var thrown = false
        try {
            safeApiCall<String>(context) { throw CancellationException("scope died") }
        } catch (e: CancellationException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun `success and http-error shapes are unchanged`() = runTest {
        val ok = safeApiCall(context) { Response.success("body") }
        assertEquals("body", (ok as NetworkResult.Success).data)

        val http = safeApiCall(context) {
            Response.error<String>(404, "nope".toResponseBody())
        }
        val error = http as NetworkResult.Error
        assertEquals(404, error.code)
        assertFalse(error.garbage)
    }
}
