package com.nuvio.tv.livetv.data

import com.nuvio.tv.data.local.localStoreJson
import com.nuvio.tv.livetv.model.Channel
import com.nuvio.tv.livetv.model.LiveStream
import com.nuvio.tv.livetv.parse.EpgChannel
import com.nuvio.tv.livetv.parse.EpgProgramme
import com.nuvio.tv.livetv.parse.EpgSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Spec 143 P1: equal values shared, nothing else changed (same values, same JSON). */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveCompactTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A new String instance equal to [s], as a JSON decode makes one. */
    private fun fresh(s: String): String = String(s.toCharArray())

    private fun ch(id: String, headers: Map<String, String> = LinkedHashMap(), attrs: Map<String, String>? = null) = Channel(
        key = "src#id:$id",
        sourceId = fresh("src"),
        name = id,
        country = fresh("uk"),
        groups = listOf(fresh("News"), fresh("UK")),
        altNames = ArrayList(),
        streams = listOf(LiveStream("https://example.org/$id.m3u8", headers = headers, kodiProps = null, tags = null)),
        attrs = attrs
    )

    private val channels = listOf(
        ch("a"),
        ch("b", headers = linkedMapOf(fresh("Referer") to fresh("https://example.org/"))),
        ch("c", attrs = linkedMapOf(fresh("tvg-shift") to fresh("1"), fresh("catchup") to fresh("default"))),
        ch("d").let { c ->
            c.copy(streams = listOf(c.streams[0].copy(
                drm = true,
                kodiProps = linkedMapOf(fresh("inputstream.adaptive.license_type") to fresh("clearkey")),
                tags = linkedMapOf(fresh("EXTVLCOPT") to fresh("http-referrer=https://example.org/"))
            )))
        }
    )

    @Test
    fun `channels keep their values and JSON and share equal strings and empty collections`() {
        val out = LiveCompact.channels(channels)
        assertEquals(channels, out)
        val json = { l: List<Channel> -> localStoreJson.encodeToString(ChannelCache.serializer(), ChannelCache(channels = l)) }
        assertEquals(json(channels), json(out))

        assertNotSame(channels[0].sourceId, channels[1].sourceId)
        assertSame(out[0].sourceId, out[1].sourceId)
        assertSame(out[0].sourceId, out[2].sourceId)
        assertSame(out[0].country, out[2].country)
        assertSame(out[0].groups[0], out[1].groups[0])
        assertSame(emptyList<String>(), out[0].altNames)
        assertSame(emptyMap<String, String>(), out[0].streams[0].headers)
        assertEquals(listOf("tvg-shift", "catchup"), out[2].attrs!!.keys.toList())
        assertEquals(channels[3].streams[0].kodiProps, out[3].streams[0].kodiProps)
        assertEquals(channels[3].streams[0].tags, out[3].streams[0].tags)
        assertSame(out[0].sourceId, out[3].sourceId)
    }

    @Test
    fun `an already compact channel is kept, not copied`() {
        val once = LiveCompact.channels(channels)
        val twice = LiveCompact.channels(once)
        // Every value is already shared within the list: nothing is copied, the list itself is kept.
        assertSame(once, twice)
        once.indices.forEach { assertSame(once[it], twice[it]) }
    }

    @Test
    fun `metadata keeps its values, key order and JSON and shares equal strings`() {
        val meta = IptvMetadata(
            fetchedAtMs = 5L,
            channels = linkedMapOf(
                fresh("z.uk") to ChannelMeta("Z", country = fresh("uk"), categories = listOf(fresh("news")), languages = listOf(fresh("eng")), feedLanguages = LinkedHashMap()),
                fresh("a.uk") to ChannelMeta("A", country = fresh("uk"), categories = listOf(fresh("news")), languages = listOf(fresh("eng")),
                    feedLanguages = linkedMapOf(fresh("hd") to listOf(fresh("eng"), fresh("wel"))))
            )
        )
        val out = LiveCompact.metadata(meta)
        assertEquals(meta, out)
        assertEquals(listOf("z.uk", "a.uk"), out.channels.keys.toList())
        val json = { m: IptvMetadata -> localStoreJson.encodeToString(IptvMetadata.serializer(), m) }
        assertEquals(json(meta), json(out))

        val z = out.channels.getValue("z.uk")
        val a = out.channels.getValue("a.uk")
        assertSame(z.country, a.country)
        assertSame(z.categories[0], a.categories[0])
        assertSame(z.languages[0], a.feedLanguages.getValue("hd")[0])
        assertSame(emptyMap<String, List<String>>(), z.feedLanguages)
    }

    private fun snapshot(): EpgSnapshot {
        fun p(start: Long, title: String) = EpgProgramme(start, start + 10, fresh(title), category = fresh("News"))
        return EpgSnapshot(
            channels = linkedMapOf(
                fresh("bbc1") to EpgChannel(fresh("bbc1"), listOf(fresh("BBC One")), null),
                fresh("bbc2") to EpgChannel(fresh("bbc2"), listOf(fresh("BBC Two")), fresh("https://example.org/b2.png"))
            ),
            programmes = linkedMapOf(
                fresh("bbc1") to listOf(p(0, "News"), p(10, "Weather")),
                fresh("bbc2") to listOf(p(0, "News"))
            ),
            windowStartMs = 0,
            windowEndMs = 100,
            complete = true
        )
    }

    @Test
    fun `a guide keeps its values and JSON and shares titles and ids`() {
        val s = snapshot()
        val out = LiveCompact.guide(s)
        assertEquals(s, out)
        val json = { g: EpgSnapshot -> localStoreJson.encodeToString(GuideCache.serializer(), GuideCache(snapshot = g)) }
        assertEquals(json(s), json(out))

        val b1 = out.programmes.getValue("bbc1")
        val b2 = out.programmes.getValue("bbc2")
        assertSame(b1[0].title, b2[0].title)
        assertSame(b1[0].title, b1[0].category)
        assertSame(out.channels.keys.first(), out.channels.getValue("bbc1").id)
        assertSame(out.channels.keys.first(), out.programmes.keys.first())
    }

    @Test
    fun `the channel cache is shared when read back and when a source is replaced`() = runTest {
        val io = UnconfinedTestDispatcher()
        ChannelRepository(tmp.root, { 0L }, io).replaceSource("src", channels)
        val cold = ChannelRepository(tmp.root, { 0L }, io)
        cold.load()
        val read = cold.channels.value
        assertEquals(channels, read)
        assertSame(read[0].sourceId, read[1].sourceId)
        assertSame(read[0].groups[1], read[2].groups[1])

        val warm = ChannelRepository(tmp.newFolder(), { 0L }, io)
        warm.replaceSource("src", channels)
        assertSame(warm.channels.value[0].country, warm.channels.value[1].country)
    }

    @Test
    fun `guides are shared when read back and when put`() = runTest {
        val io = UnconfinedTestDispatcher()
        val repo = GuideRepository(tmp.root, io)
        repo.put("g", snapshot())
        val put = repo.guides.value.getValue("g")
        assertSame(put.programmes.getValue("bbc1")[0].title, put.programmes.getValue("bbc2")[0].title)

        val cold = GuideRepository(tmp.root, io)
        cold.load(listOf("g"))
        val read = cold.guides.value.getValue("g")
        assertEquals(snapshot(), read)
        assertSame(read.programmes.getValue("bbc1")[0].title, read.programmes.getValue("bbc2")[0].title)
        assertTrue(read.complete)
    }
}
