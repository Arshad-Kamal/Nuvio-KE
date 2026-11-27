package com.nuvio.tv.core.player

import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.nuvio.tv.core.player.PlayerStartupWatchdogPolicy.StartupLoadHold.ALL_ERRORS
import com.nuvio.tv.core.player.PlayerStartupWatchdogPolicy.StartupLoadHold.NONE
import com.nuvio.tv.core.player.PlayerStartupWatchdogPolicy.StartupLoadHold.TIMEOUTS
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * W2.7 error-class contract: 4xx fail-fast (dead debrid links surface
 * immediately), socket timeouts take the short fixed ladder, unknown errors
 * keep the default policy at the elevated minimum retry count (6).
 */
class PlayerLoadErrorHandlingPolicyTest {

    private val policy = PlayerLoadErrorHandlingPolicy()

    // JVM unit test: android.net.Uri constants are stubbed null — a relaxed
    // mock satisfies DataSpec's non-null check without Android.
    private val testUri: android.net.Uri = mockk(relaxed = true)

    private fun errorInfo(
        exception: java.io.IOException,
        errorCount: Int = 1,
        dataType: Int = C.DATA_TYPE_MEDIA
    ): LoadErrorHandlingPolicy.LoadErrorInfo = LoadErrorHandlingPolicy.LoadErrorInfo(
        LoadEventInfo(0L, DataSpec(testUri), 0L),
        MediaLoadData(dataType),
        exception,
        errorCount
    )

    private fun httpError(code: Int): HttpDataSource.InvalidResponseCodeException =
        HttpDataSource.InvalidResponseCodeException(
            code, null, null, emptyMap(), DataSpec(testUri), ByteArray(0)
        )

    @Test
    fun `dead-link 4xx codes fail fast with no retry`() {
        for (code in intArrayOf(400, 401, 403, 404, 410)) {
            assertEquals("code $code", C.TIME_UNSET, policy.getRetryDelayMsFor(errorInfo(httpError(code))))
        }
    }

