package com.nuvio.tv.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Test

/** The row band's hero settle delay ([heroSettleDelayMs]). */
class HeroSettleDelayTest {

    @Test
    fun `first focus change uses the full debounce`() {
        assertEquals(MODERN_HERO_FOCUS_DEBOUNCE_MS, heroSettleDelayMs(lastNavAtMs = 0L, nowMs = 10_000L))
        assertEquals(450L, MODERN_HERO_FOCUS_DEBOUNCE_MS)
    }

    @Test
    fun `changes under 130 ms apart use the rapid delay`() {
        assertEquals(400L, heroSettleDelayMs(lastNavAtMs = 10_000L, nowMs = 10_001L))
        assertEquals(400L, heroSettleDelayMs(lastNavAtMs = 10_000L, nowMs = 10_129L))
    }

    @Test
    fun `changes 130 ms or more apart use the full debounce`() {
        assertEquals(450L, heroSettleDelayMs(lastNavAtMs = 10_000L, nowMs = 10_130L))
        assertEquals(450L, heroSettleDelayMs(lastNavAtMs = 10_000L, nowMs = 15_000L))
    }

    @Test
    fun `same-millisecond or backwards clock uses the full debounce`() {
        assertEquals(450L, heroSettleDelayMs(lastNavAtMs = 10_000L, nowMs = 10_000L))
        assertEquals(450L, heroSettleDelayMs(lastNavAtMs = 10_000L, nowMs = 9_000L))
    }
}
