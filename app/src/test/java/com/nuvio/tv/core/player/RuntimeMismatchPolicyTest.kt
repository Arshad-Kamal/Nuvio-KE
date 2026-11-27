package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeMismatchPolicyTest {

    private fun minutes(m: Double) = (m * 60_000).toLong()

    private fun eval(durationMs: Long?, expectedMs: Long?, resumed: Boolean = false, episode: Boolean = false) =
        RuntimeMismatchPolicy.evaluate(durationMs, expectedMs, resumed, episode)

    private fun episode(durationMs: Long, expectedMs: Long) = eval(durationMs, expectedMs, episode = true)

    @Test
    fun `Franklin case - 64 min file for a 23 min episode warns as longer`() {
        val m = eval(3_852_475L, minutes(23.0))
        assertNotNull(m)
        assertTrue(m!!.longer)
        assertEquals(3_852_475L, m.durationMs)
        assertEquals(minutes(23.0), m.expectedRuntimeMs)
    }

    @Test
    fun `exactly 10 percent either side does not warn, 1 ms past does`() {
        // 10% of 120 min = 720,000 ms exactly.
        assertNull(eval(minutes(108.0), minutes(120.0)))
        assertNull(eval(minutes(132.0), minutes(120.0)))
        assertNotNull(eval(minutes(120.0) + 720_001L, minutes(120.0)))
        val shorter = eval(minutes(120.0) - 720_001L, minutes(120.0))
        assertNotNull(shorter)
        assertFalse(shorter!!.longer)
    }

    @Test
    fun `short titles get a 60 s floor for whole-minute metadata`() {
        // 10% of 6 min is 36 s; the 60 s floor binds.
        assertNull(eval(minutes(6.0) + 60_000L, minutes(6.0)))
        assertNotNull(eval(minutes(6.0) + 60_001L, minutes(6.0)))
        assertNotNull(eval(minutes(6.0) - 61_000L, minutes(6.0)))
    }

    @Test
    fun `5 min runtime is judged, under it is not`() {
        assertNotNull(eval(minutes(30.0), minutes(5.0)))
        assertNull(eval(minutes(30.0), minutes(5.0) - 1L))
    }

    @Test
    fun `resumed playback never warns`() {
        assertNull(eval(3_852_475L, minutes(23.0), resumed = true))
    }

    @Test
    fun `unknown runtime or duration does not judge`() {
        assertNull(eval(3_852_475L, null))
        assertNull(eval(null, minutes(23.0)))
        assertNull(eval(0L, minutes(23.0)))
        // Media3 C.TIME_UNSET (Long.MIN_VALUE + 1) must never reach the subtraction.
        assertNull(eval(Long.MIN_VALUE + 1, minutes(23.0)))
    }

    // Spec 142 (F13): episodes get the wider EPISODE_TOLERANCE_RATIO; Shield window 2026-11-20 files.

    @Test
    fun `correct broadcast-cut episodes do not warn`() {
        // The Beverly Hillbillies S1 E1, 22.3 min vs a listed 25 m (-10.8%): warned under the film rule.
        assertNotNull(eval(1_338_336L, minutes(25.0)))
        assertNull(episode(1_338_336L, minutes(25.0)))
        // The Twilight Zone S1 E2, 28.8 min vs 25 m (+15.0%).
        assertNull(episode(1_725_432L, minutes(25.0)))
        // A 22 min sitcom episode listed at 30 m (-26.7%, Spec 35's noted risk).
        assertNull(episode(minutes(22.0), minutes(30.0)))
    }

    @Test
    fun `a different show still warns as an episode`() {
        // The 2024 Dora reboot file served as the 2000 S1 E5: 11.9 min vs 25 m (-52.5%).
        val shorter = episode(712_760L, minutes(25.0))
        assertNotNull(shorter)
        assertFalse(shorter!!.longer)
        // Spec 35's Franklin case: 64 min vs 23 m (+179%).
        assertTrue(episode(3_852_475L, minutes(23.0))!!.longer)
    }

    @Test
    fun `episode tolerance is inclusive either side, 1 ms past warns`() {
        val expected = minutes(25.0)
        val toleranceMs = (expected * RuntimeMismatchPolicy.EPISODE_TOLERANCE_RATIO).toLong()
        assertNull(episode(expected + toleranceMs, expected))
        assertNull(episode(expected - toleranceMs, expected))
        assertNotNull(episode(expected + toleranceMs + 1L, expected))
        assertNotNull(episode(expected - toleranceMs - 1L, expected))
    }

    @Test
    fun `episodes keep the resume, unknown and short-runtime rules`() {
        assertNull(eval(712_760L, minutes(25.0), resumed = true, episode = true))
        assertNull(eval(712_760L, null, episode = true))
        assertNull(episode(minutes(30.0), minutes(5.0) - 1L))
    }
}
