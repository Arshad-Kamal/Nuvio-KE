package com.nuvio.tv.core.player
import android.media.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioCapabilityReportTest {

    @Test
    fun format_boxThenHdmiLinesWithPlugReportBeforeSurround() {
        assertEquals(
            "Box opens: AC3 EAC3 TrueHD\nBox cannot open: DTS DTS-HD\nHDMI device lists: AC3 EAC3 PCM16\n" +
                "HDMI plug report: plugged in · 8 ch · PCM16 AC3\nAndroid surround: MANUAL",
            AudioCapabilityReport.format(
                supported = listOf("AC3", "EAC3", "TrueHD"),
                absent = listOf("DTS", "DTS-HD"),
                negotiated = listOf("AC3", "EAC3", "PCM16"),
                surroundMode = "MANUAL",
                plugReport = "plugged in · 8 ch · PCM16 AC3"
            )
        )
    }

    @Test
    fun format_saysNoneRatherThanEmptyListAndOmitsAMissingPlugReport() {
        assertEquals(
            "Box opens: none\nBox cannot open: AC3\nHDMI device lists: unknown\nAndroid surround: NEVER",
            AudioCapabilityReport.format(emptyList(), listOf("AC3"), emptyList(), "NEVER", null)
        )
        assertEquals(
            "Box opens: AC3\nBox cannot open: none\nHDMI device lists: AC3\nAndroid surround: AUTO",
            AudioCapabilityReport.format(listOf("AC3"), emptyList(), listOf("AC3"), "AUTO", null)
        )
    }

    @Test
    fun listedNotOpened_isHdmiListsIntersectBoxCannotOpen() {
        assertEquals(
            "DTS-HD",
            AudioCapabilityReport.listedNotOpened(listOf("PCM16", "AC3", "TrueHD", "DTS", "DTS-HD"), listOf("DTS-HD"))
        )
        // HDMI-list order, not the probe order.
        assertEquals(
            "DTS DTS-HD",
            AudioCapabilityReport.listedNotOpened(listOf("PCM16", "DTS", "DTS-HD"), listOf("DTS-HD", "DTS"))
        )
        assertNull(AudioCapabilityReport.listedNotOpened(listOf("PCM16", "AC3"), listOf("DTS-HD")))
        assertNull(AudioCapabilityReport.listedNotOpened(emptyList(), listOf("DTS", "DTS-HD")))
    }

    @Test
    fun plugReportLabel_onlyWhenPluggedIn() {
        val encodings = intArrayOf(
            AudioFormat.ENCODING_PCM_16BIT,
            AudioFormat.ENCODING_AC3,
            AudioFormat.ENCODING_DTS_HD,
            AudioFormat.ENCODING_PCM_FLOAT // no label: dropped
        )
        assertEquals("plugged in · 8 ch · PCM16 AC3 DTS-HD", AudioCapabilityReport.plugReportLabel(1, 8, encodings))
        assertEquals("plugged in · PCM16 AC3 DTS-HD", AudioCapabilityReport.plugReportLabel(1, -1, encodings))
        assertEquals("plugged in · 2 ch", AudioCapabilityReport.plugReportLabel(1, 2, null))
        assertEquals("plugged in", AudioCapabilityReport.plugReportLabel(1, 0, intArrayOf()))
        assertEquals(
            "plugged in · AC3",
            AudioCapabilityReport.plugReportLabel(1, -1, intArrayOf(AudioFormat.ENCODING_AC3, AudioFormat.ENCODING_AC3))
        )
        assertNull(AudioCapabilityReport.plugReportLabel(0, 8, encodings))
        assertNull(AudioCapabilityReport.plugReportLabel(-1, 8, encodings))
    }

    @Test
    fun latest_isNullUntilCaptured() {
        AudioCapabilityReport.reset()
        assertNull(AudioCapabilityReport.latest)
    }
}
