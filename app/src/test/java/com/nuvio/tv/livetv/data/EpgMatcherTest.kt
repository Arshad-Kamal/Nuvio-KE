package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.Channel
import com.nuvio.tv.livetv.model.EpgOverride
import com.nuvio.tv.livetv.model.EpgRef
import com.nuvio.tv.livetv.model.LiveStream
import com.nuvio.tv.livetv.parse.EpgChannel
import com.nuvio.tv.livetv.parse.EpgProgramme
import com.nuvio.tv.livetv.parse.EpgSnapshot
import com.nuvio.tv.livetv.parse.XmltvNowNextParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.time.Instant

class EpgMatcherTest {

    private fun channel(
        key: String,
        name: String,
        tvgId: String? = null,
        altNames: List<String> = emptyList(),
        country: String? = ChannelIdentity.countryOfTvgId(tvgId)
    ) =
        Channel(
            key = key,
            sourceId = "s",
            name = name,
            tvgId = tvgId,
            country = country,
            altNames = altNames,
            streams = listOf(LiveStream("https://example.org/$key.m3u8"))
        )

    private fun source(id: String, country: String?, vararg chans: Pair<String, List<String>>, withProgrammes: Set<String> = emptySet()) =
        EpgSource(
            id,
            country,
            EpgSnapshot(
                channels = chans.associate { (cid, names) -> cid to EpgChannel(cid, names, null) },
                programmes = withProgrammes.associateWith { listOf(EpgProgramme(0, 1, "p")) },
                windowStartMs = 0,
                windowEndMs = 1,
                complete = true
            )
        )

    // free-epg.de style: id doubles as a name, several ids per channel.
    private val freeEpgIn = source(
        "free-epg-in", "in",
        "StarJalsha.in" to listOf("Star Jalsha"),
        "NTV.in" to listOf("NTV")
    )

    // epg.pw style: numeric ids, upper-case names.
    private val epgPwIn = source(
        "epgpw-in", "in",
        "542998" to listOf("STAR JALSHA"),
        "404001" to listOf("ZEE BANGLA HD")
    )

    @Test
    fun `normalised tvg-id matches before names, in any country`() {
        // The name alone would pick epg.pw's 542998; the tvg-id step must win first.
        val ch = channel("k1", "Star Jalsha", tvgId = "StarJalsha.in@SD")
        val m = EpgMatcher.match(listOf(ch), listOf(epgPwIn, freeEpgIn), emptyMap())
        assertEquals(EpgRef("free-epg-in", "StarJalsha.in"), m["k1"])
        // A tvg-id hit is not country-gated: an EPG file of another (or no) country still matches.
        val foreign = source("other", null, "StarJalsha.in" to listOf("x"))
        assertEquals(EpgRef("other", "StarJalsha.in"), EpgMatcher.match(listOf(ch), listOf(foreign), emptyMap())["k1"])
    }

    @Test
    fun `a stale tvg-id hit does not shadow a live name hit in another source`() {
        // free-epg.de first: its id hit has no programmes; epg.pw's name hit does.
        val stale = source("free-epg-in", "in", "StarJalsha.in" to listOf("Star Jalsha"))
        val live = source("epgpw-in", "in", "542998" to listOf("STAR JALSHA"), withProgrammes = setOf("542998"))
        val ch = channel("j", "Star Jalsha", tvgId = "StarJalsha.in@SD")
        assertEquals(EpgRef("epgpw-in", "542998"), EpgMatcher.match(listOf(ch), listOf(stale, live), emptyMap())["j"])
        // With no live hit anywhere, the first hit in chain order still gives a (stale) guide.
        val liveless = source("epgpw-in", "in", "542998" to listOf("STAR JALSHA"))
        assertEquals(EpgRef("free-epg-in", "StarJalsha.in"), EpgMatcher.match(listOf(ch), listOf(stale, liveless), emptyMap())["j"])
    }

    @Test
    fun `country codes compare case-insensitively on both sides`() {
        val upperSource = source("epgpw-in", "IN", "542998" to listOf("STAR JALSHA"))
        val upperChannel = channel("j", "Star Jalsha", country = "IN")
        assertEquals(EpgRef("epgpw-in", "542998"), EpgMatcher.match(listOf(upperChannel), listOf(upperSource), emptyMap())["j"])
    }

