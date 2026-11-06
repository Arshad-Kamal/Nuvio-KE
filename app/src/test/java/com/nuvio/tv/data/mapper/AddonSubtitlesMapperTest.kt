package com.nuvio.tv.data.mapper

import com.nuvio.tv.data.remote.dto.SubtitleItemDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the subtitles-resource mapping (L8-26/52/10): the same untrusted-url rule as
 * stream.subtitles (http(s) only), sanitized headers, the `language` fallback, and a cap.
 */
class AddonSubtitlesMapperTest {

    @Test
    fun `keeps only http and https urls`() {
        val mapped = listOf(
            SubtitleItemDto(id = "a", url = "https://subs.example/a.srt", lang = "eng"),
            SubtitleItemDto(id = "b", url = "file:///sdcard/b.srt", lang = "eng"),
            SubtitleItemDto(id = "c", url = "content://evil/c.srt", lang = "eng"),
            SubtitleItemDto(id = "d", url = "data:text/plain,x", lang = "eng"),
            SubtitleItemDto(id = "e", url = null, lang = "eng")
        ).toAddonSubtitles(addonName = "OpenSubtitles", addonLogo = null)

        assertEquals(listOf("a"), mapped.map { it.id })
        assertEquals("OpenSubtitles", mapped.single().addonName)
    }

    @Test
    fun `language is the fallback when lang is blank`() {
        val mapped = listOf(
            SubtitleItemDto(id = "a", url = "https://subs.example/a.srt", lang = " ", language = "fre"),
            SubtitleItemDto(id = "b", url = "https://subs.example/b.srt", lang = "eng", language = "fre"),
            SubtitleItemDto(id = "c", url = "https://subs.example/c.srt")
        ).toAddonSubtitles(addonName = "Addon", addonLogo = null)

        assertEquals(listOf("fre", "eng", "und"), mapped.map { it.lang })
    }

    @Test
    fun `headers are sanitized`() {
        val subtitle = listOf(
            SubtitleItemDto(
                id = "a",
                url = "https://subs.example/a.srt",
                lang = "eng",
                headers = mapOf("User-Agent" to "ua", "X-Bad" to "line\r\nbreak", "Range" to "bytes=0-")
            )
        ).toAddonSubtitles(addonName = "Addon", addonLogo = null).single()

        assertEquals(mapOf("User-Agent" to "ua"), subtitle.headers)
        assertNull(
            listOf(SubtitleItemDto(id = "b", url = "https://subs.example/b.srt", headers = emptyMap()))
                .toAddonSubtitles("Addon", null).single().headers
        )
    }

    @Test
    fun `null list maps to empty and entries are capped`() {
        assertTrue((null as List<SubtitleItemDto>?).toAddonSubtitles("Addon", null).isEmpty())

        val many = (0 until 600).map { SubtitleItemDto(id = "$it", url = "https://subs.example/$it.srt") }
        assertEquals(500, many.toAddonSubtitles("Addon", null).size)
    }
}
