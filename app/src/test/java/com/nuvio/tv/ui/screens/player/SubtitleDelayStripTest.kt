package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.player.SUBTITLE_DELAY_MAX_MS
import com.nuvio.tv.core.player.SUBTITLE_DELAY_MIN_MS
import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec 25 U6d: the delay strip's knob position and value text. */
class SubtitleDelayStripTest {

    @Test
    fun zeroSitsMidTrackAndTheEndsAtTheEdges() {
        assertEquals(0.5f, subtitleDelayFraction(0), 0.0001f)
        assertEquals(0f, subtitleDelayFraction(SUBTITLE_DELAY_MIN_MS), 0.0001f)
        assertEquals(1f, subtitleDelayFraction(SUBTITLE_DELAY_MAX_MS), 0.0001f)
        assertEquals(0.75f, subtitleDelayFraction(SUBTITLE_DELAY_MAX_MS / 2), 0.0001f)
    }

    @Test
    fun outOfRangeClamps() {
        assertEquals(0f, subtitleDelayFraction(SUBTITLE_DELAY_MIN_MS - 1_000), 0.0001f)
        assertEquals(1f, subtitleDelayFraction(SUBTITLE_DELAY_MAX_MS + 1_000), 0.0001f)
    }

    @Test
    fun valueIsSignedSecondsToTheMillisecond() {
        assertEquals("+0.250s", formatSubtitleDelay(250))
        assertEquals("-1.100s", formatSubtitleDelay(-1_100))
        assertEquals("+0.000s", formatSubtitleDelay(0))
    }
}
