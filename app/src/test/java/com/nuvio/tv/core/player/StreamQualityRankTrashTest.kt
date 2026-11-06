package com.nuvio.tv.core.player

import com.nuvio.tv.core.stream.ReleaseFactsParser
import com.nuvio.tv.domain.model.AioParsedFile
import com.nuvio.tv.domain.model.AioStreamData
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamQualityRankTrashTest {

    @Test
    fun `preferred group ladder orders remux tiers`() {
        val tier3 = stream("Movie.2023.2160p.UHD.BluRay.REMUX.TrueHD.7.1-NTb.mkv")
        val tier2 = stream("Movie.2023.2160p.UHD.BluRay.REMUX.TrueHD.7.1-playBD.mkv")
        val tier1 = stream("Movie.2023.2160p.UHD.BluRay.REMUX.TrueHD.7.1-FraMeSToR.mkv")
        val untiered = stream("Movie.2023.2160p.UHD.BluRay.REMUX.TrueHD.7.1-RANDOMGRP.mkv")

        val ranked = StreamQualityRank.rank(listOf(untiered, tier3, tier2, tier1))

        assertEquals(listOf(tier1, tier2, tier3, untiered), ranked)
    }

    @Test
    fun `excluded groups are dropped even at higher resolution`() {
        val junk4k = stream("Movie.2023.2160p.BluRay.x265-YIFY.mkv")
        val goodWeb = stream("Movie.2023.1080p.WEB-DL.DDP5.1-FLUX.mkv")

        val ranked = StreamQualityRank.rank(listOf(junk4k, goodWeb))

        assertEquals(listOf(goodWeb), ranked)
    }

    @Test
    fun `all-excluded pool falls back to unfiltered ranking`() {
        val better = stream("Movie.2023.1080p.WEBRip.x264-YIFY.mkv")
        val worse = stream("Movie.2023.720p.HDTV.x264-MeGusta.mkv")

        val ranked = StreamQualityRank.rank(listOf(worse, better))

        assertEquals(2, ranked.size)
        assertEquals(better, ranked.first())
    }

    @Test
    fun `mkv outranks mp4 on otherwise equal streams`() {
        val mp4 = stream(name = "Movie 2160p WEB-DL DDP5.1", url = "https://example.com/a.mp4")
        val mkv = stream(name = "Movie 2160p WEB-DL DDP5.1", url = "https://example.com/a.mkv")

        val ranked = StreamQualityRank.rank(listOf(mp4, mkv))

        assertEquals(mkv, ranked.first())
    }

    @Test
    fun `a formatter age of 3d does not drop the best stream from the pool (B9)`() {
        val agedRemux = stream(
            filename = "Movie.2010.2160p.UHD.BluRay.REMUX.DV.HDR10.HEVC.TrueHD.7.1-FraMeSToR.mkv",
            description = "Movie (2010)\nHEVC DV · HDR10\n58.3 GB · 54.6 Mbps · 3d"
        )
        val web1080 = stream(filename = "Movie.2010.1080p.WEB-DL.DDP5.1.H.264-FLUX.mkv")

        val ranked = StreamQualityRank.rank(listOf(web1080, agedRemux))

        assertEquals(listOf(agedRemux, web1080), ranked)
    }

    @Test
    fun `an AIOStreams stream ranks on its parsed facts, not formatter text that reads as 3D`() {
        // Formatter text alone would read "3D" (a default exclusion) and a 1080p WEB source.
        val aioRemux = stream(name = "AIOStreams 3D", description = "Movie 1080p WEB").copy(
            streamData = AioStreamData(
                size = null,
                filename = null,
                cached = true,
                parsedFile = AioParsedFile(
                    resolution = "2160p",
                    quality = "BluRay REMUX",
                    encode = "HEVC",
                    releaseGroup = "FraMeSToR",
                    visualTags = listOf("DV", "HDR10"),
                    audioTags = listOf("TrueHD", "Atmos"),
                    audioChannels = listOf("7.1"),
                    languages = listOf("English"),
                    editions = emptyList()
                )
            )
        )
        val web1080 = stream(filename = "Movie.2010.1080p.WEB-DL.DDP5.1.H.264-FLUX.mkv")

        val ranked = StreamQualityRank.rank(listOf(web1080, aioRemux))

        assertEquals(listOf(aioRemux, web1080), ranked)
    }

    @Test
    fun `a 1080p encode of a UHD source ranks in the 1080p tier (B10)`() {
        val uhdSource1080 = stream(filename = "Movie.2010.UHD.BluRay.1080p.DDP5.1.DV.x265-GRP.mkv")
        val real2160 = stream(filename = "Movie.2010.2160p.WEB-DL.DDP5.1.DV.H.265-GRP.mkv")

        val ranked = StreamQualityRank.rank(listOf(uhdSource1080, real2160))

        assertEquals(listOf(real2160, uhdSource1080), ranked)
    }

    @Test
    fun `release group extraction handles compound names and mid-name hyphens`() {
        assertEquals(
            "FLUX",
            ReleaseFactsParser.releaseGroupFromText("Movie.2023.2160p.WEB-DL.DDP5.1.Atmos-FLUX.mkv")
        )
        assertEquals(
            "D-Z0N3",
            ReleaseFactsParser.releaseGroupFromText("Movie.2010.1080p.BluRay.DTS.x264-D-Z0N3.mkv")
        )
        assertEquals(
            "YIFY",
            ReleaseFactsParser.releaseGroupFromText("Movie.2023.1080p.BluRay.x264-YIFY.mkv")
        )
        assertEquals(
            "",
            ReleaseFactsParser.releaseGroupFromText("Movie.2008.2160p.WEB-DL")
        )
    }

    private fun stream(
        filename: String? = null,
        name: String? = null,
        url: String? = "https://example.com/stream",
        description: String? = null
    ): Stream = Stream(
        name = name ?: filename,
        title = null,
        description = description,
        url = url,
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null,
            bingeGroup = null,
            countryWhitelist = null,
            proxyHeaders = null,
            filename = filename
        ),
        addonName = "TestAddon",
        addonLogo = null
    )
}