    @Test
    fun `wrapped 403 in a cause chain still fails fast`() {
        val wrapped = java.io.IOException("load failed", httpError(403))
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(errorInfo(wrapped)))
    }

    @Test
    fun `retryable server codes keep the default ladder`() {
        // Not just "retryable" — pins the default super path exactly (0 ms at
        // errorCount=1), so a misroute into the timeout ladder also fails.
        assertEquals(0L, policy.getRetryDelayMsFor(errorInfo(httpError(500))))
    }

    @Test
    fun `socket timeouts take the short fixed ladder`() {
        assertEquals(750L, policy.getRetryDelayMsFor(errorInfo(SocketTimeoutException("t"), errorCount = 1)))
        assertEquals(1500L, policy.getRetryDelayMsFor(errorInfo(SocketTimeoutException("t"), errorCount = 2)))
        assertEquals(3000L, policy.getRetryDelayMsFor(errorInfo(SocketTimeoutException("t"), errorCount = 3)))
        assertEquals(3000L, policy.getRetryDelayMsFor(errorInfo(SocketTimeoutException("t"), errorCount = 9)))
    }

    @Test
    fun `unknown errors keep the default ladder`() {
        // At 1.8.0 the default delay method never returns TIME_UNSET for generic
        // IOExceptions — it always offers min((n-1)*1000, 5000). The retry BUDGET
        // is governed by getMinimumLoadableRetryCount (pinned below).
        assertEquals(0L, policy.getRetryDelayMsFor(errorInfo(IOException("boom"), errorCount = 1)))
        assertEquals(2000L, policy.getRetryDelayMsFor(errorInfo(IOException("boom"), errorCount = 3)))
        assertEquals(5000L, policy.getRetryDelayMsFor(errorInfo(IOException("boom"), errorCount = 6)))
        assertEquals(5000L, policy.getRetryDelayMsFor(errorInfo(IOException("boom"), errorCount = 99)))
    }

    @Test
    fun `minimum loadable retry count is elevated to six`() {
        // The (6) constructor raises the retry budget for every data type — this
        // is what keeps unknown-length progressive streams retrying past default.
        assertEquals(6, policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
        assertEquals(6, policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA_PROGRESSIVE_LIVE))
    }

    // T2 malformed-container arm: deterministic corrupt media bytes fail fast
    // into the player's mid-play failover; manifest/playlist loads (shared
    // policy with the HLS/DASH factories) keep the default retry ladder.

    private fun malformedContainer(): ParserException =
        androidx.media3.common.ParserException.createForMalformedContainer(
            "No valid varint length mask found", /* cause= */ null
        )

    @Test
    fun `malformed container error on a media load fails fast`() {
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(errorInfo(malformedContainer())))
    }

    @Test
    fun `probe 206 for another window fails fast`() {
        // P12: deterministic, the same server answers the same way on every retry.
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(errorInfo(InvalidRangeResponseException("start"))))
    }

    @Test
    fun `wrapped malformed container error still fails fast`() {
        val wrapped = IOException("load failed", malformedContainer())
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(errorInfo(wrapped)))
    }

    @Test
    fun `malformed container error on a manifest load is not fork fail-fast`() {
        // Media3's own default policy already fails fast for manifest
        // ParserExceptions (deterministic bytes; never retried), so the fork arm
        // must not change that - this pins parity, and that the gate keeps the
        // fork arm scoped to media loads rather than widening some other type.
        assertEquals(
            C.TIME_UNSET,
            policy.getRetryDelayMsFor(
                errorInfo(malformedContainer(), dataType = C.DATA_TYPE_MANIFEST)
            )
        )
    }

    @Test
    fun `manifest loads with transient errors keep the default ladder`() {
        // The class of error that genuinely self-heals (network) on a manifest
        // load still retries - pre-T2 behavior preserved by the dataType gate.
        assertEquals(
            0L,
            policy.getRetryDelayMsFor(
                errorInfo(IOException("socket reset"), dataType = C.DATA_TYPE_MANIFEST)
            )
        )
    }

    // T4 rate-limit streak arm (ysosrs 94b0c5df2): a surfaced 429/503 storm is
    // transient throttling, not a dead stream — retry through it (count uncapped)
    // while continuous and under the ceiling; past the ceiling, one clean fatal
    // so mid-play failover can act. Fake clock drives the streak deterministically.
    // Base is non-zero because 0L is the policy's idle sentinel — elapsedRealtime()
    // is never 0 on a booted device, and the sentinel contract rides on that.

    @Test
    fun `rate-limit codes retry with the capped ladder instead of failing fast`() {
        var now = 1_000_000L
        val p = PlayerLoadErrorHandlingPolicy { now }
        assertEquals(1000L, p.getRetryDelayMsFor(errorInfo(httpError(429), errorCount = 1)))
        assertEquals(3000L, p.getRetryDelayMsFor(errorInfo(httpError(503), errorCount = 3)))
        assertEquals(5000L, p.getRetryDelayMsFor(errorInfo(httpError(429), errorCount = 5)))
        assertEquals(5000L, p.getRetryDelayMsFor(errorInfo(httpError(429), errorCount = 99)))
    }

    @Test
    fun `active streak is never fatal on the retry count`() {
        val base = 1_000_000L
        var now = base
        val p = PlayerLoadErrorHandlingPolicy { now }
        // Continuous storm: hits every 5 s (inside the quiet window) so the streak
        // genuinely ages — a single far-future hit would restart it instead.
        var t = base
        while (t <= base + 60_000L) {
            now = t
            p.getRetryDelayMsFor(errorInfo(httpError(429), errorCount = 3))
            t += 5_000L
        }
        now = base + 65_000L // 65 s continuous, inside the ceiling
        assertEquals(Int.MAX_VALUE, p.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
    }

    @Test
    fun `streak outside the quiet window releases the retry-count override`() {
        val base = 1_000_000L
        var now = base
        val p = PlayerLoadErrorHandlingPolicy { now }
        p.getRetryDelayMsFor(errorInfo(httpError(429)))
        now = base + PlayerLoadErrorHandlingPolicy.RATE_LIMIT_STREAK_QUIET_RESET_MS + 1 // quiet gap: streak ended
        assertEquals(6, p.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
    }

    @Test
    fun `continuous streak past the ceiling gives up cleanly and resets`() {
        val base = 1_000_000L
        var now = base
        val p = PlayerLoadErrorHandlingPolicy { now }
        // A continuous storm: hits every 5 s (inside the quiet window) across the
        // ceiling — the streak never ends, so its age crosses the ceiling. A single
        // clock jump past the ceiling would NOT count: the quiet gap ends the
        // streak first (that is the quiet-reset contract, pinned in the test below).
        var t = base
        while (t <= base + PlayerLoadErrorHandlingPolicy.RATE_LIMIT_STREAK_CEILING_MS) {
            now = t
            p.getRetryDelayMsFor(errorInfo(httpError(429), errorCount = 10))
            t += 5_000L
        }
        now = base + PlayerLoadErrorHandlingPolicy.RATE_LIMIT_STREAK_CEILING_MS + 1
        assertEquals(
            "past-ceiling continuous streak must be one clean fatal",
            C.TIME_UNSET,
            p.getRetryDelayMsFor(errorInfo(httpError(429), errorCount = 2))
        )
        // Give-up resets the streak: the next 429 starts a fresh window with the
        // ladder, not another fatal.
        assertEquals(1000L, p.getRetryDelayMsFor(errorInfo(httpError(429), errorCount = 1)))
        assertEquals(Int.MAX_VALUE, p.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
    }

    @Test
    fun `quiet gap after a streak restarts the ceiling window`() {
        val base = 1_000_000L
        var now = base
        val p = PlayerLoadErrorHandlingPolicy { now }
        p.getRetryDelayMsFor(errorInfo(httpError(429))) // streak A starts at base
        now = base + PlayerLoadErrorHandlingPolicy.RATE_LIMIT_STREAK_QUIET_RESET_MS + 1 // gap ends streak A before its ceiling
        p.getRetryDelayMsFor(errorInfo(httpError(429), errorCount = 1)) // streak B starts here
        now = base + PlayerLoadErrorHandlingPolicy.RATE_LIMIT_STREAK_CEILING_MS + 1 // streak A's age would be past ceiling
        assertEquals(
            "the quiet gap restarted the streak — the current one is young and must retry",
            2000L,
            p.getRetryDelayMsFor(errorInfo(httpError(429), errorCount = 2))
        )
    }

    // --- Spec 138: torrent start load hold (D2 timeouts, F1 every error under the box) ---

    @Test
    fun `a held timeout uncaps the retry count until the hold ends`() {
        val p = PlayerLoadErrorHandlingPolicy()
        var hold = TIMEOUTS
        p.startupLoadHold = { hold }
        assertEquals(750L, p.getRetryDelayMsFor(errorInfo(SocketTimeoutException("t"), errorCount = 1)))
        assertEquals(Int.MAX_VALUE, p.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
        hold = NONE
        assertEquals(6, p.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
        // Cleared: a later hold needs a new held error.
        hold = TIMEOUTS
        assertEquals(6, p.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
    }

    @Test
    fun `only a timeout on a held uri is held while the watchdog judges`() {
        val p = PlayerLoadErrorHandlingPolicy()
        p.startupLoadHold = { NONE }
        p.getRetryDelayMsFor(errorInfo(SocketTimeoutException("t")))
        assertEquals(6, p.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))

        val q = PlayerLoadErrorHandlingPolicy()
        q.startupLoadHold = { TIMEOUTS }
        q.getRetryDelayMsFor(errorInfo(java.io.IOException("not a timeout")))
        assertEquals(6, q.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
    }

    @Test
    fun `a new start's clear drops the previous torrent's held error`() {
        val p = PlayerLoadErrorHandlingPolicy()
        p.startupLoadHold = { TIMEOUTS }
        p.getRetryDelayMsFor(errorInfo(SocketTimeoutException("t")))
        assertEquals(Int.MAX_VALUE, p.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
        p.clearHeldError()
        assertEquals(6, p.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
    }

    @Test
    fun `another error on the held uri ends the hold, another uri's leaves it`() {
        val p = PlayerLoadErrorHandlingPolicy()
        p.startupLoadHold = { if (it === testUri) TIMEOUTS else NONE }
        p.getRetryDelayMsFor(errorInfo(SocketTimeoutException("t")))
        assertEquals(Int.MAX_VALUE, p.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
        // A sidecar subtitle failing meanwhile does not end the torrent's hold.
        val otherUri: android.net.Uri = mockk(relaxed = true)
        p.getRetryDelayMsFor(
            LoadErrorHandlingPolicy.LoadErrorInfo(
                LoadEventInfo(0L, DataSpec(otherUri), 0L), MediaLoadData(C.DATA_TYPE_MEDIA),
                java.io.IOException("subtitle"), 1
            )
        )
        assertEquals(Int.MAX_VALUE, p.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
        // TorrServer refusing (a non-timeout error on the held URI) counts again.
        p.getRetryDelayMsFor(errorInfo(java.io.IOException("connection refused")))
        assertEquals(6, p.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
    }

    @Test
    fun `under the watchdog's box every error on the held uri is retried and uncapped`() {
        // F1 (Shield window 2026-11-20): held timeouts while judging, the box, then TorrServer's own
        // add-torrent timeout as HTTP 500 must not reach onPlayerError before the box's grace stop.
        val p = PlayerLoadErrorHandlingPolicy()
        var hold = TIMEOUTS
        p.startupLoadHold = { hold }
        p.getRetryDelayMsFor(errorInfo(SocketTimeoutException("t"), errorCount = 5))
        hold = ALL_ERRORS
        assertEquals(
            PlayerLoadErrorHandlingPolicy.HELD_ERROR_RETRY_DELAY_MS,
            p.getRetryDelayMsFor(errorInfo(httpError(500), errorCount = 6))
        )
        assertEquals(Int.MAX_VALUE, p.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
        // Ahead of the fail-fast arms: a dead-link code or a malformed body is held too.
        assertEquals(
            PlayerLoadErrorHandlingPolicy.HELD_ERROR_RETRY_DELAY_MS,
            p.getRetryDelayMsFor(errorInfo(httpError(404), errorCount = 7))
        )
        assertEquals(
            PlayerLoadErrorHandlingPolicy.HELD_ERROR_RETRY_DELAY_MS,
            p.getRetryDelayMsFor(errorInfo(malformedContainer(), errorCount = 8))
        )
        assertEquals(Int.MAX_VALUE, p.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
        // A 503 under the box starts no rate-limit streak.
        assertEquals(
            PlayerLoadErrorHandlingPolicy.HELD_ERROR_RETRY_DELAY_MS,
            p.getRetryDelayMsFor(errorInfo(httpError(503), errorCount = 9))
        )
        // The hold ends: the count applies again and a fail-fast error is fatal again.
        hold = NONE
        assertEquals(6, p.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
        assertEquals(C.TIME_UNSET, p.getRetryDelayMsFor(errorInfo(httpError(404), errorCount = 10)))
        assertEquals(6, p.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
    }

    @Test
    fun `a box hold that falls back to the timeout hold keeps a held error until an unheld one`() {
        val p = PlayerLoadErrorHandlingPolicy()
        var hold = ALL_ERRORS
        p.startupLoadHold = { hold }
        p.getRetryDelayMsFor(errorInfo(httpError(500), errorCount = 7))
        hold = TIMEOUTS
        assertEquals(Int.MAX_VALUE, p.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
        p.getRetryDelayMsFor(errorInfo(httpError(500), errorCount = 8))
        assertEquals(6, p.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
    }

    @Test
    fun `under the box another uri's error is not held`() {
        val p = PlayerLoadErrorHandlingPolicy()
        p.startupLoadHold = { if (it === testUri) ALL_ERRORS else NONE }
        val otherUri: android.net.Uri = mockk(relaxed = true)
        val subtitle404 = LoadErrorHandlingPolicy.LoadErrorInfo(
            LoadEventInfo(0L, DataSpec(otherUri), 0L), MediaLoadData(C.DATA_TYPE_MEDIA),
            HttpDataSource.InvalidResponseCodeException(404, null, null, emptyMap(), DataSpec(otherUri), ByteArray(0)),
            1
        )
        assertEquals(C.TIME_UNSET, p.getRetryDelayMsFor(subtitle404))
        assertEquals(6, p.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
    }

    @Test
    fun `under the box a first non-timeout error starts the hold`() {
        val p = PlayerLoadErrorHandlingPolicy()
        p.startupLoadHold = { ALL_ERRORS }
        p.getRetryDelayMsFor(errorInfo(java.io.IOException("connection refused")))
        assertEquals(Int.MAX_VALUE, p.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
    }
}
