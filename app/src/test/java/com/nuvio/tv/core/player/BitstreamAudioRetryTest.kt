package com.nuvio.tv.core.player

import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5 (upstream-sync Register A2): a refused bitstream AudioTrack open retries the same
 * configuration twice per stream, refilled after 60 s, after waiting for the output.
 */
class BitstreamAudioRetryTest {

    private val url = "https://host/remux.mkv"

    @Test
    fun `two retries per stream, then none`() {
        val budget = BitstreamAudioRetryBudget()
        assertEquals(1, budget.take(url, nowMs = 1_000))
        assertEquals(2, budget.take(url, nowMs = 2_000))
        assertNull(budget.take(url, nowMs = 3_000))
    }

    @Test
    fun `a new stream starts with a full budget`() {
        val budget = BitstreamAudioRetryBudget()
        budget.take(url, nowMs = 1_000)
        budget.take(url, nowMs = 2_000)
        assertEquals(1, budget.take("https://host/other.mkv", nowMs = 3_000))
    }

    @Test
    fun `the budget refills 60 s after the last retry's rebuild, not after it was scheduled`() {
        val budget = BitstreamAudioRetryBudget()
        budget.take(url, nowMs = 0)
        budget.take(url, nowMs = 1_000)
        budget.noteRebuild(nowMs = 21_000) // the second retry waited 20 s for HDMI
        assertNull(budget.take(url, nowMs = 61_000))
        assertEquals(1, budget.take(url, nowMs = 21_000 + BitstreamAudioRetryBudget.REFILL_AFTER_MS))
    }

    @Test
    fun `retry delay grows with the attempt`() {
        assertEquals(400L, BitstreamAudioRetryBudget.delayMs(1))
        assertEquals(800L, BitstreamAudioRetryBudget.delayMs(2))
    }

    @Test
    fun `only a refused open of an allowed bitstream is retried`() {
        val init = PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED
        assertTrue(isBitstreamSameConfigRetryCandidate(init, "", MimeTypes.AUDIO_TRUEHD, policyDeniesMime = false))
        assertTrue(
            isBitstreamSameConfigRetryCandidate(
                PlaybackException.ERROR_CODE_UNSPECIFIED, "AudioTrack init failed", MimeTypes.AUDIO_E_AC3_JOC, false
            )
        )
        // a 5002 opened; PCM already decodes; a denied format already decodes
        assertFalse(
            isBitstreamSameConfigRetryCandidate(
                PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED, "", MimeTypes.AUDIO_TRUEHD, false
            )
        )
        assertFalse(isBitstreamSameConfigRetryCandidate(init, "", MimeTypes.AUDIO_RAW, false))
        assertFalse(isBitstreamSameConfigRetryCandidate(init, "", null, false))
        assertFalse(isBitstreamSameConfigRetryCandidate(init, "", MimeTypes.AUDIO_DTS_HD, policyDeniesMime = true))
    }

    @Test
    fun `waits while HDMI does not take the format or no output is routed`() {
        assertFalse(bitstreamRetryWaitOver(outputClaimsFormat = false, routeKey = "type:hdmi|name:tv", waitedMs = 5_000))
        assertFalse(bitstreamRetryWaitOver(outputClaimsFormat = false, routeKey = null, waitedMs = 5_000))
    }

    @Test
    fun `stops waiting when the format is claimed, a non-HDMI output takes over, or at the cap`() {
        assertTrue(bitstreamRetryWaitOver(outputClaimsFormat = true, routeKey = "type:hdmi|name:avr", waitedMs = 0))
        assertTrue(bitstreamRetryWaitOver(false, "type:bluetooth_a2dp|name:buds", waitedMs = 0))
        assertTrue(bitstreamRetryWaitOver(false, "type:hdmi|name:tv", waitedMs = BitstreamAudioRetryBudget.MAX_WAIT_MS))
    }

    @Test
    fun `hdmi, arc and earc route keys are HDMI`() {
        assertTrue(AudioOutputRouteDetector.isHdmiRouteKey("type:hdmi|name:avr"))
        assertTrue(AudioOutputRouteDetector.isHdmiRouteKey("type:hdmi_arc|name:tv"))
        assertTrue(AudioOutputRouteDetector.isHdmiRouteKey("type:hdmi_earc|name:tv"))
        assertFalse(AudioOutputRouteDetector.isHdmiRouteKey("type:usb_device|name:dac"))
    }
}
