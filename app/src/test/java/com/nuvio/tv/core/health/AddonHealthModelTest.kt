package com.nuvio.tv.core.health

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression coverage for the health breaker's state machine, in particular the
 * half-open wedge (G2-3): once the first cooldown expired, failures could never
 * re-open the breaker — the open branch required openUntil == 0, unreachable once
 * openUntil was stuck in the past — so a still-down source read DEGRADED forever
 * and DOWN was unreachable.
 */
class AddonHealthModelTest {

    private fun fail(atMs: Long) = HealthSample(atMs, HealthOutcome.FAILURE, 10_000L)
    private fun ok(atMs: Long) = HealthSample(atMs, HealthOutcome.SUCCESS, 100L)
    private fun empty(atMs: Long) = HealthSample(atMs, HealthOutcome.EMPTY, 100L)

    private fun applied(vararg samples: HealthSample): HealthRecord =
        samples.fold(HealthRecord()) { rec, s -> AddonHealthModel.applySample(rec, s) }

    @Test
    fun `opens after two consecutive failures and reads DOWN during cooldown`() {
        val rec = applied(fail(0), fail(1))
        assertEquals(1L + AddonHealthModel.COOLDOWN_MS, rec.breakerOpenUntilMs)
        assertEquals(AddonHealthLevel.DOWN, AddonHealthModel.deriveLevel(rec, nowMs = 2))
    }

    @Test
    fun `ignores outcomes while the cooldown is still running`() {
        val rec = applied(fail(0), fail(1), ok(1_000), ok(2_000))
        // Mid-cooldown successes must not close the breaker early.
        assertEquals(1L + AddonHealthModel.COOLDOWN_MS, rec.breakerOpenUntilMs)
        assertEquals(AddonHealthLevel.DOWN, AddonHealthModel.deriveLevel(rec, nowMs = 2_001))
    }

    @Test
    fun `half-open wedge regression - failures after expiry re-arm the cooldown`() {
        val opened = applied(fail(0), fail(1))
        val afterExpiry = AddonHealthModel.applySample(opened, fail(400_000))
        assertEquals(400_000L + AddonHealthModel.COOLDOWN_MS, afterExpiry.breakerOpenUntilMs)
        assertEquals(AddonHealthLevel.DOWN, AddonHealthModel.deriveLevel(afterExpiry, nowMs = 400_001))
    }

    @Test
    fun `half-open closes after two consecutive successes`() {
        val opened = applied(fail(0), fail(1))
        val one = AddonHealthModel.applySample(opened, ok(400_000))
        assertEquals(0, one.consecutiveFailures)
        assertEquals(AddonHealthLevel.DEGRADED, AddonHealthModel.deriveLevel(one, nowMs = 400_001))
        val closed = AddonHealthModel.applySample(one, ok(400_002))
        assertEquals(0L, closed.breakerOpenUntilMs)
        assertEquals(AddonHealthLevel.HEALTHY, AddonHealthModel.deriveLevel(closed, nowMs = 400_003))
    }

    @Test
    fun `half-open empty outcome resets counters without arming or closing`() {
        val opened = applied(fail(0), fail(1))
        val emptied = AddonHealthModel.applySample(opened, empty(400_000))
        // EMPTY is not the source's fault: it wipes the counters and leaves the
        // (expired) breaker untouched — neither re-arm nor close.
        assertEquals(0, emptied.consecutiveFailures)
        assertEquals(0, emptied.consecutiveSuccesses)
        assertEquals(1L + AddonHealthModel.COOLDOWN_MS, emptied.breakerOpenUntilMs)
        assertEquals(AddonHealthLevel.DEGRADED, AddonHealthModel.deriveLevel(emptied, nowMs = 400_001))
        // Fresh evidence decides from a clean slate: two failures re-arm.
        val one = AddonHealthModel.applySample(emptied, fail(400_002))
        val reArmed = AddonHealthModel.applySample(one, fail(400_003))
        assertEquals(400_003L + AddonHealthModel.COOLDOWN_MS, reArmed.breakerOpenUntilMs)
    }

    @Test
    fun `half-open re-opens after success then two failures`() {
        val opened = applied(fail(0), fail(1))
        val one = AddonHealthModel.applySample(opened, ok(400_000))
        val two = AddonHealthModel.applySample(one, fail(400_002))
        // The single success cleared the cooldown-era failures; one post-expiry
        // failure is not hard-down evidence yet, but two in a row re-arm.
        assertEquals(1, two.consecutiveFailures)
        assertEquals(1L + AddonHealthModel.COOLDOWN_MS, two.breakerOpenUntilMs)
        val three = AddonHealthModel.applySample(two, fail(400_003))
        assertEquals(400_003L + AddonHealthModel.COOLDOWN_MS, three.breakerOpenUntilMs)
        assertEquals(AddonHealthLevel.DOWN, AddonHealthModel.deriveLevel(three, nowMs = 400_004))
    }
}
