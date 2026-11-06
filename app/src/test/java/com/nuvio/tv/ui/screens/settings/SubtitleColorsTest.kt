package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleColorsTest {

    private val text = SubtitleColorTarget.TEXT.swatches
    private val background = SubtitleColorTarget.BACKGROUND.swatches

    @Test
    fun `a stored colour matches its swatch by RGB, alpha aside`() {
        assertEquals(R.string.sub_colour_white, subtitleSwatchFor(0xFFFFFFFF.toInt(), text)?.name)
        // The retired "Black 50%" background reads as Black at 50%.
        assertEquals(R.string.sub_colour_black, subtitleSwatchFor(0x80000000.toInt(), background)?.name)
        assertNull(subtitleSwatchFor(0xFF123456.toInt(), text))
    }

    @Test
    fun `alpha 0 is None where None is offered, else off the list`() {
        assertEquals(R.string.action_none, subtitleSwatchFor(0, background)?.name)
        assertEquals(R.string.action_none, subtitleSwatchFor(0x00FFFFFF, background)?.name)
        assertNull(subtitleSwatchFor(0x00FFFFFF, text))
    }

    @Test
    fun `picking a swatch keeps the opacity, starting opaque from a hidden colour`() {
        val yellow = text.first { it.name == R.string.sub_colour_yellow }
        assertEquals(0x80FFFF00.toInt(), subtitlePickSwatch(yellow, 0x80FFFFFF.toInt()))
        assertEquals(0xFFFFFF00.toInt(), subtitlePickSwatch(yellow, 0x00FFFFFF))
        assertEquals(0, subtitlePickSwatch(background.first(), 0xFF000000.toInt()))
    }

    @Test
    fun `opacity converts both ways and labels only partial alpha`() {
        assertEquals(100, subtitleOpacityPercent(0xFF000000.toInt()))
        assertEquals(50, subtitleOpacityPercent(0x80000000.toInt()))
        assertEquals(0, subtitleOpacityPercent(0x00000000))
        assertEquals(0x80FF6B6B.toInt(), subtitleColorWithOpacity(0xFFFF6B6B.toInt(), 50))
        assertEquals(0xFFFF6B6B.toInt(), subtitleColorWithOpacity(0x10FF6B6B, 100))
        assertTrue(subtitleOpacityShown(0x80000000.toInt()))
        assertFalse(subtitleOpacityShown(0xFF000000.toInt()))
        assertFalse(subtitleOpacityShown(0))
    }

    @Test
    fun `an off-list colour reads as its hex`() {
        assertEquals("#12AB9F", subtitleColorHex(0xFF12AB9F.toInt()))
        assertEquals("#000000", subtitleColorHex(0x80000000.toInt()))
    }
}
