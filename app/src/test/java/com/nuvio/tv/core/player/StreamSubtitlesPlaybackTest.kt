package com.nuvio.tv.core.player

import androidx.lifecycle.SavedStateHandle
import com.nuvio.tv.domain.model.Subtitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins how a stream's own subtitles reach the player: the route codec and nav
 * args that carry them from the stream screen, and the merge into the addon-subtitle
 * list the picker shows.
 */
class StreamSubtitlesPlaybackTest {

    private fun sub(id: String, url: String, lang: String = "eng", addon: String = "Addon") =
        Subtitle(id = id, url = url, lang = lang, addonName = addon, addonLogo = null)

    @Test
    fun `stream subtitles lead and duplicates by url are dropped`() {
        val stream = listOf(
            sub("s1", "https://a.example/1.srt", addon = "Torrentio"),
            sub("s1-dup", "https://a.example/1.srt", addon = "Torrentio")
        )
        val addon = listOf(
            sub("o1", "https://os.example/9.srt", addon = "OpenSubtitles"),
            sub("o2", "https://a.example/1.srt", addon = "OpenSubtitles")
        )

        val merged = mergeStreamSubtitles(stream, addon)

        assertEquals(listOf("s1", "o1"), merged.map { it.id })
        assertEquals("Torrentio", merged.first().addonName)
    }

    @Test
    fun `no stream subtitles leaves the addon list untouched`() {
        val addon = listOf(
            sub("o1", "https://os.example/1.srt"),
            sub("o1-mirror", "https://os.example/1.srt")
        )

        assertSame(addon, mergeStreamSubtitles(emptyList(), addon))
    }

    @Test
    fun `stream subtitles show even when no subtitle addon answered`() {
        val stream = listOf(sub("s1", "https://a.example/1.srt"))

        assertEquals(stream, mergeStreamSubtitles(stream, emptyList()))
    }

    @Test
    fun `nav args read stream subtitles with percent escapes intact`() {
        val subtitles = listOf(
            sub("s1", "https://a.example/sub%20one.srt?token=a%2Fb", lang = "eng", addon = "Torrentio"),
            sub("s2", "https://a.example/2.vtt", lang = "fre", addon = "Torrentio")
        )
        // Navigation has already decoded the route once, so the handle holds the
        // raw codec JSON; PlayerNavigationArgs must not decode the urls again.
        val handle = SavedStateHandle(
            mapOf(
                "subtitles" to encodeStreamSubtitlesArg(subtitles),
                "addonName" to "Torrentio"
            )
        )

        assertEquals(subtitles, PlayerNavigationArgs.from(handle).streamSubtitles)
    }

    @Test
    fun `nav args keep plus signs and percent escapes in text, header and url args`() {
        // Navigation has already decoded each arg once; a second decode would turn
        // '+' into a space and %2F into '/'. No arg carries a bare '%', which would make
        // a second decode throw and fall back to the raw value, hiding the bug.
        val headers = """{"Authorization":"Bearer a+b/c=="}"""
        val sources = """["https://cdn.example/x%2Fy.mkv","tracker:udp://t.example:80"]"""
        val handle = SavedStateHandle(
            mapOf(
                "title" to "Disney+ Hotstar",
                "headers" to headers,
                "poster" to "https://img.example/a%20b.jpg",
                "sources" to sources,
                "year" to ""
            )
        )

        val args = PlayerNavigationArgs.from(handle)

        assertEquals("Disney+ Hotstar", args.title)
        assertEquals(headers, args.headersJson)
        assertEquals("https://img.example/a%20b.jpg", args.poster)
        assertEquals(sources, args.sourcesJson)
        assertEquals(null, args.year)
    }

    @Test
    fun `route decode tolerates garbage and re-filters non-http urls`() {
        assertTrue(decodeStreamSubtitlesArg(null, "A", null).isEmpty())
        assertTrue(decodeStreamSubtitlesArg("not json", "A", null).isEmpty())
        assertTrue(decodeStreamSubtitlesArg("{\"id\":\"x\"}", "A", null).isEmpty())

        val decoded = decodeStreamSubtitlesArg(
            json = """[{"id":"a","url":"file:///x.srt","lang":"eng"},{"id":"b","url":"https://a.example/b.srt","lang":"eng"}]""",
            addonName = "A",
            addonLogo = null
        )
        assertEquals(listOf("b"), decoded.map { it.id })
    }
}
