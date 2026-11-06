package com.nuvio.tv.core.player

import android.graphics.Color
import androidx.media3.ui.CaptionStyleCompat
import com.nuvio.tv.data.local.SubtitleEdgeStyle
import com.nuvio.tv.data.local.SubtitleStyleSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleEdgePresentationTest {

    private val yellow = 0xFFFFD700.toInt()
    private val cyan = 0xFF00E5FF.toInt()
    private val box = 0x80000000.toInt()

    @Test
    fun exoEdgeTypeAndColourPerStyle() {
        val base = SubtitleStyleSettings(outlineColor = cyan)
        val none = exoSubtitleLook(base.copy(edgeStyle = SubtitleEdgeStyle.NONE), ownSsaStyle = false)
        val outline = exoSubtitleLook(base.copy(edgeStyle = SubtitleEdgeStyle.OUTLINE), ownSsaStyle = false)
        val shadow = exoSubtitleLook(base.copy(edgeStyle = SubtitleEdgeStyle.DROP_SHADOW), ownSsaStyle = false)
        assertEquals(CaptionStyleCompat.EDGE_TYPE_NONE, none.edgeType)
        assertEquals(CaptionStyleCompat.EDGE_TYPE_OUTLINE, outline.edgeType)
        assertEquals(cyan, outline.edgeColor)
        assertEquals(CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, shadow.edgeType)
        assertEquals(SUBTITLE_SHADOW_COLOR, shadow.edgeColor)
    }

    @Test
    fun plainTrackUsesTheUserLook() {
        val look = exoSubtitleLook(
            SubtitleStyleSettings(textColor = yellow, backgroundColor = box, size = 150),
            ownSsaStyle = false
        )
        assertEquals(yellow, look.foregroundColor)
        assertEquals(box, look.backgroundColor)
        assertEquals(36f, look.fixedTextSizeSp!!, 0f)
    }

    @Test
    fun ssaWithoutLibassKeepsFileSizesAndColoursButUserEdge() {
        val style = SubtitleStyleSettings(
            textColor = yellow,
            backgroundColor = box,
            edgeStyle = SubtitleEdgeStyle.OUTLINE,
            outlineColor = cyan
        )
        val look = exoSubtitleLook(style, ownSsaStyle = true)
        assertNull(look.fixedTextSizeSp)
        assertEquals(Color.WHITE, look.foregroundColor)
        assertEquals(Color.TRANSPARENT, look.backgroundColor)
        // Deviation from upstream (EDGE_TYPE_NONE): Media3's Cue carries no edge, so the user's stays.
        assertEquals(CaptionStyleCompat.EDGE_TYPE_OUTLINE, look.edgeType)
        assertEquals(cyan, look.edgeColor)
    }

    @Test
    fun mpvShadowIgnoresOutlineAndBox() {
        val edges = mpvSubtitleEdges(
            SubtitleStyleSettings(edgeStyle = SubtitleEdgeStyle.DROP_SHADOW, backgroundColor = box),
            assSelected = false
        )
        assertEquals(0.0, edges.outlineSize, 0.0)
        assertEquals(1.5, edges.shadowOffset, 0.0)
        assertEquals("outline-and-shadow", edges.borderStyle)
        assertEquals(SUBTITLE_SHADOW_COLOR, edges.backColor)
    }

    @Test
    fun mpvOutlineAndNoneKeepTheExistingMapping() {
        val outline = mpvSubtitleEdges(SubtitleStyleSettings(edgeStyle = SubtitleEdgeStyle.OUTLINE), assSelected = false)
        assertEquals(1.0, outline.outlineSize, 0.0)
        assertEquals(0.0, outline.shadowOffset, 0.0)
        assertEquals("outline-and-shadow", outline.borderStyle)
        val assOutline = mpvSubtitleEdges(
            SubtitleStyleSettings(edgeStyle = SubtitleEdgeStyle.OUTLINE, outlineWidth = 4),
            assSelected = true
        )
        assertEquals(4.0, assOutline.outlineSize, 0.0)
        val noneBoxed = mpvSubtitleEdges(
            SubtitleStyleSettings(edgeStyle = SubtitleEdgeStyle.NONE, backgroundColor = box),
            assSelected = false
        )
        assertEquals(0.0, noneBoxed.outlineSize, 0.0)
        assertEquals(5.0, noneBoxed.shadowOffset, 0.0)
        assertEquals("background-box", noneBoxed.borderStyle)
        assertEquals(box, noneBoxed.backColor)
    }
}
