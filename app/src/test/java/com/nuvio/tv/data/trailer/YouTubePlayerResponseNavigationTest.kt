package com.nuvio.tv.data.trailer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the lenient element-tree navigation the in-app YouTube extractor uses
 * on opaque player responses (Gson-cast parity): string content for any
 * primitive, unquoted-only booleans, numeric strings coerced to numbers.
 */
class YouTubePlayerResponseNavigationTest {

    private val json = Json

    private fun parse(body: String) = json.parseToJsonElement(body).jsonObject

    @Test
    fun `string value reads string and number primitives as content`() {
        val obj = parse("""{"url":"https://h","itag":137,"qualityLabel":"1080p"}""")

        assertEquals("https://h", obj.stringValue("url"))
        assertEquals("137", obj.stringValue("itag"))
        assertEquals("1080p", obj.stringValue("qualityLabel"))
        assertNull(obj.stringValue("missing"))
        // JSON null is absent, never the literal string "null".
        assertNull(parse("{\"hlsManifestUrl\":null}").stringValue("hlsManifestUrl"))
        assertNull(parse("{\"url\":null}").stringValue("url"))
    }

    @Test
    fun `number value coerces numeric strings and rejects quoted non-numbers`() {
        val obj = parse("""{"fps":30,"bitrate":"2500000","label":"1080p60"}""")

        assertEquals(30.0, obj.numberValue("fps"))
        assertEquals(2500000.0, obj.numberValue("bitrate"))
        assertNull(obj.numberValue("label"))
        assertNull(obj.numberValue("missing"))
    }

    @Test
    fun `boolean value accepts unquoted only`() {
        val obj = parse("""{"ok":true,"flagged":"false"}""")

        assertEquals(true, obj.booleanValue("ok"))
        assertNull(obj.booleanValue("flagged"))
    }

    @Test
    fun `map and list navigation skip non-matching shapes`() {
        val obj = parse(
            """{
                "streamingData":{
                    "hlsManifestUrl":"https://m3u8",
                    "adaptiveFormats":[{"itag":137,"url":"https://v"}, "junk", {"itag":140}]
                },
                "playabilityStatus":"not-a-map"
            }"""
        )

        assertEquals("https://m3u8", obj.mapValue("streamingData")?.stringValue("hlsManifestUrl"))
        assertNull(obj.mapValue("playabilityStatus"))
        val formats = obj.mapValue("streamingData")?.listMapValue("adaptiveFormats").orEmpty()
        assertEquals(2, formats.size)
        assertEquals("137", formats[0].stringValue("itag"))
    }

    @Test
    fun `player payload shape is pinned`() {
        val android = CLIENTS.first { it.key == "android" }

        val encoded = buildPlayerPayload(videoId = "dQw4w9WgXcQ", client = android).toString()

        assertEquals(true, encoded.contains("\"videoId\":\"dQw4w9WgXcQ\""))
        assertEquals(true, encoded.contains("\"contentCheckOk\":true"))
        assertEquals(true, encoded.contains("\"clientName\":\"ANDROID\""))
        assertEquals(true, encoded.contains("\"androidSdkVersion\":34")) // unquoted number
        assertEquals(true, encoded.contains("\"html5Preference\":\"HTML5_PREF_WANTS\""))
    }
}
