package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** Register A2 P8: the Sync Line list centres on the line on screen, video time - delay. */
class SyncLineAnchorTest {

    @Test
    fun `no delay anchors at video time`() {
        assertEquals(60_000L, syncLineAnchorMs(60_000L, 0))
    }

    @Test
    fun `positive delay (subtitles later) anchors earlier in cue time`() {
        assertEquals(57_500L, syncLineAnchorMs(60_000L, 2_500))
    }

    @Test
    fun `negative delay (subtitles earlier) anchors later in cue time`() {
        assertEquals(61_000L, syncLineAnchorMs(60_000L, -1_000))
    }

    @Test
    fun `anchor never goes below zero`() {
        assertEquals(0L, syncLineAnchorMs(1_000L, 5_000))
    }
}
