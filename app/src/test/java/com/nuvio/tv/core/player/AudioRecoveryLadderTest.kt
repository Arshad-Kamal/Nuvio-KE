package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * DRDYK B1: the audio-failure ladder walks safe audio, PCM, audio off, then gives up. The PCM
 * rung's arming (pendingAudioPcmFallbackRebuild, so the flag survives initializePlayer) lives in
 * tryAudioRecoveryLadder next to tryAudioTrackPcmFallback's.
 */
class AudioRecoveryLadderTest {

    @Test
    fun `rungs come in order safe audio, PCM, audio off`() {
        assertEquals(AudioRecoveryRung.SAFE_AUDIO, nextAudioRecoveryRung(false, false, false))
        assertEquals(AudioRecoveryRung.PCM, nextAudioRecoveryRung(true, false, false))
        assertEquals(AudioRecoveryRung.AUDIO_OFF, nextAudioRecoveryRung(true, true, false))
    }

    @Test
    fun `audio off is terminal even after the rebuild cleared the PCM flag`() {
        assertNull(nextAudioRecoveryRung(true, true, true))
        assertNull(nextAudioRecoveryRung(true, false, true))
    }

    @Test
    fun `safe audio comes first even after a 5001 PCM rebuild`() {
        // tryAudioTrackPcmFallback can set the PCM flag before safe audio was ever tried.
        assertEquals(AudioRecoveryRung.SAFE_AUDIO, nextAudioRecoveryRung(false, true, false))
    }
}
