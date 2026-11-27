package com.nuvio.tv.core.player

import androidx.media3.exoplayer.ExoPlayer
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 142 D2 (F14): starting playback held by a track-AFR switch must not undo the runtime
 * warning's pause. From that coroutine Media3 reports onIsPlayingChanged(true) synchronously and
 * the progress job's first tick (Main.immediate) can warn and pause inside the play call.
 */
class TrackAfrHeldStartTest {

    @Test
    fun `a warning that pauses inside the held start stays paused`() {
        var playWhenReady = false
        var warned = false
        // Each play intent fires the one-shot runtime check, which warns and pauses at once.
        fun playIntent() {
            playWhenReady = true
            if (!warned) {
                warned = true
                playWhenReady = false
            }
        }
        val player = mockk<ExoPlayer>(relaxed = true)
        every { player.playWhenReady } answers { playWhenReady }
        every { player.playWhenReady = any() } answers { if (firstArg()) playIntent() else playWhenReady = false }
        every { player.play() } answers { playIntent() }
        val controller = mockk<PlayerRuntimeController>(relaxed = true)
        every { controller._exoPlayer } returns player
        every { controller.hasRenderedFirstFrame } returns true
        every { controller.userPausedManually } returns false
        every { controller.afrSettleHoldInFlight } returns false

        controller.resumePlaybackAfterTrackAfrIfHeld()

        assertTrue("the held start never played", warned)
        assertFalse("the warning's pause was undone", playWhenReady)
    }
}
