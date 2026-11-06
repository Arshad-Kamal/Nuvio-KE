package com.nuvio.tv.core.profile

import com.nuvio.tv.data.remote.supabase.SupabaseProfilePinVerifyResult
import org.junit.Assert.assertEquals
import org.junit.Test

class PinLockoutsTest {

    private fun verify(unlocked: Boolean = false, retry: Int = 0) =
        Result.success(SupabaseProfilePinVerifyResult(unlocked = unlocked, retryAfterSeconds = retry))

    @Test
    fun `maps verify results to outcomes`() {
        val lockouts = PinLockouts()
        assertEquals(PinVerifyOutcome.Unlocked, lockouts.record(2, verify(unlocked = true), nowMs = 0))
        assertEquals(PinVerifyOutcome.Wrong, lockouts.record(2, verify(), nowMs = 0))
        assertEquals(PinVerifyOutcome.LockedOut(30), lockouts.record(2, verify(retry = 30), nowMs = 0))
        assertEquals(PinVerifyOutcome.Failed, lockouts.record(2, Result.failure(RuntimeException()), nowMs = 0))
    }

    @Test
    fun `a lockout counts down per profile, rounding up, to zero`() {
        val lockouts = PinLockouts()
        lockouts.record(2, verify(retry = 30), nowMs = 10_000)
        assertEquals(30, lockouts.remainingSeconds(2, nowMs = 10_000))
        assertEquals(30, lockouts.remainingSeconds(2, nowMs = 10_001))
        assertEquals(1, lockouts.remainingSeconds(2, nowMs = 39_999))
        assertEquals(0, lockouts.remainingSeconds(2, nowMs = 40_000))
        assertEquals(0, lockouts.remainingSeconds(3, nowMs = 10_000))
    }

    @Test
    fun `an unlock clears the profile's lockout, other outcomes keep it`() {
        val lockouts = PinLockouts()
        lockouts.record(2, verify(retry = 60), nowMs = 0)
        lockouts.record(2, Result.failure(RuntimeException()), nowMs = 1_000)
        lockouts.record(2, verify(), nowMs = 1_000)
        assertEquals(59, lockouts.remainingSeconds(2, nowMs = 1_000))
        lockouts.record(2, verify(unlocked = true), nowMs = 2_000)
        assertEquals(0, lockouts.remainingSeconds(2, nowMs = 2_000))
        assertEquals(emptyMap<Int, Long>(), lockouts.lockedUntil.value)
    }
}
