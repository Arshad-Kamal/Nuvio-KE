package com.nuvio.tv.core.player

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.net.SocketTimeoutException

/**
 * W2.7: dead debrid links surface immediately instead of burning the retry
 * budget — 400/401/403/404/410 fail fast (single-URL items have nothing to
 * fail over to, and a 4xx never heals), socket timeouts get a short fixed
 * ladder (750 ms / 1500 ms / 3000 ms — flaky hosts stay flowing without the
 * default's long waits), everything else keeps the default policy at an
 * elevated minimum retry count of 6 (covers unknown-length progressive
 * streams).
 *
 * Extracted from PlayerMediaSourceFactory (2026-08-31) and made internal so
 * the error-class contract is unit-pinned.
 */
/**
 * HTTP codes treated as dead links (fail fast — a 4xx of this class never
 * heals, and a single-URL debrid item has nothing to fail over to). Shared by
 * the load-error policy and the player's error-recovery classifier so the two
 * can never drift apart.
 */
internal val DEAD_LINK_HTTP_RESPONSE_CODES = setOf(400, 401, 403, 404, 410)

/**
 * T4 (ysosrs 94b0c5df2, fatal-429 Build 1): clock injectable so the streak
 * contract stays unit-pinnable; monotonic by default (a wall-clock NTP step
 * must not fake or truncate a streak).
 */
internal class PlayerLoadErrorHandlingPolicy(
    private val clock: () -> Long = android.os.SystemClock::elapsedRealtime,
) : DefaultLoadErrorHandlingPolicy(6) {

    // T4: monotonic ms of the last 429/503 seen and the start of the current
    // continuous rate-limit streak. Shared across every MediaPeriod using this
    // single policy instance (one per playback session). Guarded by [streakLock]
    // (T4 review: upstream's two bare AtomicLongs tear at streak start — a fresh
    // lastHit with a stale streakStart reads as an aged streak, i.e. instant
    // fatal on a brand-new storm — and the give-up reset can erase a concurrent
    // fresh hit's marker, resurrecting the count-fatal mid-storm). Error-paced
    // calls only; the lock is uncontended.
    private val streakLock = Any()
    private var rateLimitLastHitMs = 0L
    private var rateLimitStreakStartMs = 0L

    internal companion object {
        private const val TAG = "NuvioLoadErrPolicy"

        // No 429/503 for this long -> the streak is considered ended and resets.
        internal const val RATE_LIMIT_STREAK_QUIET_RESET_MS = 10_000L

        // A continuous throttle with zero successful progress longer than this is
        // treated as a dead stream: surface one clean fatal so mid-play source
        // failover can act. Single tunable: longer than the ~58 s count-based
        // crash it replaces, so a genuinely dead stream fails over later than
        // before — the win is surviving every recoverable storm shorter than this.
        // (Upstream's planned Build 2 replaces this fixed ceiling with a
        // buffer-aware trigger; not yet shipped upstream, not pre-built here.)
        internal const val RATE_LIMIT_STREAK_CEILING_MS = 120_000L
    }

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val httpException = loadErrorInfo.exception.findCause<HttpDataSource.InvalidResponseCodeException>()
        if (httpException != null && httpException.responseCode in DEAD_LINK_HTTP_RESPONSE_CODES) {
            return C.TIME_UNSET
        }
        // T4 (ysosrs 94b0c5df2): 429/503 is transient server rate-limiting, not a
        // dead stream. PRDS already backs off (Retry-After / AIMD depth) and only
        // surfaces here once its own budget is spent; without this arm that
        // surfaced storm crossed the loader retry count and became a fatal Source
        // error mid-storm. Keep retrying (count uncapped in
        // getMinimumLoadableRetryCount) while the throttle is continuous and inside
        // the ceiling, then give up cleanly. Delay is set explicitly (NOT via
        // super) because the base policy may treat some codes (e.g. 503) as
        // permanent -> TIME_UNSET -> instant fatal.
        if (httpException != null &&
            (httpException.responseCode == 429 || httpException.responseCode == 503)
        ) {
            val giveUp = synchronized(streakLock) {
                val now = clock()
                val last = rateLimitLastHitMs
                rateLimitLastHitMs = now
                if (last == 0L || now - last > RATE_LIMIT_STREAK_QUIET_RESET_MS) {
                    rateLimitStreakStartMs = now
                }
                val streakMs = now - rateLimitStreakStartMs
                if (streakMs > RATE_LIMIT_STREAK_CEILING_MS) {
                    rateLimitLastHitMs = 0L
                    true
                } else {
                    false
                }
            }
            if (giveUp) {
                Log.w(
                    TAG,
                    "Rate-limit streak over ${RATE_LIMIT_STREAK_CEILING_MS}ms; giving up (clean fatal for failover)"
                )
                return C.TIME_UNSET
            }
            return minOf(1000L * loadErrorInfo.errorCount, 5_000L)
        }
        // NuvioTV fork (T2, ysosrs cad15d29e): a malformed-container error (a
        // zero-fill hole the extractor resync could not clear) will not
        // un-malform on retry - the same bytes fail identically. Surface it
        // immediately (no backoff retries) so the player's mid-play failover
        // can switch sources, matching the permanent-HTTP arm above.
        // Recoverable holes never reach here (the extractor swallows them), so
        // only genuinely unrecoverable corruption is short-circuited.
        // Scoped to media-chunk loads: this policy is shared with the HLS/DASH
        // factories, where a ParserException from a transiently-garbled manifest
        // or playlist refresh SHOULD keep the default retry ladder.
        val malformed = loadErrorInfo.mediaLoadData.dataType == C.DATA_TYPE_MEDIA &&
            (loadErrorInfo.exception.findCause<ParserException>() != null ||
                loadErrorInfo.exception.findCause<IllegalStateException>()?.message?.contains("varint") == true)
        if (malformed) {
            return C.TIME_UNSET
        }
        // P12: a probe 206 for another window repeats on every retry; fail fast so recovery
        // (same-link auto-retry, then source failover) acts without ~15 s of backoff.
        if (loadErrorInfo.exception.findCause<InvalidRangeResponseException>() != null) {
            return C.TIME_UNSET
        }
        val timeout = loadErrorInfo.exception.findCause<SocketTimeoutException>() != null
        return if (timeout) {
            when (loadErrorInfo.errorCount) {
                1 -> 750L
                2 -> 1500L
                else -> 3000L
            }
        } else super.getRetryDelayMsFor(loadErrorInfo)
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int {
        // T4 (ysosrs 94b0c5df2): during an active throttle streak inside the
        // budget, never go fatal on the retry count — getRetryDelayMsFor owns the
        // give-up (ceiling -> C.TIME_UNSET).
        val streakActive = synchronized(streakLock) {
            val last = rateLimitLastHitMs
            if (last != 0L) {
                val now = clock()
                now - last <= RATE_LIMIT_STREAK_QUIET_RESET_MS &&
                    now - rateLimitStreakStartMs <= RATE_LIMIT_STREAK_CEILING_MS
            } else {
                false
            }
        }
        if (streakActive) {
            return Int.MAX_VALUE
        }
        return super.getMinimumLoadableRetryCount(dataType)
    }
}

internal inline fun <reified T : Throwable> Throwable.findCause(): T? {
    var current: Throwable? = this
    while (current != null) {
        if (current is T) return current
        current = current.cause
    }
    return null
}
