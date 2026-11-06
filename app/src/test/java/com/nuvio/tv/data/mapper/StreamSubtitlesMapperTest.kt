package com.nuvio.tv.data.mapper

import com.nuvio.tv.data.remote.dto.StreamDto
import com.nuvio.tv.data.remote.dto.SubtitleDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Stremio `stream.subtitles` mapping: untrusted urls are kept only
 * when http(s), lang/id fall back like SubtitleRepositoryImpl, and entries are
 * labelled with the stream's addon.
 */
class StreamSubtitlesMapperTest {

    @Test
    fun `keeps only http and https urls`() {
        val mapped = listOf(
            SubtitleDto(id = "a", url = "https://subs.example/a.srt", lang = "eng"),
            SubtitleDto(id = "b", url = "HTTP://subs.example/b.vtt", lang = "fre"),
            SubtitleDto(id = "c", url = "file:///sdcard/c.srt", lang = "eng"),
            SubtitleDto(id = "d", url = "content://evil/d.srt", lang = "eng"),
            SubtitleDto(id = "e", url = "javascript:alert(1)", lang = "eng"),
            SubtitleDto(id = "f", url = "ftp://subs.example/f.srt", lang = "eng"),
            SubtitleDto(id = "g", url = "https://", lang = "eng"),
            SubtitleDto(id = "h", url = null, lang = "eng"),
            SubtitleDto(id = "i", url = "   ", lang = "eng")
        ).toStreamSubtitles(addonName = "Torrentio", addonLogo = null)

        assertEquals(listOf("a", "b"), mapped.map { it.id })
    }

    @Test
    fun `trims the url and labels entries with the stream addon`() {
        val subtitle = listOf(SubtitleDto(id = "a", url = "  https://subs.example/a.srt ", lang = "eng"))
            .toStreamSubtitles(addonName = "Torrentio", addonLogo = "https://logo.example/t.png")
            .single()

        assertEquals("https://subs.example/a.srt", subtitle.url)
        assertEquals("eng", subtitle.lang)
        assertEquals("Torrentio", subtitle.addonName)
        assertEquals("https://logo.example/t.png", subtitle.addonLogo)
    }

    @Test
    fun `missing lang falls back to und and missing id derives from lang and url`() {
        val url = "https://subs.example/x.srt"
        val subtitle = listOf(SubtitleDto(id = " ", url = url, lang = null))
            .toStreamSubtitles(addonName = "Addon", addonLogo = null)
            .single()

        assertEquals("und", subtitle.lang)
        assertEquals("und-${url.hashCode()}", subtitle.id)
    }

    @Test
    fun `null list maps to empty and entries are capped`() {
        assertTrue((null as List<SubtitleDto>?).toStreamSubtitles("Addon", null).isEmpty())

        val many = (0 until 250).map { SubtitleDto(id = "$it", url = "https://subs.example/$it.srt", lang = "eng") }
        assertEquals(100, many.toStreamSubtitles("Addon", null).size)
    }

    @Test
    fun `stream toDomain carries mapped subtitles`() {
        val stream = StreamDto(
            url = "https://cdn.example/movie.mkv",
            subtitles = listOf(
                SubtitleDto(id = "a", url = "https://subs.example/a.srt", lang = "eng"),
                SubtitleDto(id = "b", url = "file:///b.srt", lang = "eng")
            )
        ).toDomain(addonName = "Torrentio", addonLogo = null)

        assertEquals(listOf("a"), stream.subtitles.map { it.id })
        assertEquals("Torrentio", stream.subtitles.single().addonName)
        assertTrue(StreamDto(url = "https://cdn.example/m.mkv").toDomain("A", null).subtitles.isEmpty())
    }
}
