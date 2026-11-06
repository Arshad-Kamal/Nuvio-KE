package com.nuvio.tv.core.player

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Upstream-sync Register A2 P2: Media3's stuck-buffering watchdog (8000 + "Playback stuck
 * buffering and not loading") is a loading deadlock. It is retryable on the same link and is
 * never mistaken for the runtime-check fault the DV and audio arms handle.
 */
class StuckBufferingWatchdogClassificationTest {

    private fun runtimeCheck(message: String) = PlaybackException(
        "Unexpected runtime error",
        IllegalStateException(message),
        PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK
    )

    @Test
    fun `the media3 watchdog is recognised and retryable`() {
        val watchdog = runtimeCheck("Playback stuck buffering and not loading")
        assertTrue(watchdog.isStuckBufferingWatchdog())
        assertTrue(isRetryablePlaybackError(watchdog))
    }

    @Test
    fun `another runtime-check fault is neither the watchdog nor retryable`() {
        val assertion = runtimeCheck("converted bitstream tripped an assertion")
        assertFalse(assertion.isStuckBufferingWatchdog())
        assertFalse(isRetryablePlaybackError(assertion))
    }

    @Test
    fun `the watchdog text under another code is not the watchdog`() {
        assertFalse(
            isStuckBufferingWatchdog(
                PlaybackException.ERROR_CODE_DECODING_FAILED,
                "Playback stuck buffering and not loading"
            )
        )
    }
}
