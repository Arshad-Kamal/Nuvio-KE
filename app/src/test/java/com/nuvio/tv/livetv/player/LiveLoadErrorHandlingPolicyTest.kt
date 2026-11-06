package com.nuvio.tv.livetv.player

import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/** Spec 34 D7: the live fail-fast set, Media3's retries for everything else. */
class LiveLoadErrorHandlingPolicyTest {

    private val adaptive = LiveLoadErrorHandlingPolicy(progressive = false)
    private val progressive = LiveLoadErrorHandlingPolicy(progressive = true)

    // JVM unit test: android.net.Uri is stubbed; a relaxed mock satisfies DataSpec.
    private val testUri: android.net.Uri = mockk(relaxed = true)

    private fun errorInfo(exception: IOException, dataType: Int, errorCount: Int = 1) =
        LoadErrorHandlingPolicy.LoadErrorInfo(
            LoadEventInfo(0L, DataSpec(testUri), 0L),
            MediaLoadData(dataType),
            exception,
            errorCount
        )

    private fun http(code: Int) =
        HttpDataSource.InvalidResponseCodeException(code, null, null, emptyMap(), DataSpec(testUri), ByteArray(0))

    @Test
    fun `dead-link codes on a playlist load fail at once`() {
        for (code in intArrayOf(400, 401, 403, 404, 410, 451)) {
            assertEquals("code $code", C.TIME_UNSET, adaptive.getRetryDelayMsFor(errorInfo(http(code), C.DATA_TYPE_MANIFEST)))
        }
    }

    @Test
    fun `segment 4xx keeps the default retry, 451 does not`() {
        for (code in intArrayOf(403, 404, 410)) {
            assertEquals("code $code", 0L, adaptive.getRetryDelayMsFor(errorInfo(http(code), C.DATA_TYPE_MEDIA)))
        }
        assertEquals(C.TIME_UNSET, adaptive.getRetryDelayMsFor(errorInfo(http(451), C.DATA_TYPE_MEDIA)))
    }

    @Test
    fun `a progressive stream fails fast on dead-link codes for any load`() {
        for (code in intArrayOf(403, 404, 451)) {
            assertEquals(
                "code $code",
                C.TIME_UNSET,
                // ProgressiveMediaPeriod reports its loads as DATA_TYPE_MEDIA.
                progressive.getRetryDelayMsFor(errorInfo(http(code), C.DATA_TYPE_MEDIA))
            )
        }
    }

    @Test
    fun `wrapped codes are found in the cause chain`() {
        val wrapped = IOException("load failed", http(404))
        assertEquals(C.TIME_UNSET, adaptive.getRetryDelayMsFor(errorInfo(wrapped, C.DATA_TYPE_MANIFEST)))
    }

    @Test
    fun `server errors and timeouts keep the Media3 delay ladder`() {
        assertEquals(0L, adaptive.getRetryDelayMsFor(errorInfo(http(500), C.DATA_TYPE_MANIFEST, errorCount = 1)))
        assertEquals(2_000L, adaptive.getRetryDelayMsFor(errorInfo(http(502), C.DATA_TYPE_MANIFEST, errorCount = 3)))
        val timeout = IOException(SocketTimeoutException("read"))
        assertEquals(1_000L, progressive.getRetryDelayMsFor(errorInfo(timeout, C.DATA_TYPE_MEDIA, errorCount = 2)))
        assertNotEquals(C.TIME_UNSET, adaptive.getRetryDelayMsFor(errorInfo(http(429), C.DATA_TYPE_MANIFEST)))
    }

    @Test
    fun `retry counts stay at the Media3 defaults`() {
        assertEquals(3, adaptive.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
        assertEquals(3, adaptive.getMinimumLoadableRetryCount(C.DATA_TYPE_MANIFEST))
        assertEquals(6, progressive.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA_PROGRESSIVE_LIVE))
    }
}
