package com.nuvio.tv.core.player

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec 21 P1: pins [PlayerStallWatchdogPolicy] (the stall watchdog's self-seek decision). */
class PlayerStallWatchdogPolicyTest {

    private val threshold = PlayerRuntimeController.STALL_WATCHDOG_THRESHOLD_MS

    private fun evaluate(
        buffered: Long = 60_000L,
        playhead: Long = 50_000L,
        duration: Long = 7_200_000L,
        stalledFor: Long = threshold,
    ) = PlayerStallWatchdogPolicy.evaluate(
        PlayerStallWatchdogPolicy.Input(
            bufferedPositionMs = buffered,
            playheadMs = playhead,
            durationMs = duration,
            stalledForMs = stalledFor,
        )
    )

    @Test
    fun `below the threshold keeps waiting even when every other check would skip`() {
        assertEquals(
            PlayerStallWatchdogPolicy.Decision.KeepWaiting,
            evaluate(stalledFor = threshold - 1, duration = C.TIME_UNSET)
        )
    }

    @Test
    fun `at the threshold a buffered edge ahead of the playhead seeks just past it`() {
        assertEquals(
            PlayerStallWatchdogPolicy.Decision.SeekPastBufferedEdge(60_000L + PlayerStallWatchdogPolicy.SKIP_PAST_BUFFERED_MS),
            evaluate()
        )
    }

    @Test
    fun `unknown or non-positive duration skips the self-seek`() {
        assertEquals(PlayerStallWatchdogPolicy.Decision.SkipUnknownDuration, evaluate(duration = C.TIME_UNSET))
        assertEquals(PlayerStallWatchdogPolicy.Decision.SkipUnknownDuration, evaluate(duration = 0L))
        assertEquals(PlayerStallWatchdogPolicy.Decision.SkipUnknownDuration, evaluate(duration = -5L))
    }

    @Test
    fun `buffered position at or behind the playhead skips`() {
        assertEquals(PlayerStallWatchdogPolicy.Decision.SkipBufferedNotAhead, evaluate(buffered = 50_000L))
        assertEquals(PlayerStallWatchdogPolicy.Decision.SkipBufferedNotAhead, evaluate(buffered = 40_000L))
    }

    @Test
    fun `a negative playhead reads as zero`() {
        assertEquals(PlayerStallWatchdogPolicy.Decision.SkipBufferedNotAhead, evaluate(buffered = 0L, playhead = -1L))
        assertEquals(
            PlayerStallWatchdogPolicy.Decision.SeekPastBufferedEdge(10L + PlayerStallWatchdogPolicy.SKIP_PAST_BUFFERED_MS),
            evaluate(buffered = 10L, playhead = -1L)
        )
    }

    @Test
    fun `the target clamps to one millisecond before the end`() {
        assertEquals(
            PlayerStallWatchdogPolicy.Decision.SeekPastBufferedEdge(99_999L),
            evaluate(buffered = 99_900L, playhead = 90_000L, duration = 100_000L)
        )
    }

    @Test
    fun `a clamped target that is not ahead of the playhead skips`() {
        assertEquals(
            PlayerStallWatchdogPolicy.Decision.SkipTargetNotForward,
            evaluate(buffered = 100_000L, playhead = 99_999L, duration = 100_000L)
        )
    }

    @Test
    fun `a buffered position near Long MAX does not overflow`() {
        assertEquals(
            PlayerStallWatchdogPolicy.Decision.SeekPastBufferedEdge(99_999L),
            evaluate(buffered = Long.MAX_VALUE - 1, playhead = 0L, duration = 100_000L)
        )
    }
}
