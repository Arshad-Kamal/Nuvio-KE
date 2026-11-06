package com.nuvio.tv.core.player

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackFormatNamesTest {

    @Test
    fun formatNameFromMimeMapsKnownCodecs() {
        assertEquals("TrueHD", TrackFormatNames.formatNameFromMime(MimeTypes.AUDIO_TRUEHD))
        assertEquals("DTS-HD", TrackFormatNames.formatNameFromMime(MimeTypes.AUDIO_DTS_HD))
        assertEquals("HEVC", TrackFormatNames.formatNameFromMime(MimeTypes.VIDEO_H265))
        assertEquals("PGS", TrackFormatNames.formatNameFromMime("application/pgs"))
        assertNull(TrackFormatNames.formatNameFromMime("video/unknown"))
        assertNull(TrackFormatNames.formatNameFromMime(null))
    }

    @Test
    fun formatNameFallsBackToCodecs() {
        // Register A2 P9: extraction-parsed subtitles keep the original MIME in codecs.
        assertEquals("SSA", TrackFormatNames.formatName(MimeTypes.APPLICATION_MEDIA3_CUES, MimeTypes.TEXT_SSA))
        assertEquals("SRT", TrackFormatNames.formatName(MimeTypes.APPLICATION_MEDIA3_CUES, MimeTypes.APPLICATION_SUBRIP))
        assertEquals("HEVC", TrackFormatNames.formatName(MimeTypes.VIDEO_H265, MimeTypes.TEXT_SSA))
        assertNull(TrackFormatNames.formatName(MimeTypes.APPLICATION_MEDIA3_CUES, null))
        assertNull(TrackFormatNames.formatName(null, "hvc1.2.4.L153"))
    }

    @Test
    fun channelLayoutNames() {
        assertEquals("Mono", TrackFormatNames.getChannelLayoutName(1))
        assertEquals("Stereo", TrackFormatNames.getChannelLayoutName(2))
        assertEquals("5.1", TrackFormatNames.getChannelLayoutName(6))
        assertEquals("7.1", TrackFormatNames.getChannelLayoutName(8))
        assertEquals("3ch", TrackFormatNames.getChannelLayoutName(3))
        assertNull(TrackFormatNames.getChannelLayoutName(0))
    }
}