    @Test
    fun `title wins over alt names, a name normalising to empty is skipped`() {
        val ch = channel("t", "Zee Bangla", tvgId = "X.in", altNames = listOf("Star Jalsha"))
        assertEquals(EpgRef("epgpw-in", "404001"), EpgMatcher.match(listOf(ch), listOf(epgPwIn), emptyMap())["t"])
        val empty = channel("e", "(HD) [x]", tvgId = "Y.in", altNames = listOf("Star Jalsha"))
        assertEquals(EpgRef("epgpw-in", "542998"), EpgMatcher.match(listOf(empty), listOf(epgPwIn), emptyMap())["e"])
    }

    @Test
    fun `Bengali display names match through the index`() {
        val bn = source("bn", "in", "1" to listOf("স্টার জলসা"))
        val ch = channel("b", "স্টার জলসা HD", tvgId = "Z.in")
        assertEquals(EpgRef("bn", "1"), EpgMatcher.match(listOf(ch), listOf(bn), emptyMap())["b"])
    }

    @Test
    fun `name match is accepted only in the channel's own country`() {
        val jalsha = channel("j", "Star Jalsha HD", tvgId = "StarJalshaHD.in@HD")
        val ntvBd = channel("n", "NTV", tvgId = "NTV.bd@SD")
        val m = EpgMatcher.match(listOf(jalsha, ntvBd), listOf(epgPwIn, freeEpgIn), emptyMap())
        assertEquals(EpgRef("epgpw-in", "542998"), m["j"])
        assertNull(m["n"]) // NTV.bd must not get India's NTV
    }

    @Test
    fun `a channel without a country never name-matches`() {
        val m = EpgMatcher.match(listOf(channel("x", "Star Jalsha")), listOf(epgPwIn), emptyMap())
        assertNull(m["x"])
    }

    @Test
    fun `alt names are tried after the title`() {
        val ch = channel("z", "Zee Bangla Cinema?", tvgId = "ZeeBanglaX.in", altNames = listOf("Zee Bangla"))
        assertEquals(EpgRef("epgpw-in", "404001"), EpgMatcher.match(listOf(ch), listOf(epgPwIn), emptyMap())["z"])
    }

    @Test
    fun `overrides win, and a null override means no guide`() {
        val a = channel("a", "Star Jalsha", tvgId = "StarJalsha.in")
        val b = channel("b", "Zee Bangla", tvgId = "ZeeBangla.in")
        val overrides = mapOf(
            "a" to EpgOverride(EpgRef("epgpw-in", "404001")),
            "b" to EpgOverride(null)
        )
        val m = EpgMatcher.match(listOf(a, b), listOf(epgPwIn, freeEpgIn), overrides)
        assertEquals(EpgRef("epgpw-in", "404001"), m["a"])
        assertNull(m["b"])
    }

    @Test
    fun `among ids sharing a name the first with programmes wins`() {
        val dup = source(
            "dup", "in",
            "StarJalsha.in" to listOf("Star Jalsha"),
            "Star Jalsha HD.in" to listOf("Star Jalsha HD"),
            withProgrammes = setOf("Star Jalsha HD.in")
        )
        val ch = channel("j", "Star Jalsha", tvgId = "Jalsha.in")
        assertEquals(EpgRef("dup", "Star Jalsha HD.in"), EpgMatcher.match(listOf(ch), listOf(dup), emptyMap())["j"])
        val none = source("dup", "in", "StarJalsha.in" to listOf("Star Jalsha"), "Star Jalsha HD.in" to listOf("Star Jalsha HD"))
        assertEquals(EpgRef("dup", "StarJalsha.in"), EpgMatcher.match(listOf(ch), listOf(none), emptyMap())["j"])
    }

