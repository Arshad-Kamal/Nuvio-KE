package com.nuvio.tv.data.simkl

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 106 S1a / T6: the allowance as Simkl reports it, and the pause until its reset. */
class SimklQuotaTest {

    private val clock = FakeClock()
    private val quota = SimklQuota { clock.now }

    private fun utc(year: Int, month: Int, day: Int, hour: Int): Long = Calendar.getInstance(TimeZone.getTimeZone("UTC")).run {
        clear()
        set(year, month - 1, day, hour, 0, 0)
        timeInMillis
    }

    @Test
    fun `the reset is the next New York midnight in winter and summer`() {
        assertEquals(utc(2026, 1, 16, 5), nextSimklResetEpochMs(utc(2026, 1, 15, 12)))
        assertEquals(utc(2026, 7, 16, 4), nextSimklResetEpochMs(utc(2026, 7, 15, 12)))
        // 02:00 UTC on the 16th is still the 15th in New York.
        assertEquals(utc(2026, 1, 16, 5), nextSimklResetEpochMs(utc(2026, 1, 16, 2)))
        // DST 2026 starts 8 Mar and ends 1 Nov: the reset is New York's midnight on either side of each switch.
        assertEquals(utc(2026, 3, 8, 5), nextSimklResetEpochMs(utc(2026, 3, 7, 12)))
        assertEquals(utc(2026, 3, 9, 4), nextSimklResetEpochMs(utc(2026, 3, 8, 12)))
        assertEquals(utc(2026, 11, 1, 4), nextSimklResetEpochMs(utc(2026, 10, 31, 12)))
        assertEquals(utc(2026, 11, 2, 5), nextSimklResetEpochMs(utc(2026, 11, 1, 12)))
    }

    @Test
    fun `the headers set what is left and an uncounted reply changes nothing`() {
        quota.record(1, SimklResponse(200, "", mapOf("x-ratelimit-limit" to "500", "x-ratelimit-remaining" to "368")))
        quota.record(1, SimklResponse(200, "", emptyMap()))
        assertEquals(SimklQuotaState(limit = 500, remaining = 368), quota.state(1))
        assertEquals(SimklQuotaState(), quota.state(2))
    }

    @Test
    fun `a spent day pauses that profile until Retry-After, then clears`() {
        quota.recordFailure(1, SimklApiException(429, SIMKL_ERROR_USER_LIMIT, retryAfterSeconds = 600, rateLimitLimit = 500))
        val paused = quota.state(1)
        assertEquals(0, paused.remaining)
        assertEquals(clock.now + 600_000L, paused.pausedUntilEpochMs)
        assertTrue(paused.isPaused(clock.now))

        val refused = assertFailsSuspend<SimklQuotaExceededException> { quota.checkAvailable(1) }
        assertEquals(paused.pausedUntilEpochMs, refused.pausedUntilEpochMs)
        quota.checkAvailable(2)

        clock.now += 600_000L
        quota.checkAvailable(1)
        assertEquals(SimklQuotaState(limit = 500), quota.state(1))
    }

    @Test
    fun `without Retry-After the pause runs to the New York reset`() {
        quota.recordFailure(1, SimklApiException(429, SIMKL_ERROR_USER_LIMIT))
        assertEquals(nextSimklResetEpochMs(clock.now), quota.state(1).pausedUntilEpochMs)
    }

    @Test
    fun `other refusals never pause`() {
        quota.recordFailure(1, SimklApiException(429, SIMKL_ERROR_RATE_LIMIT, retryAfterSeconds = 600))
        quota.recordFailure(1, SimklApiException(500, null))
        assertFalse(quota.state(1).isPaused(clock.now))
    }
}
