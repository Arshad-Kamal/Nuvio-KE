package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec 25 U6c: the subtitles style view's Text Opacity stepper. */
class SubtitleStyleStepsTest {

    private fun alphaOf(argb: Int) = argb ushr 24

    @Test
    fun opacityPercentRoundsTheAlphaByte() {
        assertEquals(100, textOpacityPercent(0xFFFFFFFF.toInt()))
        assertEquals(0, textOpacityPercent(0x00FFFFFF))
        assertEquals(50, textOpacityPercent(0x80FFFFFF.toInt()))
    }

    @Test
    fun stepMovesAlphaTenPointsAndKeepsRgb() {
        val stepped = stepTextOpacity(0xFFFFD700.toInt(), -10)
        assertEquals(230, alphaOf(stepped))
        assertEquals(0xFFD700, stepped and 0x00FFFFFF)
        assertEquals(90, textOpacityPercent(stepped))
        assertEquals(0xFFFFD700.toInt(), stepTextOpacity(stepped, 10))
    }

    @Test
    fun stepRoundsFromTheDisplayedPercent() {
        // 0x80 = 50.2 % reads as 50 %; +10 lands on 60 % = 153.
        assertEquals(153, alphaOf(stepTextOpacity(0x80FFFFFF.toInt(), 10)))
        assertEquals(26, alphaOf(stepTextOpacity(0x00000000, 10)))
    }

    @Test
    fun stepClampsAtBothEnds() {
        assertEquals(0xFFFFFFFF.toInt(), stepTextOpacity(0xFFFFFFFF.toInt(), 10))
        assertEquals(0x00FFFFFF, stepTextOpacity(0x00FFFFFF, -10))
        assertEquals(0, alphaOf(stepTextOpacity(0x1AFFFFFF, -10)))
    }
}
