package com.nuvio.tv.ui.components.marquee

import com.nuvio.tv.core.stream.ReleaseFacts
import com.nuvio.tv.domain.model.ReleaseAudioChannel
import com.nuvio.tv.domain.model.ReleaseAudioTag
import com.nuvio.tv.domain.model.ReleaseEncode
import com.nuvio.tv.domain.model.ReleaseQuality
import com.nuvio.tv.domain.model.ReleaseResolution
import com.nuvio.tv.domain.model.ReleaseVisualTag
import com.nuvio.tv.domain.model.StreamBadge
import com.nuvio.tv.ui.components.formatStreamSizeLabel
import org.junit.Assert.assertEquals
import org.junit.Test

/** Shared source badges (Detail source line, Stream rows): priority order, labels, cap, fusion. */
class MarqueeSourceBadgesTest {

    private fun facts(
        resolution: ReleaseResolution = ReleaseResolution.UNKNOWN,
        quality: ReleaseQuality = ReleaseQuality.UNKNOWN,
        visual: List<ReleaseVisualTag> = listOf(ReleaseVisualTag.UNKNOWN),
        audio: List<ReleaseAudioTag> = listOf(ReleaseAudioTag.UNKNOWN),
        channels: List<ReleaseAudioChannel> = listOf(ReleaseAudioChannel.UNKNOWN),
        size: Long? = null,
        editions: List<String> = emptyList()
    ) = ReleaseFacts(
        resolution = resolution,
        quality = quality,
        visualTags = visual,
        audioTags = audio,
        audioChannels = channels,
        encode = ReleaseEncode.UNKNOWN,
        releaseGroup = "",
        size = size,
        resolutionRank = 0,
        qualityRank = 0,
        visualRank = 0,
        audioRank = 0,
        channelRank = 0,
        encodeRank = 0,
        groupRank = 0,
        editions = editions
    )

    private val size: (Long) -> String = { formatStreamSizeLabel(it, "%1\$s GB", "%1\$s MB") }

    private fun labels(items: List<SourceBadgeItem>): List<String> = items.map {
        when (it) {
            is SourceBadgeItem.Tech -> it.label
            is SourceBadgeItem.Image -> "image:${it.badge.name}"
        }
    }

    @Test
    fun `4k remux reads in priority order with one combined audio badge`() {
        val labels = sourceTechBadgeLabels(
            facts(
                resolution = ReleaseResolution.P2160,
                quality = ReleaseQuality.BLURAY_REMUX,
                visual = listOf(ReleaseVisualTag.DV_ONLY, ReleaseVisualTag.DV),
                audio = listOf(ReleaseAudioTag.ATMOS, ReleaseAudioTag.TRUEHD),
                channels = listOf(ReleaseAudioChannel.CH_7_1),
                size = 62_491_774_976L // 58.2 GiB
            ),
            size
        )
        assertEquals(listOf("4K", "DOLBY VISION", "TRUEHD ATMOS 7.1", "REMUX", "58.2 GB"), labels)
    }

    @Test
    fun `dual layer dolby vision names its hdr10 base`() {
        val dvHdr10 = listOf(ReleaseVisualTag.HDR_DV, ReleaseVisualTag.HDR10, ReleaseVisualTag.DV, ReleaseVisualTag.HDR)
        assertEquals(listOf("DV · HDR10"), sourceTechBadgeLabels(facts(visual = dvHdr10), null))
        // The parser adds HDR10 with HDR10+, so the + layer must win over HDR10.
        val dvHdr10Plus = listOf(
            ReleaseVisualTag.HDR_DV, ReleaseVisualTag.HDR10_PLUS, ReleaseVisualTag.HDR10, ReleaseVisualTag.DV, ReleaseVisualTag.HDR
        )
        assertEquals(listOf("DV · HDR10+"), sourceTechBadgeLabels(facts(visual = dvHdr10Plus), null))
        // A bare "DV HDR" release names no HDR10 layer: plain Dolby Vision.
        val dvHdr = listOf(ReleaseVisualTag.HDR_DV, ReleaseVisualTag.DV, ReleaseVisualTag.HDR)
        assertEquals(listOf("DOLBY VISION"), sourceTechBadgeLabels(facts(visual = dvHdr), null))
    }

