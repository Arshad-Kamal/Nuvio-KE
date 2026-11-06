package com.nuvio.tv.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [NuvioScrollDefaults.minimalScrollSpec] (F-10): a request inside the margin-padded viewport
 * asks for no scroll, so a nested list never hands an unconsumable remainder to its parent.
 */
class NuvioScrollDefaultsTest {

    // Ratings grid shape: 10 px ring room, 60 px cells, 3 rows in a 212 px viewport.
    private val spec = NuvioScrollDefaults.minimalScrollSpec(marginPx = 10)
    private val container = 212f

    private fun distance(offset: Float, size: Float = 60f) =
        spec.calculateScrollDistance(offset, size, container)

    @Test
    fun `first row at the content padding asks for no scroll`() {
        // smoothScrollSpec asked for about -49 here (centre to 42%), past the list top: the rest
        // scrolled the page.
        assertEquals(0f, distance(offset = 10f), 0f)
    }

    @Test
    fun `last row at the trailing padding asks for no scroll`() {
        assertEquals(0f, distance(offset = 142f), 0f)
    }

    @Test
    fun `row above the viewport scrolls back to show its margin`() {
        assertEquals(-40f, distance(offset = -30f), 0f)
    }

    @Test
    fun `row below the viewport scrolls forward to show its margin`() {
        assertEquals(68f, distance(offset = 210f), 0f)
    }

    @Test
    fun `item larger than the viewport that already covers it stays put`() {
        assertEquals(0f, distance(offset = -20f, size = 300f), 0f)
    }

    @Test
    fun `margin shrinks to the room left so a clipped item still scrolls in once`() {
        // 70 px viewport, 60 px item: 5 px of margin fit, not 15.
        val tight = NuvioScrollDefaults.minimalScrollSpec(marginPx = 15)
        assertEquals(-7f, tight.calculateScrollDistance(-2f, 60f, 70f), 0f)
        assertEquals(0f, tight.calculateScrollDistance(5f, 60f, 70f), 0f)
    }
}
