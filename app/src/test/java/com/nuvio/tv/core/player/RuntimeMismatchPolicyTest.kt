package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeMismatchPolicyTest {

    private fun minutes(m: Double) = (m * 60_000).toLong()

    private fun eval(durationMs: Long?, expectedMs: Long?, resumed: Boolean = false) =
        RuntimeMismatchPolicy.evaluate(durationMs, expectedMs, resumed)

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
}
