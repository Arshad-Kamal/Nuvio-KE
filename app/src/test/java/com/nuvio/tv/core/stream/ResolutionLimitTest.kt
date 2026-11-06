package com.nuvio.tv.core.stream

import com.nuvio.tv.domain.model.ReleaseResolution.P1080
import com.nuvio.tv.domain.model.ReleaseResolution.P1440
import com.nuvio.tv.domain.model.ReleaseResolution.P2160
import com.nuvio.tv.domain.model.ReleaseResolution.P360
import com.nuvio.tv.domain.model.ReleaseResolution.P480
import com.nuvio.tv.domain.model.ReleaseResolution.P576
import com.nuvio.tv.domain.model.ReleaseResolution.P720
import com.nuvio.tv.domain.model.ReleaseResolution.UNKNOWN
import com.nuvio.tv.domain.model.AioParsedFile
import com.nuvio.tv.domain.model.AioStreamData
import com.nuvio.tv.domain.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 58 F3: the resolution limit's cap, the over-cap rule and the display mode ladder. */
class ResolutionLimitTest {

    @Test
    fun `this TV caps at its largest mode, and a 4K or unknown TV hides nothing`() {
        assertEquals(P1080, ResolutionLimit(ResolutionLimitMode.THIS_TV, tvMax = P1080).cap)
        assertEquals(P1440, ResolutionLimit(ResolutionLimitMode.THIS_TV, tvMax = P1440).cap)
        assertNull(ResolutionLimit(ResolutionLimitMode.THIS_TV, tvMax = P2160).cap)
        assertNull(ResolutionLimit(ResolutionLimitMode.THIS_TV, tvMax = null).cap)
        assertNull(ResolutionLimit(ResolutionLimitMode.THIS_TV, tvMax = UNKNOWN).cap)
    }

    @Test
    fun `fixed choices win over the TV, off hides nothing`() {
        assertEquals(P1080, ResolutionLimit(ResolutionLimitMode.UP_TO_1080P, tvMax = P2160).cap)
        assertEquals(P720, ResolutionLimit(ResolutionLimitMode.UP_TO_720P, tvMax = P1080).cap)
        assertEquals(P1080, ResolutionLimit(ResolutionLimitMode.UP_TO_1080P, tvMax = null).cap)
        assertNull(ResolutionLimit(ResolutionLimitMode.OFF, tvMax = P720).cap)
    }

    @Test
    fun `only a known resolution above the cap is over it`() {
        assertTrue(isOverResolutionCap(P2160, P1080))
        assertTrue(isOverResolutionCap(P1440, P1080))
        assertFalse(isOverResolutionCap(P1080, P1080))
        assertFalse(isOverResolutionCap(P720, P1080))
        assertFalse(isOverResolutionCap(UNKNOWN, P720))
        assertTrue(isOverResolutionCap(P1080, P720))
    }

    @Test
    fun `a stream's verdict names the cap and whether it is the TV's`() {
        val uhd = stream("Film.2021.2160p.WEB-DL.DV.HEVC")
        val fhd = stream("Film.2021.1080p.BluRay.x264")
        val bare = stream("Film 2021")
        val manual = ResolutionLimit(ResolutionLimitMode.UP_TO_1080P, tvMax = P2160)
        assertEquals(ResolutionVerdict(P1080, fromTv = false), resolutionLimitVerdict(uhd, manual))
        assertNull(resolutionLimitVerdict(fhd, manual))
        assertNull(resolutionLimitVerdict(bare, manual))
        val fhdTv = ResolutionLimit(ResolutionLimitMode.THIS_TV, tvMax = P1080)
        assertEquals(ResolutionVerdict(P1080, fromTv = true), resolutionLimitVerdict(uhd, fhdTv))
        assertNull(resolutionLimitVerdict(uhd, ResolutionLimit(ResolutionLimitMode.THIS_TV, tvMax = P2160)))
    }

    @Test
    fun `resolutionOf reads as the full facts do`() {
        for (text in listOf("Film.2160p.UHD", "Film 4K HDR", "Film.UHD.BluRay.1080p", "Film.720p", "Film", "Film DTS-HD 1080")) {
            val s = stream(text)
            assertEquals(text, ReleaseFactsParser.factsFor(s).resolution, ReleaseFactsParser.resolutionOf(s))
        }
    }

    @Test
    fun `resolutionOf takes the AIOStreams parse first, as the full facts do`() {
        val aio = stream("Film.2160p.REMUX").copy(
            streamData = AioStreamData(size = null, filename = null, cached = null, parsedFile = AioParsedFile(
                resolution = "1080p", quality = null, encode = null, releaseGroup = null, visualTags = emptyList(),
                audioTags = emptyList(), audioChannels = emptyList(), languages = emptyList(), editions = emptyList()
            ))
        )
        assertEquals(P1080, ReleaseFactsParser.resolutionOf(aio))
        assertEquals(ReleaseFactsParser.factsFor(aio).resolution, ReleaseFactsParser.resolutionOf(aio))
    }

    @Test
    fun `display modes map to the ladder, scope widths included`() {
        assertEquals(P2160, resolutionOfMode(3840, 2160))
        assertEquals(P2160, resolutionOfMode(3840, 1600))
        assertEquals(P2160, resolutionOfMode(4096, 2160))
        assertEquals(P1440, resolutionOfMode(2560, 1440))
        assertEquals(P1080, resolutionOfMode(1920, 1080))
        assertEquals(P720, resolutionOfMode(1280, 720))
        assertEquals(P576, resolutionOfMode(720, 576))
        assertEquals(P480, resolutionOfMode(720, 480))
        assertEquals(P360, resolutionOfMode(640, 360))
        assertEquals(P360, resolutionOfMode(320, 240))
        assertEquals(P720, resolutionOfMode(1366, 768))
        assertEquals(P1440, resolutionOfMode(2560, 1080))
        assertEquals(P1080, resolutionOfMode(1080, 1920)) // reported portrait
    }

    @Test
    fun `stored names read back, anything else is this TV`() {
        for (mode in ResolutionLimitMode.entries) assertEquals(mode, ResolutionLimitMode.fromStored(mode.name))
        assertEquals(ResolutionLimitMode.THIS_TV, ResolutionLimitMode.fromStored(null))
        assertEquals(ResolutionLimitMode.THIS_TV, ResolutionLimitMode.fromStored("UP_TO_4K"))
    }

    private fun stream(name: String) = Stream(
        name = name, title = null, description = null, url = "https://example.com/f.mkv", ytId = null, infoHash = null,
        fileIdx = null, externalUrl = null, behaviorHints = null, addonName = "Addon", addonLogo = null, streamData = null
    )
}
