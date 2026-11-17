package com.nuvio.tv.livetv.data

import com.nuvio.tv.data.local.localStoreJson
import com.nuvio.tv.livetv.model.Channel
import com.nuvio.tv.livetv.model.LiveStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Spec 115 I7: the streaming read and write keep the file format and the failure contract. */
class JsonFileTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(name: String = "channels.json") = JsonFile(File(tmp.root, name), ChannelCache.serializer())

    // Past the 64 KiB buffer, with 2-, 3- and 4-byte UTF-8 (an emoji is a surrogate pair) for boundaries to split.
    private val big = ChannelCache(
        channels = (1..2_000).map { i ->
            Channel(
                key = "s#id:$i",
                sourceId = "s",
                name = "Ćhannel $i · 東京 · ✓ · 📺",
                groups = listOf("Новости"),
                streams = listOf(LiveStream("https://example.org/$i.m3u8", headers = mapOf("Referer" to "https://ex.org/")))
            )
        }
    )

    @Test
    fun `a written value reads back unchanged, leaving no temp file`() {
        val f = file()
        f.write(big)
        assertEquals(big, f.read())
        assertFalse(File(tmp.root, "channels.json.tmp").exists())
    }

    @Test
    fun `a second write replaces the first`() {
        val f = file()
        f.write(big)
        val small = ChannelCache(channels = big.channels.take(1))
        f.write(small)
        assertEquals(small, f.read())
    }

    @Test
    fun `missing, truncated and empty files read as null`() {
        assertNull(file("absent.json").read())
        val f = file()
        f.write(big)
        val raw = File(tmp.root, "channels.json")
        raw.writeBytes(raw.readBytes().copyOf(raw.length().toInt() / 2))
        assertNull(f.read())
        raw.writeText("")
        assertNull(f.read())
        raw.writeText(localStoreJson.encodeToString(ChannelCache.serializer(), big) + "garbage")
        assertNull(f.read())
    }

    @Test
    fun `a cache written by the old String writer reads back (upgrade path)`() {
        File(tmp.root, "channels.json").writeText(localStoreJson.encodeToString(ChannelCache.serializer(), big), Charsets.UTF_8)
        assertEquals(big, file().read())
    }

    @Test
    fun `the file is plain UTF-8 JSON, as before`() {
        val f = file()
        f.write(big)
        val text = File(tmp.root, "channels.json").readText(Charsets.UTF_8)
        assertEquals(big, localStoreJson.decodeFromString(ChannelCache.serializer(), text))
    }
}
