package com.nuvio.tv.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleEdgeStyleTest {

    @Test
    fun storedValueWinsOverLegacyToggle() {
        assertEquals(SubtitleEdgeStyle.DROP_SHADOW, resolveSubtitleEdgeStyle("DROP_SHADOW", true))
        assertEquals(SubtitleEdgeStyle.NONE, resolveSubtitleEdgeStyle("NONE", true))
        assertEquals(SubtitleEdgeStyle.OUTLINE, resolveSubtitleEdgeStyle("OUTLINE", false))
    }

    @Test
    fun legacyToggleMapsOneToOne() {
        assertEquals(SubtitleEdgeStyle.OUTLINE, resolveSubtitleEdgeStyle(null, true))
        assertEquals(SubtitleEdgeStyle.NONE, resolveSubtitleEdgeStyle(null, false))
    }

    @Test
    fun freshProfileDefaultsToOutline() {
        // Upstream defaults new profiles to DROP_SHADOW; the fork keeps Outline.
        assertEquals(SubtitleEdgeStyle.OUTLINE, resolveSubtitleEdgeStyle(null, null))
        assertEquals(SubtitleEdgeStyle.OUTLINE, SubtitleStyleSettings().edgeStyle)
    }

    @Test
    fun unknownStoredValueFallsBackToLegacy() {
        assertEquals(SubtitleEdgeStyle.NONE, resolveSubtitleEdgeStyle("RAISED", false))
        assertEquals(SubtitleEdgeStyle.OUTLINE, resolveSubtitleEdgeStyle("RAISED", null))
    }
}
