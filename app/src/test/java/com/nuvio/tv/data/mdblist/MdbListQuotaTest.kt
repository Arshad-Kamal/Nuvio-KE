package com.nuvio.tv.data.mdblist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 105 S1a / D5: the allowance as MDBList reports it, and the pause until its reset. */
class MdbListQuotaTest {

    private val clock = FakeClock()
    private val quota = MdbListQuota { clock.now }

    /** The next 00:00 UTC after the clock's start (2025-11-13 02:13:20 UTC). */
    private val reset = 1_763_078_400_000L

    private fun response(headers: Map<String, String>) = MdbListResponse(200, "", headers)

    @Test
    fun `the reset is the next midnight UTC`() {
        assertEquals(reset, nextMdbListResetEpochMs(clock.now))
        assertEquals(reset + 86_400_000L, nextMdbListResetEpochMs(reset))
        assertEquals(reset, nextMdbListResetEpochMs(reset - 1))
    }

    @Test
    fun `the headers set what is left and an uncounted reply changes nothing`() {
        quota.record(1, response(mapOf("x-ratelimit-limit" to "1000", "x-ratelimit-remaining" to "912", "x-ratelimit-reset" to "1763078400")))
        quota.record(1, response(emptyMap()))
        assertEquals(MdbListQuotaState(limit = 1000, remaining = 912, resetsAtEpochMs = reset), quota.state(1))
        assertEquals(MdbListQuotaState(), quota.state(2))
    }

    @Test
    fun `an implausible reset header is ignored`() {
        quota.record(1, response(mapOf("x-ratelimit-reset" to "1")))
        quota.record(1, response(mapOf("x-ratelimit-reset" to "${(clock.now + 3L * 86_400_000L) / 1_000L}")))
        assertEquals(MdbListQuotaState(), quota.state(1))
    }

    @Test
    fun `the daily limit pauses the profile until the reset header, then lifts`() {
        quota.recordFailure(1, MdbListApiException(429, dailyLimit = true, rateLimitLimit = 1000, rateLimitResetEpochSeconds = 1_763_078_400L))
        assertTrue(quota.state(1).isPaused(clock.now))
        assertEquals(0, quota.state(1).remaining)
        val failure = assertThrows(MdbListQuotaExceededException::class.java) { quota.checkAvailable(1) }
        assertEquals(reset, failure.pausedUntilEpochMs)
        quota.checkAvailable(2)

        clock.now = reset
        quota.checkAvailable(1)
        assertEquals(MdbListQuotaState(limit = 1000, resetsAtEpochMs = reset), quota.state(1))
    }

    @Test
    fun `without a usable reset header the pause runs to the next midnight UTC`() {
        quota.recordFailure(1, MdbListApiException(429, dailyLimit = true))
        assertEquals(reset, quota.state(1).pausedUntilEpochMs)
    }

    @Test
    fun `other failures and the five-minute limit do not pause`() {
        quota.recordFailure(1, MdbListApiException(429, dailyLimit = false, retryAfterSeconds = 120))
        quota.recordFailure(1, MdbListApiException(500))
        assertFalse(quota.state(1).isPaused(clock.now))
        quota.checkAvailable(1)
    }

    @Test
    fun `clear forgets the profile`() {
        quota.recordFailure(1, MdbListApiException(429, dailyLimit = true))
        quota.clear(1)
        assertEquals(MdbListQuotaState(), quota.state(1))
    }
}
