package com.nuvio.tv.core.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F3 outage guard and sink-format seam (upstream-sync Register A2 P1): a refusal is learned only
 * on the build route with the output still claiming the format, and its group comes from the
 * format the sink was opened for, not the renderer's compressed input.
 */
class AudioRejectionLearningTest {

    private val hdmi = "type:hdmi|name:avr"

    @Test
    fun `refusal on the build route with the output still claiming the format is learned`() {
        assertEquals(
            AudioRejectionLearning.LEARN,
            audioRejectionLearning(routeAtBuild = hdmi, routeNow = hdmi, deviceChangedNearRefusal = false, outputStillClaims = true)
        )
    }

    @Test
    fun `output gone (AVR asleep, TV on another input) is not learned`() {
        assertEquals(
            AudioRejectionLearning.OUTPUT_GONE,
            audioRejectionLearning(routeAtBuild = hdmi, routeNow = hdmi, deviceChangedNearRefusal = false, outputStillClaims = false)
        )
    }

    @Test
    fun `route changed under the player is not learned, even if the new output claims the format`() {
        assertEquals(
            AudioRejectionLearning.ROUTE_CHANGED,
            audioRejectionLearning(routeAtBuild = hdmi, routeNow = "type:hdmi|name:tv", deviceChangedNearRefusal = false, outputStillClaims = true)
        )
        assertEquals(
            AudioRejectionLearning.ROUTE_CHANGED,
            audioRejectionLearning(routeAtBuild = null, routeNow = hdmi, deviceChangedNearRefusal = false, outputStillClaims = true)
        )
    }

    @Test
    fun `a device added or removed around the refusal is not learned, even with the output back`() {
        assertEquals(
            AudioRejectionLearning.ROUTE_CHANGING,
            audioRejectionLearning(routeAtBuild = hdmi, routeNow = hdmi, deviceChangedNearRefusal = true, outputStillClaims = true)
        )
    }

    @Test
    fun `the registration replay of connected devices is not a device change`() {
        val known = setOf(2, 7, 9)
        assertFalse(isAudioDeviceSetChange(known, eventDeviceIds = setOf(2, 7, 9), added = true))
        assertFalse(isAudioDeviceSetChange(known, eventDeviceIds = setOf(7), added = true))
    }

    @Test
    fun `a new device id or any removal is a device change`() {
        val known = setOf(2, 7, 9)
        assertTrue(isAudioDeviceSetChange(known, eventDeviceIds = setOf(7, 12), added = true))
        assertTrue(isAudioDeviceSetChange(known, eventDeviceIds = setOf(7), added = false))
    }

    @Test
    fun `no detectable route is not learned`() {
        assertEquals(
            AudioRejectionLearning.NO_ROUTE,
            audioRejectionLearning(routeAtBuild = null, routeNow = null, deviceChangedNearRefusal = false, outputStillClaims = true)
        )
    }

    @Test
    fun `sink input format is read through the cause chain`() {
        val trueHd = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_TRUEHD).build()
        val error = PlaybackException(
            "renderer error",
            AudioSink.InitializationException("AudioTrack init failed 0", 0, trueHd, true, null),
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED
        )
        assertEquals(MimeTypes.AUDIO_TRUEHD, failedAudioTrackInputFormat(error)?.sampleMimeType)
    }

    @Test
    fun `a PCM sink refusal under a compressed renderer format yields no bitstream label`() {
        // FFmpeg decoded TrueHD to PCM: the sink was opened for audio/raw, so nothing is learned.
        val pcm = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).build()
        val error = PlaybackException(
            "renderer error",
            AudioSink.InitializationException("AudioTrack init failed 0", 0, pcm, true, null),
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED
        )
        val format = failedAudioTrackInputFormat(error)
        assertEquals(MimeTypes.AUDIO_RAW, format?.sampleMimeType)
        assertNull(AudioTrackRejectionLog.labelForMime(format?.sampleMimeType))
    }

    @Test
    fun `no sink exception in the chain yields no format`() {
        val error = PlaybackException(
            "AudioTrack init failed",
            IllegalStateException("x"),
            PlaybackException.ERROR_CODE_UNSPECIFIED
        )
        assertNull(failedAudioTrackInputFormat(error))
    }
}
