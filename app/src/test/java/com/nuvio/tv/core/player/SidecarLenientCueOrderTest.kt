package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * L2-07: the lenient SRT/VTT fallback returned cues in file order, and
 * collectActiveSidecarCues stops at the first cue starting after the position, so one
 * out-of-order block hid every later cue.
 */
class SidecarLenientCueOrderTest {

    private val outOfOrderSrt = """
        1
        00:00:10,000 --> 00:00:12,000
        Second

        2
        00:00:01,000 --> 00:00:03,000
        First

        3
        00:00:20,000 --> 00:00:22,000
        Third
    """.trimIndent()

    @Test
    fun lenientParseReturnsCuesSortedByStartTime() {
        val cues = parseSidecarTimedCuesLenient(outOfOrderSrt, "https://example.test/sub.srt")

        assertEquals(listOf(1_000_000L, 10_000_000L, 20_000_000L), cues.map { it.startTimeUs })
    }

    @Test
    fun outOfOrderBlockDoesNotHideAnEarlierCue() {
        val cues = parseSidecarTimedCuesLenient(outOfOrderSrt, "https://example.test/sub.srt")

        val active = collectActiveSidecarCues(cues, positionUs = 2_000_000L)

        assertEquals(listOf("First"), active.map { it.text.toString() })
    }
}
