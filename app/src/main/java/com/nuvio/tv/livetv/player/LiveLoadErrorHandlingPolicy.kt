package com.nuvio.tv.livetv.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.nuvio.tv.core.player.DEAD_LINK_HTTP_RESPONSE_CODES
import com.nuvio.tv.core.player.findCause

/**
 * Load-error policy for Live TV streams (Spec 34 D7, L6a). Media3's default retries (3 per load,
 * 6 for progressive live) and HLS track/location exclusion, plus a fail-fast set so a dead stream
 * hands over to the ladder (`LiveTuneLadder`) at once instead of burning the per-channel budget:
 *
 * - 451 is fatal for every load (a legal block never heals).
 * - The VOD dead-link set (400/401/403/404/410) is fatal for manifest and playlist loads, and for
 *   every load of a [progressive] source (a raw TS stream's one connection is its manifest).
 *   HLS/DASH segment 4xx keep the default retry: a segment can 404 while the CDN catches up with
 *   the playlist, and Media3 excludes the failing variant first when there is another.
 *
 * Unlike the VOD policy there is no 429/503 streak budget: a throttled channel moves to its next
 * stream when the retries run out, and the ladder's watchdog bounds the wait.
 */
@OptIn(UnstableApi::class)
internal class LiveLoadErrorHandlingPolicy(private val progressive: Boolean) : DefaultLoadErrorHandlingPolicy() {

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val code = loadErrorInfo.exception.findCause<HttpDataSource.InvalidResponseCodeException>()?.responseCode
        if (code != null && isFatal(code, loadErrorInfo.mediaLoadData.dataType)) return C.TIME_UNSET
        return super.getRetryDelayMsFor(loadErrorInfo)
    }

    internal fun isFatal(code: Int, dataType: Int): Boolean =
        code == HTTP_UNAVAILABLE_FOR_LEGAL_REASONS ||
            (code in DEAD_LINK_HTTP_RESPONSE_CODES && (progressive || dataType == C.DATA_TYPE_MANIFEST))

    internal companion object {
        const val HTTP_UNAVAILABLE_FOR_LEGAL_REASONS = 451
    }
}
