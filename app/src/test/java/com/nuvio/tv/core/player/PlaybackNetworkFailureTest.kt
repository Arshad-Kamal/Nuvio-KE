package com.nuvio.tv.core.player

import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException

/** Upstream-sync Register A2 P3: a connection lost mid-play vs a broken file. */
class PlaybackNetworkFailureTest {
    private val spec: DataSpec = DataSpec.Builder().setUri(mockk<Uri>(relaxed = true)).build()

    /** The shape a failed download reaches the read site in: future.get wraps downloadChunk's IOException. */
    private fun chunkError(cause: Throwable): Throwable =
        ExecutionException(IOException("Failed to download chunk 4 after 2 attempts", cause))

    private fun chainOf(t: Throwable) = generateSequence(t) { it.cause }.toList()

    @Test
    fun `connection-level exceptions anywhere in the chain count as network failures`() {
        listOf(
            SocketException("Connection reset"),
            ConnectException("Connection refused"),
            UnknownHostException("cdn.example"),
            SocketTimeoutException("timeout"),
            TimeoutException(),
            HttpDataSource.HttpDataSourceException(
                IOException("unexpected end of stream"),
                spec,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                HttpDataSource.HttpDataSourceException.TYPE_READ
            )
        ).forEach { cause ->
            assertTrue(cause.toString(), PlaybackNetworkFailure.hasNetworkCause(chunkError(cause)))
        }
    }

    @Test
    fun `broken bodies and http statuses are not network failures`() {
        assertFalse(PlaybackNetworkFailure.hasNetworkCause(IOException("Short chunk")))
        assertFalse(PlaybackNetworkFailure.hasNetworkCause(chunkError(EOFException())))
        assertFalse(PlaybackNetworkFailure.hasNetworkCause(chunkError(IllegalStateException("bad box"))))
        assertFalse(PlaybackNetworkFailure.hasNetworkCause(null))
        val tooMany = HttpDataSource.InvalidResponseCodeException(429, "Too Many", null, emptyMap(), spec, byteArrayOf())
        assertFalse(PlaybackNetworkFailure.hasNetworkCause(chunkError(tooMany)))
    }

    @Test
    fun `a chunk lost to the network becomes an http read failure that keeps the original chain`() {
        val reset = SocketException("Connection reset")
        val failure = PlaybackNetworkFailure.chunkFailure("Failed to download chunk 4", chunkError(reset), spec)
            as HttpDataSource.HttpDataSourceException
        assertEquals(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, failure.reason)
        assertEquals(HttpDataSource.HttpDataSourceException.TYPE_READ, failure.type)
        assertSame(spec, failure.dataSpec)
        assertTrue(chainOf(failure).any { it === reset })
    }

    @Test
    fun `an okhttp-shaped socket timeout maps to 2002 and stays findable for the loader policy`() {
        val okHttpFailure = HttpDataSource.HttpDataSourceException(
            SocketTimeoutException("read timed out"),
            spec,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            HttpDataSource.HttpDataSourceException.TYPE_READ
        )
        val failure = PlaybackNetworkFailure.chunkFailure("Failed to download chunk 4", chunkError(okHttpFailure), spec)
            as HttpDataSource.HttpDataSourceException
        assertEquals(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, failure.reason)
        assertNotNull(failure.findCause<SocketTimeoutException>())
    }

    @Test
    fun `a bare future timeout reports 2002 with an io cause wrapping it`() {
        val timeout = TimeoutException()
        val failure = PlaybackNetworkFailure.chunkFailure("Failed to download chunk 4", timeout, spec)
            as HttpDataSource.HttpDataSourceException
        assertEquals(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, failure.reason)
        assertTrue(failure.cause is IOException)
        assertSame(timeout, failure.cause?.cause)
    }

    @Test
    fun `an http status chunk failure stays a plain io failure so T4 and dead-link handling are unchanged`() {
        val unavailable = HttpDataSource.InvalidResponseCodeException(503, "Unavailable", null, emptyMap(), spec, byteArrayOf())
        val failure = PlaybackNetworkFailure.chunkFailure("Failed to download chunk 4", chunkError(unavailable), spec)
        assertFalse(failure is HttpDataSource.HttpDataSourceException)
        assertTrue(chainOf(failure).any { it === unavailable })
    }

    @Test
    fun `without a network cause or a data spec the failure stays a plain io failure`() {
        val broken = PlaybackNetworkFailure.chunkFailure("Failed to download chunk 4", chunkError(EOFException("short")), spec)
        assertFalse(broken is HttpDataSource.HttpDataSourceException)
        assertEquals("Failed to download chunk 4", broken.message)
        val noSpec = PlaybackNetworkFailure.chunkFailure("x", chunkError(SocketException("reset")), null)
        assertFalse(noSpec is HttpDataSource.HttpDataSourceException)
    }
}
