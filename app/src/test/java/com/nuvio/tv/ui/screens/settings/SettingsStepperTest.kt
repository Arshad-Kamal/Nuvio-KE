package com.nuvio.tv.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsStepperTest {

    private val sizes = listOf(256, 512, 1024, 2048)

    @Test
    fun `a list value steps to its neighbour and stays put at either end`() {
        assertEquals(1024, stepValue(sizes, 512, 1))
        assertEquals(256, stepValue(sizes, 512, -1))
        assertEquals(256, stepValue(sizes, 256, -1))
        assertEquals(2048, stepValue(sizes, 2048, 1))
        assertEquals(2048, stepValue(sizes, 256, 10))
    }

    @Test
    fun `an off-list value moves to the nearest entry that way first`() {
        assertEquals(1024, stepValue(sizes, 700, 1))
        assertEquals(512, stepValue(sizes, 700, -1))
        assertEquals(2048, stepValue(sizes, 700, 2))
        assertEquals(256, stepValue(sizes, 700, -2))
        assertEquals(700, stepValue(sizes, 700, 0))
    }

    @Test
    fun `an off-list value past an end never moves the wrong way`() {
        assertEquals(4096, stepValue(sizes, 4096, 1))
        assertEquals(2048, stepValue(sizes, 4096, -1))
        assertEquals(100, stepValue(sizes, 100, -1))
        assertEquals(256, stepValue(sizes, 100, 1))
    }

    @Test
    fun `a range value steps by its step and clamps`() {
        assertEquals(15, stepValue(min = 5, max = 30, step = 5, current = 10, delta = 1))
        assertEquals(5, stepValue(min = 5, max = 30, step = 5, current = 5, delta = -1))
        assertEquals(30, stepValue(min = 5, max = 30, step = 5, current = 28, delta = 1))
    }

    @Test
    fun `a segment index clamps at both ends`() {
        assertEquals(1, stepIndex(size = 3, index = 0, delta = 1))
        assertEquals(0, stepIndex(size = 3, index = 0, delta = -1))
        assertEquals(2, stepIndex(size = 3, index = 2, delta = 1))
        assertEquals(0, stepIndex(size = 0, index = 0, delta = 1))
    }

    @Test
    fun `the track fill follows the value's place`() {
        assertEquals(0f, stepFraction(sizes, 256), 0.0001f)
        assertEquals(1f, stepFraction(sizes, 2048), 0.0001f)
        assertEquals(1f / 3f, stepFraction(sizes, 512), 0.0001f)
        assertEquals(1f / 3f, stepFraction(sizes, 700), 0.0001f)
        assertEquals(0f, stepFraction(sizes, 100), 0.0001f)
        assertEquals(0f, stepFraction(listOf(5), 5), 0.0001f)
        assertEquals(0.5f, stepFraction(min = 0, max = 10, current = 5), 0.0001f)
        assertEquals(1f, stepFraction(min = 0, max = 10, current = 50), 0.0001f)
        assertEquals(0f, stepFraction(min = 3, max = 3, current = 3), 0.0001f)
    }
}
