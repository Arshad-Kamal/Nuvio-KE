package com.nuvio.tv.livetv.data

import com.nuvio.tv.core.di.NetworkModule
import com.nuvio.tv.livetv.model.Channel
import com.nuvio.tv.livetv.model.LiveStream
import com.nuvio.tv.livetv.ui.ChannelTags
import com.nuvio.tv.livetv.ui.LiveTvModel
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real iptv-org rows trimmed to 11 channels (2026-10-19): DW, France 24, NHK, DD, Star Jalsha, ATN, NSFW cases. */
class ChannelEnricherTest {

    private fun <T> fixture(name: String, s: KSerializer<T>): List<T> =
        NetworkModule.provideWireJson().decodeFromString(
            ListSerializer(s),
            javaClass.getResourceAsStream("/livetv/$name")!!.bufferedReader().readText()
        )

    private val meta = IptvMetadataBuilder.build(
        fixture("iptv-org-channels-trimmed.json", IptvChannelDto.serializer()).asSequence(),
        fixture("iptv-org-feeds-trimmed.json", IptvFeedDto.serializer()).asSequence(),
        fixture("iptv-org-blocklist-trimmed.json", IptvBlockDto.serializer()).asSequence(),
        fetchedAtMs = 1L
    )

    private fun ch(tvgId: String?, name: String, groups: List<String> = emptyList(), country: String? = null) = Channel(
        key = "s#${tvgId ?: name}",
        sourceId = "s",
        name = name,
        tvgId = tvgId,
        country = country ?: ChannelIdentity.countryOfTvgId(tvgId),
        groups = groups,
        streams = listOf(LiveStream("https://example.org/${name.hashCode()}.m3u8"))
    )

    @Test
    fun `builder keys by normalised id and keeps only differing feeds`() {
        assertEquals(11, meta.channels.size)
        val dw = meta.channels.getValue("dw.de")
        assertEquals(listOf("deu"), dw.languages) // main feed: Deutsch
        assertEquals(listOf("eng"), dw.feedLanguages["english"])
        assertNull(dw.feedLanguages["deutsch"])
        // No is_main feed: the union of every feed's languages.
        val noMain = IptvMetadataBuilder.build(
            sequenceOf(IptvChannelDto("X.ca", "X")),
            sequenceOf(IptvFeedDto("X.ca", "A", false, listOf("eng")), IptvFeedDto("X.ca", "B", null, listOf("fra", "eng"))),
            emptySequence(),
            1
        )
        assertEquals(listOf("eng", "fra"), noMain.channels.getValue("x.ca").languages)
        val stub = IptvMetadataBuilder.build(emptySequence(), emptySequence(), sequenceOf(IptvBlockDto("Gone.us", "nsfw")), 1)
        assertTrue(stub.channels.getValue("gone.us").nsfw)
    }

    @Test
    fun `enrich fills country, names, categories and the feed's languages`() {
        val dw = ChannelEnricher.enrich(ch("DW.de@English", "DW English"), meta)
        assertEquals("de", dw.country)
        assertEquals(listOf("DW", "Deutsche Welle"), dw.altNames)
        assertEquals(listOf("news"), dw.categories)
        assertEquals(listOf("eng"), dw.languages)

        assertEquals(listOf("deu"), ChannelEnricher.enrich(ch("DW.de@Deutsch", "DW"), meta).languages)

        // No country in the tvg-id form: iptv-org's fills it; a source country wins.
        assertEquals("in", ChannelEnricher.enrich(ch("ddbangla.in", "DD Bangla", country = null), meta).country)
        assertEquals("ca", ChannelEnricher.enrich(ch("DDBangla.in", "DD Bangla", country = "ca"), meta).country)

        val unknown = ChannelEnricher.enrich(ch("Nope.xx", "Local News"), meta)
        assertTrue(unknown.categories.isEmpty())
        assertFalse(unknown.nsfw)
    }