    @Test
    fun `a Pluto stream id matches the Pluto guide's channel id before tvg-id and name, in any country`() {
        // i.mjh.nz keys Pluto channels by the id in the jmp2.uk redirector URL; real ids from the
        // Worldwide 2026 playlist (CBS News 24/7 has several feeds; the first listed in the guide wins).
        val pluto = source(
            "pluto", null,
            "6350fdd266e9ea0007bedec5" to listOf("CBS News 24/7"),
            "5a6b92f6e22a617379789618" to listOf("CBS News"),
            withProgrammes = setOf("5a6b92f6e22a617379789618")
        )
        val ch = channel("cbs", "CBS News 24/7 (720p)", tvgId = "CBSNews247.us@SD").copy(
            streams = listOf(
                LiveStream("https://example.org/cbs.m3u8"),
                LiveStream("https://jmp2.uk/plu-6350FDD266E9EA0007BEDEC5.m3u8"),
                LiveStream("https://jmp2.uk/plu-5a6b92f6e22a617379789618.m3u8")
            )
        )
        // The live pass prefers the stream whose guide id has programmes.
        assertEquals(EpgRef("pluto", "5a6b92f6e22a617379789618"), EpgMatcher.match(listOf(ch), listOf(pluto), emptyMap())["cbs"])
        // Without programmes anywhere, the first Pluto stream's id wins, ahead of a tvg-id hit elsewhere.
        val plain = source("pluto", null, "6350fdd266e9ea0007bedec5" to listOf("CBS News 24/7"))
        val byTvgId = source("other", "us", "CBSNews247.us" to listOf("CBS News 24/7"))
        assertEquals(EpgRef("pluto", "6350fdd266e9ea0007bedec5"), EpgMatcher.match(listOf(ch), listOf(byTvgId, plain), emptyMap())["cbs"])
    }

    @Test
    fun `a stale Pluto hit does not shadow a live tvg-id hit, and a live Pluto hit beats a live tvg-id hit`() {
        val ch = channel("c", "CBS News 24/7", tvgId = "CBSNews247.us@SD").copy(
            streams = listOf(LiveStream("https://jmp2.uk/plu-6350fdd266e9ea0007bedec5.m3u8"))
        )
        val stalePluto = source("pluto", null, "6350fdd266e9ea0007bedec5" to listOf("CBS News 24/7"))
        val liveById = source("other", "us", "CBSNews247.us" to listOf("CBS News 24/7"), withProgrammes = setOf("CBSNews247.us"))
        assertEquals(EpgRef("other", "CBSNews247.us"), EpgMatcher.match(listOf(ch), listOf(stalePluto, liveById), emptyMap())["c"])
        val livePluto = source("pluto", null, "6350fdd266e9ea0007bedec5" to listOf("CBS News 24/7"), withProgrammes = setOf("6350fdd266e9ea0007bedec5"))
        assertEquals(EpgRef("pluto", "6350fdd266e9ea0007bedec5"), EpgMatcher.match(listOf(ch), listOf(liveById, livePluto), emptyMap())["c"])
    }

    @Test
    fun `a non-Pluto URL holding a Pluto-like id does not match`() {
        val pluto = source("pluto", null, "6350fdd266e9ea0007bedec5" to listOf("CBS News 24/7"))
        val ch = channel("x", "Something").copy(
            streams = listOf(LiveStream("https://example.org/plu-6350fdd266e9ea0007bedec5.m3u8"))
        )
        assertNull(EpgMatcher.match(listOf(ch), listOf(pluto), emptyMap())["x"])
    }

    @Test
    fun `real epg pw fixture matches an iptv-org named channel by country-gated name`() {
        val at = Instant.parse("2026-10-19T02:15:00Z").toEpochMilli()
        val snapshot = javaClass.getResourceAsStream("/livetv/epg-pw-in-trimmed.xml.gz")!!
            .use { XmltvNowNextParser(newParser = { KXmlParser() }).parse(it, at) }
        val ch = channel("sj", "Star Jalsha (1080p)", tvgId = "StarJalsha.in@SD")
        val ref = EpgMatcher.match(listOf(ch), listOf(EpgSource("epgpw-in", "in", snapshot)), emptyMap())["sj"]
        assertEquals(EpgRef("epgpw-in", "542998"), ref)
        assertEquals("Bigg Boss Bangla", snapshot.nowNext(ref!!.epgChannelId, at).first?.title)
    }
}
