package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.player.decodeStreamSubtitlesArg
import com.nuvio.tv.core.player.encodeStreamSubtitlesArg
import com.nuvio.tv.data.mapper.subtitleReleaseName
import com.nuvio.tv.domain.model.Subtitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Subtitles panel release line + "Matches your file" (Spec 26/26l, P19/X2). */
class SubtitleReleaseTest {

    @Test
    fun `release name prefers the addon field, else the file name without its extension`() {
        assertEquals("Tears.of.Steel.2012.1080p", subtitleReleaseName("Tears.of.Steel.2012.1080p", "x.srt"))
        assertEquals("Tears.of.Steel.2012.BRRip-RemixHD", subtitleReleaseName(" ", "Tears.of.Steel.2012.BRRip-RemixHD.srt"))
        assertEquals("Tears Of Steel (2012) 1080p.spa", subtitleReleaseName(null, "Tears Of Steel (2012) 1080p.spa.srt"))
        assertEquals("one line", subtitleReleaseName("one\n  line", null))
        assertEquals(160, subtitleReleaseName("a".repeat(400), null)?.length)
        assertEquals("b".repeat(160), subtitleReleaseName(null, "b".repeat(300) + ".srt"))
        assertEquals("Tears.of.Steel.2012.UHD-x", subtitleReleaseName(null, "Tears.of.Steel.2012.UHD-x.srt"))
        assertNull(subtitleReleaseName(null, null))
    }

    @Test
    fun `matches the playing file by release words or group`() {
        val file = "Tears.of.Steel.2012.2160p.UHD.BluRay.REMUX.HDR.HEVC.Atmos-FraMeSToR.mkv"
        assertTrue(subtitleMatchesFile("Tears.of.Steel.2012.UHD.BluRay-OTHER", "FraMeSToR", file))
        assertTrue(subtitleMatchesFile("Tears.of.Steel.2012.2160p.UHD.BluRay.REMUX.HDR.HEVC.Atmos-FraMeSToR", null, file))
        assertFalse(subtitleMatchesFile("Tears of Steel 2012 2160p UHD BluRay", null, file))
        assertFalse(subtitleMatchesFile("Tears.of.Steel.2012.1080p.WEB-DL-NTb", "NTb", file))
        assertFalse(subtitleMatchesFile("TOS", null, file))
        assertFalse(subtitleMatchesFile("Tears.of.Steel", "2012", file))
        assertFalse(subtitleMatchesFile("Tears.of.Steel.2012", "FraMeSToR", null))
        // Hyphenated groups are several words; a stream name without an extension keeps its last word.
        assertTrue(subtitleMatchesFile(null, "D-Z0N3", "Tears.of.Steel.2012.1080p.BluRay.DD5.1.x264-D-Z0N3.mkv"))
        assertFalse(subtitleMatchesFile("Tears.of.Steel.2012.2160p", null, "Tears of Steel 2012 2160p UHD"))
        assertTrue(subtitleMatchesFile("Tears.of.Steel.2012.2160p.UHD", null, "Tears of Steel 2012 2160p UHD"))
    }

    @Test
    fun `route codec carries the release fields`() {
        val subtitle = Subtitle(
            id = "1", url = "https://subs.example/1.srt", lang = "eng", addonName = "OpenSubtitles", addonLogo = null,
            releaseName = "Tears.of.Steel.2012.UHD-FraMeSToR", releaseGroup = "FraMeSToR"
        )
        val decoded = decodeStreamSubtitlesArg(encodeStreamSubtitlesArg(listOf(subtitle)), "OpenSubtitles", null).single()
        assertEquals(subtitle.releaseName, decoded.releaseName)
        assertEquals(subtitle.releaseGroup, decoded.releaseGroup)
    }
}
