package com.nuvio.tv.ui.screens.player

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 140 D3: the OSD title's line height follows the autoSize-chosen font size. */
class OsdTitleStyleTest {

    @Test
    fun spLineHeightBecomesProportionalEm() {
        val style = TextStyle(fontSize = 18.sp, lineHeight = 24.sp).withProportionalLineHeight()
        assertTrue(style.lineHeight.isEm)
        assertEquals(24f / 18f, style.lineHeight.value, 1e-6f)
        assertEquals(18.sp, style.fontSize)
        // At the 14 sp minimum the lines sit 18.67 sp apart (stage 10 of the approved mockup).
        assertEquals(18.67f, 14f * style.lineHeight.value, 0.01f)
    }

    @Test
    fun nonSpInputsAreUnchanged() {
        listOf(
            TextStyle(fontSize = 18.sp, lineHeight = 1.3.em),
            TextStyle(fontSize = 18.sp),
            TextStyle(fontSize = TextUnit.Unspecified, lineHeight = 24.sp),
            TextStyle(fontSize = 0.sp, lineHeight = 24.sp)
        ).forEach { assertSame(it, it.withProportionalLineHeight()) }
    }
}
