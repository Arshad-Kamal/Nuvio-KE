package com.nuvio.tv.core.player

import android.media.AudioTrack
import android.util.Log
import androidx.media3.exoplayer.audio.DefaultAudioSink
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * L2-60: the HUD reads the AudioTrack on main while the playback thread releases it.
 * A read that throws on a released track must skip that tick only, never latch the
 * reflection-failure flag that disables the Underruns/Route rows for the session.
 */
class PlaybackSpeedAwareAudioSinkTrackReadTest {

    private lateinit var track: AudioTrack
    private lateinit var sink: PlaybackSpeedAwareAudioSink

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0

        track = mockk()
        val defaultSink = mockk<DefaultAudioSink>(relaxed = true)
        DefaultAudioSink::class.java.getDeclaredField("audioTrack")
            .apply { isAccessible = true }
            .set(defaultSink, track)
        sink = PlaybackSpeedAwareAudioSink(defaultSink)
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `underrun read on a released track skips one tick and keeps reading`() {
        every { track.underrunCount } throws IllegalStateException("released")
        assertNull(sink.nativeAudioTrackUnderrunCount())

        every { track.underrunCount } returns 7
        assertEquals(7, sink.nativeAudioTrackUnderrunCount())
    }

    @Test
    fun `route read on a released track skips one tick and keeps reading`() {
        every { track.routedDevice } throws IllegalStateException("released")
        assertNull(sink.sampleAudioRoute())

        every { track.routedDevice } returns null
        assertEquals(
            PlaybackSpeedAwareAudioSink.AudioRouteSnapshot("none", 0),
            sink.sampleAudioRoute()
        )
    }
}