    @Test
    fun `nsfw comes from is_nsfw, the blocklist, or the keyword guard for unknown channels`() {
        assertTrue(ChannelEnricher.enrich(ch("21Sextury.us", "21 Sextury"), meta).nsfw)
        assertTrue(ChannelEnricher.enrich(ch("DancingBear.us", "Dancing Bear"), meta).nsfw) // blocklist reason nsfw
        assertFalse(ChannelEnricher.enrich(ch("ABCSpark.ca", "ABC Spark"), meta).nsfw) // blocklist reason dmca
        assertTrue(ChannelEnricher.enrich(ch(null, "Night Show", groups = listOf("XXX")), meta).nsfw)
        assertTrue(ChannelEnricher.enrich(ch(null, "Hot 18+ TV"), meta).nsfw)
        assertTrue(ChannelEnricher.enrich(ch(null, "Night", groups = listOf("Adult")), meta).nsfw)
        assertTrue(ChannelEnricher.enrich(ch(null, "Night", groups = listOf("Adults only")), meta).nsfw)
        assertTrue(ChannelEnricher.enrich(ch(null, "Erotik Kanal"), meta).nsfw)
        assertFalse(ChannelEnricher.enrich(ch(null, "Adult Swim"), meta).nsfw)
        assertFalse(ChannelEnricher.enrich(ch(null, "Essex Radio TV"), meta).nsfw)
        assertFalse(ChannelEnricher.enrich(ch(null, "Sports 18"), meta).nsfw)
    }

    @Test
    fun `listable enriched channels carry the Live TV picker values`() {
        val raw = listOf(
            ch("DW.de@English", "DW English"),
            ch("DDSports.in@SD", "DD Sports"),
            ch("DDBangla.in@SD", "DD Bangla"),
            ch("ATNDDSports.ca@SD", "ATN DD Sports"),
            ch(null, "Local Cricket", groups = listOf("Sports")),
            ch(null, "Kolkata Live", groups = listOf("Bengali")),
            ch("21Sextury.us", "21 Sextury"),
            ch("StarJalsha.in", "Star Jalsha").copy(lostAtMs = 5)
        )
        val listable = ChannelEnricher.listable(ChannelEnricher.enrich(raw, meta))
        assertEquals(
            listOf("DW English", "DD Sports", "DD Bangla", "ATN DD Sports", "Local Cricket", "Kolkata Live"),
            listable.map { it.name }
        )
        fun named(test: (ChannelTags) -> Boolean) = listable.filter { test(LiveTvModel.tags(it)) }.map { it.name }
        // Sports: iptv-org's category, else the playlist group (Local Cricket).
        assertEquals(listOf("DD Sports", "ATN DD Sports", "Local Cricket"), named { "sports" in it.categories })
        assertEquals(listOf("DW English", "ATN DD Sports"), named { "eng" in it.languages })
        // Languages come from iptv-org only: Kolkata Live's "Bengali" group is its category.
        assertEquals(listOf("DD Bangla"), named { "ben" in it.languages })
        assertEquals(listOf("Kolkata Live"), named { "bengali" in it.categories })
        assertEquals(listOf("ATN DD Sports"), named { it.country == "ca" })
    }

    @Test
    fun `enriched fields never reach the channel cache`() {
        val enriched = ChannelEnricher.enrich(ch("DW.de@English", "DW English"), meta)
        val json = com.nuvio.tv.data.local.localStoreJson
        val text = json.encodeToString(Channel.serializer(), enriched)
        assertFalse(text.contains("categories") || text.contains("languages") || text.contains("nsfw"))
        val back = json.decodeFromString(Channel.serializer(), text)
        assertTrue(back.categories.isEmpty() && back.languages.isEmpty() && !back.nsfw)
    }

    @Test
    fun `without metadata only the keyword guard applies`() {
        val plain = ChannelEnricher.enrich(ch("DW.de@English", "DW English"), null)
        assertNull(plain.languages.firstOrNull())
        assertFalse(plain.nsfw)
    }
}
