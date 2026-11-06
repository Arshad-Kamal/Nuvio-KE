package com.nuvio.tv.livetv.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {

    // Shape of an iptv-org playlist: tvg-id with an @feed suffix, no tvg-name, EXTVLCOPT headers.
    private val iptvOrgStyle = """
        #EXTM3U
        #EXTINF:-1 tvg-id="DWEnglish.de@SD" tvg-logo="https://i.imgur.com/A1xzjOI.png" group-title="News",DW English (1080p)
        https://dwamdstream102.akamaized.net/hls/live/2015525/dwstream102/index.m3u8
        #EXTINF:-1 tvg-id="StarJalsha.in@SD" tvg-logo="https://example.org/jalsha.png" group-title="Entertainment;General",Star Jalsha
        #EXTVLCOPT:http-referrer=https://example.org/
        #EXTVLCOPT:http-user-agent=Mozilla/5.0 (Linux; Android 11)
        http://example.org/live/jalsha/index.m3u8
    """.trimIndent()

    @Test
    fun `iptv-org style entries keep id, logo, groups, title and headers`() {
        val p = M3uParser.parse(iptvOrgStyle)
        assertEquals(2, p.entries.size)
        val dw = p.entries[0]
        assertEquals("DW English (1080p)", dw.title)
        assertEquals("DWEnglish.de@SD", dw.tvgId)
        assertNull(dw.tvgName)
        assertEquals(listOf("News"), dw.groups)
        assertEquals("https://dwamdstream102.akamaized.net/hls/live/2015525/dwstream102/index.m3u8", dw.url)
        assertTrue(dw.headers.isEmpty())
        val jalsha = p.entries[1]
        assertEquals(listOf("Entertainment", "General"), jalsha.groups)
        assertEquals(
            mapOf("Referer" to "https://example.org/", "User-Agent" to "Mozilla/5.0 (Linux; Android 11)"),
            jalsha.headers
        )
    }

    @Test
    fun `header EPG urls from x-tvg-url and url-tvg, comma lists split, duplicates dropped`() {
        val p = M3uParser.parse(
            "#EXTM3U x-tvg-url=\"https://a.example/epg.xml.gz,https://b.example/epg.xml\" url-tvg=\"https://a.example/epg.xml.gz\"\n"
        )
        assertEquals(listOf("https://a.example/epg.xml.gz", "https://b.example/epg.xml"), p.epgUrls)
    }

    @Test
    fun `BOM, CRLF, unquoted values, missing header and comma in a quoted value`() {
        val text = "\uFEFF#EXTINF:-1 tvg-id=Kolkata.in tvg-chno=105 group-title=\"News, Bengali\",Kolkata TV\r\n" +
            "https://example.org/kolkata.m3u8\r\n"
        val e = M3uParser.parse(text).entries.single()
        assertEquals("Kolkata TV", e.title)
        assertEquals("Kolkata.in", e.tvgId)
        assertEquals("105", e.channelNumber)
        assertEquals(listOf("News, Bengali"), e.groups)
        assertEquals("https://example.org/kolkata.m3u8", e.url)
    }

    @Test
    fun `title falls back to tvg-name, then the url`() {
        val p = M3uParser.parse(
            "#EXTM3U\n#EXTINF:-1 tvg-name=\"France 24\",\nhttps://example.org/f24.m3u8\nhttps://example.org/bare.m3u8\n"
        )
        assertEquals("France 24", p.entries[0].title)
        assertEquals("https://example.org/bare.m3u8", p.entries[1].title)
    }

    @Test
    fun `EXTGRP is used when group-title is absent`() {
        val e = M3uParser.parse("#EXTINF:-1,NHK World\n#EXTGRP:Japan\nhttps://example.org/nhk.m3u8\n").entries.single()
        assertEquals(listOf("Japan"), e.groups)
    }

    @Test
    fun `pipe suffix headers are split off the url and decoded`() {
        val e = M3uParser.parse(
            "#EXTINF:-1,Ch\nhttps://example.org/a.m3u8|User-Agent=VLC%2F3.0&referer=https%3A%2F%2Fsite.example%2F\n"
        ).entries.single()
        assertEquals("https://example.org/a.m3u8", e.url)
        assertEquals(mapOf("User-Agent" to "VLC/3.0", "Referer" to "https://site.example/"), e.headers)
    }

    @Test
    fun `KODIPROP binds to the next item, marks DRM and adds stream headers`() {
        val p = M3uParser.parse(
            """
            #EXTM3U
            #KODIPROP:inputstream.adaptive.license_type=com.widevine.alpha
            #KODIPROP:inputstream.adaptive.stream_headers=Origin=https%3A%2F%2Fo.example
            #EXTINF:-1,Locked
            https://example.org/locked.mpd
            #EXTINF:-1,Open
            https://example.org/open.m3u8
            """.trimIndent()
        )
        assertTrue(p.entries[0].drm)
        assertEquals(mapOf("Origin" to "https://o.example"), p.entries[0].headers)
        assertFalse(p.entries[1].drm)
        assertTrue(p.entries[1].headers.isEmpty())
    }

    @Test
    fun `unknown directives and attributes are ignored, placeholders kept`() {
        val e = M3uParser.parse(
            "#EXTM3U\n#EXT-X-SOMETHING:1\n#EXTINF:-1 catchup=\"default\" foo=bar,Ch\n#PLAYLIST:x\nhttps://example.org/live.m3u8?device=[DEVICE_ID]\n"
        ).entries.single()
        assertEquals("https://example.org/live.m3u8?device=[DEVICE_ID]", e.url)
        assertEquals("Ch", e.title)
    }

    @Test
    fun `entry cap stops parsing`() {
        val text = buildString {
            repeat(10) { append("#EXTINF:-1,C$it\nhttps://example.org/$it.m3u8\n") }
        }
        assertEquals(3, M3uParser.parse(text, maxEntries = 3).entries.size)
    }

    @Test
    fun `Kodi 21 drm keys, license_key and manifest_headers`() {
        val p = M3uParser.parse(
            """
            #EXTINF:-1,Legacy
            #KODIPROP:inputstream.adaptive.drm_legacy=org.w3.clearkey|https://lic.example
            https://example.org/a.mpd
            #EXTINF:-1,Json
            #KODIPROP:inputstream.adaptive.drm={"com.widevine.alpha":{}}
            https://example.org/b.mpd
            #EXTINF:-1,Key
            #KODIPROP:inputstream.adaptive.license_key=https://lic.example
            #KODIPROP:inputstream.adaptive.manifest_headers=Referer=https%3A%2F%2Fr.example%2F
            https://example.org/c.mpd
            """.trimIndent()
        )
        assertEquals(listOf(true, true, true), p.entries.map { it.drm })
        assertEquals(mapOf("Referer" to "https://r.example/"), p.entries[2].headers)
    }

    @Test
    fun `an EXTINF without a URL does not leak its directives into the next item`() {
        val p = M3uParser.parse(
            """
            #EXTINF:-1 group-title="Lost",Orphan
            #EXTVLCOPT:http-referer=https://orphan.example/
            #KODIPROP:inputstream.adaptive.license_type=com.widevine.alpha
            #EXTGRP:OrphanGroup
            #EXTINF:-1,Real
            https://example.org/real.m3u8
            """.trimIndent()
        )
        val e = p.entries.single()
        assertEquals("Real", e.title)
        assertTrue(e.headers.isEmpty())
        assertFalse(e.drm)
        assertTrue(e.groups.isEmpty())
    }

    @Test
    fun `EXTVLCOPT before EXTINF binds to that item`() {
        val e = M3uParser.parse(
            "#EXTVLCOPT:http-user-agent=UA/1\n#EXTVLCOPT:http-referer=https://r.example/\n#EXTINF:-1,Ch\nhttps://example.org/ch.m3u8\n"
        ).entries.single()
        assertEquals(mapOf("User-Agent" to "UA/1", "Referer" to "https://r.example/"), e.headers)
    }

    @Test
    fun `header EPG urls are capped`() {
        val urls = (1..8).joinToString(",") { "https://e$it.example/epg.xml" }
        assertEquals(M3uParser.MAX_EPG_URLS, M3uParser.parse("#EXTM3U x-tvg-url=\"$urls\"\n").epgUrls.size)
    }

    @Test
    fun `real iptv-org ben playlist, trimmed`() {
        val p = javaClass.getResourceAsStream("/livetv/iptv-org-ben-trimmed.m3u")!!
            .bufferedReader().use { M3uParser.parse(it) }
        assertEquals(12, p.entries.size)
        assertTrue(p.epgUrls.isEmpty()) // iptv-org playlists carry no x-tvg-url (Spec 34 D5)
        val first = p.entries.first()
        assertEquals("Aakaash Aath (576p)", first.title)
        assertEquals("AakaashAath.in@SD", first.tvgId)
        assertEquals(listOf("Entertainment"), first.groups)
        assertEquals("http://tvsen5.aynascope.net/Wm9Lv2RjZGT6/index.m3u8", first.url)
        assertTrue(p.entries.all { it.tvgName == null && it.url.startsWith("http") && !it.drm })
    }

    @Test
    fun `garbage input yields no crash`() {
        val p = M3uParser.parse("#EXTINF\n#EXTINF:-1 tvg-id=\"unterminated,Title\n=\n")
        assertEquals(1, p.entries.size)
        assertEquals("=", p.entries.single().url)
    }
}