    @Test
    fun `lossless codec wins over the core track the parser also saw`() {
        val labels = sourceTechBadgeLabels(
            facts(audio = listOf(ReleaseAudioTag.DTS_HD_MA, ReleaseAudioTag.DTS_HD, ReleaseAudioTag.DTS)),
            size
        )
        assertEquals(listOf("DTS-HD MA"), labels)
    }

    @Test
    fun `hdr10 plus without dolby vision and web source`() {
        val labels = sourceTechBadgeLabels(
            facts(
                resolution = ReleaseResolution.P1080,
                quality = ReleaseQuality.WEB_DL,
                visual = listOf(ReleaseVisualTag.HDR_ONLY, ReleaseVisualTag.HDR10_PLUS, ReleaseVisualTag.HDR10, ReleaseVisualTag.HDR),
                audio = listOf(ReleaseAudioTag.DD_PLUS),
                channels = listOf(ReleaseAudioChannel.CH_5_1)
            ),
            size
        )
        assertEquals(listOf("1080P", "HDR10+", "DD+ 5.1", "WEB-DL"), labels)
    }

    @Test
    fun `editions then imax follow the source, size last and only when asked`() {
        val f = facts(
            resolution = ReleaseResolution.P2160,
            quality = ReleaseQuality.BLURAY_REMUX,
            visual = listOf(ReleaseVisualTag.IMAX),
            size = 700L * 1024 * 1024,
            editions = listOf("EXTENDED")
        )
        assertEquals(listOf("4K", "REMUX", "EXTENDED", "IMAX", "700 MB"), sourceTechBadgeLabels(f, size))
        assertEquals(listOf("4K", "REMUX", "EXTENDED", "IMAX"), sourceTechBadgeLabels(f, null))
    }

    @Test
    fun `badges cap at five and shed the tail`() {
        val f = facts(
            resolution = ReleaseResolution.P2160,
            quality = ReleaseQuality.BLURAY_REMUX,
            visual = listOf(
                ReleaseVisualTag.HDR_DV, ReleaseVisualTag.HDR10, ReleaseVisualTag.DV, ReleaseVisualTag.HDR, ReleaseVisualTag.IMAX
            ),
            audio = listOf(ReleaseAudioTag.TRUEHD),
            channels = listOf(ReleaseAudioChannel.CH_7_1),
            size = 62_491_774_976L,
            editions = listOf("EXTENDED")
        )
        assertEquals(
            listOf("4K", "DV · HDR10", "TRUEHD 7.1", "REMUX", "EXTENDED"),
            labels(sourceBadgeItems(f, emptyList(), size))
        )
    }

    @Test
    fun `fusion badges replace parsed ones, text badges become tech badges, blanks skipped`() {
        val f = facts(resolution = ReleaseResolution.P2160, quality = ReleaseQuality.BLURAY_REMUX)
        val fusion = listOf(
            StreamBadge(name = "Remux", imageURL = "https://badges.example.net/remux.png"),
            StreamBadge(name = "4K"),
            StreamBadge(name = " "),
            StreamBadge(name = "DV"),
            StreamBadge(name = "Atmos"),
            StreamBadge(name = "7.1"),
            StreamBadge(name = "Cached")
        )
        assertEquals(
            listOf("image:Remux", "4K", "DV", "Atmos", "7.1"),
            labels(sourceBadgeItems(f, fusion, size))
        )
    }

    @Test
    fun `fusion list of only blank badges falls back to parsed labels`() {
        val f = facts(resolution = ReleaseResolution.P2160, quality = ReleaseQuality.BLURAY_REMUX)
        assertEquals(listOf("4K", "REMUX"), labels(sourceBadgeItems(f, listOf(StreamBadge(name = " ")), size)))
    }

    @Test
    fun `unknown facts and zero size render no badges`() {
        assertEquals(emptyList<SourceBadgeItem>(), sourceBadgeItems(facts(size = 0L), emptyList(), size))
    }
}
