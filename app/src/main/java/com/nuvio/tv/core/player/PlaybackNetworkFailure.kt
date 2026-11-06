package com.nuvio.tv.core.player

import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

/**
 * Tells a lost connection apart from a broken file (ysosrs `0954ef772`, adapted; upstream-sync
 * Register A2 P3). A connection lost mid-play is worth a same-link retry; a broken file is not.
 *
 * Deviation from upstream: an HTTP status failure (404, 429, 503, ...) is NOT a network cause
 * here. Its handling stays with the dead-link arm and the T4 rate-limit give-up, whose clean
 * fatal must keep failing over to the next source.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal object PlaybackNetworkFailure {
    private const val MAX_CAUSE_DEPTH = 12

    /** Connection-level reasons an [HttpDataSource.HttpDataSourceException] can carry. */
    private val NETWORK_ERROR_CODES = setOf(
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
    )

    fun findNetworkCause(error: Throwable?): Throwable? =
        generateSequence(error) { it.cause }
            .take(MAX_CAUSE_DEPTH)
            .firstOrNull { cause ->
                when (cause) {
                    is HttpDataSource.InvalidResponseCodeException -> false
                    is HttpDataSource.HttpDataSourceException -> cause.reason in NETWORK_ERROR_CODES
                    is SocketTimeoutException,
                    is SocketException,
                    is UnknownHostException,
                    is TimeoutException -> true
                    else -> false
                }
            }

    fun hasNetworkCause(error: Throwable?): Boolean = findNetworkCause(error) != null

    private fun errorCodeFor(networkCause: Throwable): Int = when (networkCause) {
        is HttpDataSource.HttpDataSourceException ->
            if (networkCause.reason == PlaybackException.ERROR_CODE_IO_UNSPECIFIED) {
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
            } else {
                networkCause.reason
            }
        is SocketTimeoutException,
        is TimeoutException -> PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        else -> PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
    }

    /**
     * A chunk lost to the network surfaces as an HTTP read failure with a network code, so the
     * player retries the same link instead of reading the unspecified 2000 as a broken file.
     * Anything else (a short body, an HTTP status) stays a plain [IOException], as before.
     */
    fun chunkFailure(message: String, error: Throwable, dataSpec: DataSpec?): IOException {
        val networkCause = findNetworkCause(error)
        if (networkCause == null || dataSpec == null) return IOException(message, error)
        // Keeps the original chain (SocketTimeoutException etc.) under the wrapper: the loader
        // policy and the container-corruption check (hasNetworkCause) match on it.
        val ioCause = error.findCause<IOException>() ?: IOException(error)
        return HttpDataSource.HttpDataSourceException(
            message,
            ioCause,
            dataSpec,
            errorCodeFor(networkCause),
            HttpDataSource.HttpDataSourceException.TYPE_READ
        )
    }
}
