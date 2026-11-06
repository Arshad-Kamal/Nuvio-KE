package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.Channel
import com.nuvio.tv.livetv.model.LiveStream
import com.nuvio.tv.livetv.parse.M3uParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelIdentityTest {

    @Test
    fun `tvg-id normalisation drops the feed and case`() {
        assertEquals("starjalsha.in", ChannelIdentity.normalizeTvgId(" StarJalsha.in@SD "))
        assertEquals("starjalsha.in", ChannelIdentity.normalizeTvgId("STARJALSHA.IN"))
        assertNull(ChannelIdentity.normalizeTvgId("  "))
        assertNull(ChannelIdentity.normalizeTvgId("@HD"))
        assertNull(ChannelIdentity.normalizeTvgId(null))
    }

    @Test
    fun `Pluto ids come from jmp2 uk redirector URLs only, lower case, deduplicated`() {
        val ch = Channel(
            key = "k", sourceId = "s", name = "n",
            streams = listOf(
                "https://jmp2.uk/plu-62BA60F059624E000781C436.m3u8",
                "http://jmp2.uk/plu-62ba60f059624e000781c436",
                "https://jmp2.uk/plu-5a6b92f6e22a617379789618?x=1",
                "https://jmp2.uk/stvp-5a6b92f6e22a617379789618.m3u8",
                "https://jmp2.uk/plu-62ba60f059624e000781c4361.m3u8",
                "https://evil.example/jmp2.uk/plu-62ba60f059624e000781c436.m3u8"
            ).map { LiveStream(it) }
        )
        assertEquals(listOf("62ba60f059624e000781c436", "5a6b92f6e22a617379789618"), ChannelIdentity.plutoIds(ch))
    }

    @Test
    fun `name normalisation follows the measured match rules`() {
        val rows = listOf(
            "Star Jalsha HD" to "starjalsha",
            "STAR JALSHA" to "starjalsha",
            "Aakaash Aath (576p)" to "aakaashaath",
            "R. Bangla" to "rbangla",
            "SONY SPORTS TEN 2 HD" to "sonysportsten2",
            "Travel XP [Geo-blocked] {x}" to "travelxp",
            "AT&T SportsNet" to "atandtsportsnet",
            "Colors+ 4K" to "colorsplus",
            "\uFF2E\uFF34\uFF36" to "ntv", // full-width NTV folds by NFKC
            "স্টার জলসা" to "স্টারজলসা" // Bengali letters and vowel signs kept, space removed
        )
        for ((raw, want) in rows) assertEquals(raw, want, ChannelIdentity.normalizeName(raw))
    }

    @Test
    fun `country comes from an iptv-org style tvg-id only`() {
        assertEquals("in", ChannelIdentity.countryOfTvgId("StarJalsha.in@SD"))
        assertEquals("uk", ChannelIdentity.countryOfTvgId("ATNBanglaUK.uk"))
        assertNull(ChannelIdentity.countryOfTvgId("542998"))
        assertNull(ChannelIdentity.countryOfTvgId("Foo.usa"))
        assertNull(ChannelIdentity.countryOfTvgId(null))
    }

    @Test
    fun `feeds of one tvg-id become one channel with ordered, de-duplicated streams`() {
        val p = M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="DWEnglish.de@SD" group-title="News",DW English (480p)
            https://example.org/dw-sd.m3u8
            #EXTINF:-1 tvg-id="DWEnglish.de@HD" tvg-name="DW",DW English (1080p)
            https://example.org/dw-hd.m3u8
            #EXTINF:-1 tvg-id="dwenglish.de",DW English again
            https://example.org/dw-sd.m3u8
            #EXTINF:-1 tvg-name="No id",No id
            https://example.org/noid.m3u8|Referer=https%3A%2F%2Fr.example%2F
            """.trimIndent()
        )
        val channels = ChannelIdentity.channelsFromM3u("src1", p)
        assertEquals(2, channels.size)
        val dw = channels[0]
        assertEquals("src1#id:dwenglish.de", dw.key)
        assertEquals("DW English (480p)", dw.name)
        assertEquals("de", dw.country)
        assertEquals(listOf("News"), dw.groups)
        assertTrue(dw.altNames.isEmpty()) // the first item names the channel; it had no tvg-name
        assertEquals(listOf("https://example.org/dw-sd.m3u8", "https://example.org/dw-hd.m3u8"), dw.streams.map { it.url })
        val noId = channels[1]
        assertEquals("src1#url:https://example.org/noid.m3u8", noId.key)
        assertNull(noId.country)
        assertTrue(noId.altNames.isEmpty()) // tvg-name equal to the title is not an extra name
        assertEquals(mapOf("Referer" to "https://r.example/"), noId.streams.single().headers)
    }

    @Test
    fun `keys are scoped by source and stable across parses`() {
        val p = M3uParser.parse("#EXTINF:-1 tvg-id=\"A.in\",A\nhttps://example.org/a.m3u8\n")
        val a1 = ChannelIdentity.channelsFromM3u("s1", p).single().key
        assertEquals(a1, ChannelIdentity.channelsFromM3u("s1", M3uParser.parse("#EXTINF:-1 tvg-id=\"A.in@HD\",A\nhttps://other.example/a.m3u8\n")).single().key)
        assertNotEquals(a1, ChannelIdentity.channelsFromM3u("s2", p).single().key)
    }

    @Test
    fun `tvg-name different from the title becomes an alt name`() {
        val c = ChannelIdentity.channelsFromM3u(
            "s", M3uParser.parse("#EXTINF:-1 tvg-id=\"A.in\" tvg-name=\"Star Jalsha\",SJ (1080p)\nhttps://example.org/a.m3u8\n")
        ).single()
        assertEquals(listOf("Star Jalsha"), c.altNames)
    }

    @Test
    fun `stremio keys keep colliding ids apart by name`() {
        assertNotEquals(
            ChannelIdentity.stremioKey("rtve", "tve:RNE.TV", "Radio Nacional"),
            ChannelIdentity.stremioKey("rtve", "tve:RNE.TV", "Radio 3")
        )
    }

    @Test
    fun `real iptv-org ben playlist gives one channel per tvg-id`() {
        val p = javaClass.getResourceAsStream("/livetv/iptv-org-ben-trimmed.m3u")!!
            .bufferedReader().use { M3uParser.parse(it) }
        val channels = ChannelIdentity.channelsFromM3u("iptv-org-ben", p)
        assertEquals(p.entries.mapNotNull { ChannelIdentity.normalizeTvgId(it.tvgId) }.toSet().size, channels.size)
        assertEquals("in", channels.first().country)
        assertEquals(channels.size, channels.map { it.key }.toSet().size)
    }
}
