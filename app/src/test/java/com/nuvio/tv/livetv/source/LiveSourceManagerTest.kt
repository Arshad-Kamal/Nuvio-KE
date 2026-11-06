package com.nuvio.tv.livetv.source

import com.nuvio.tv.livetv.data.ChannelRepository
import com.nuvio.tv.livetv.data.GuideRepository
import com.nuvio.tv.livetv.model.EpgOverride
import com.nuvio.tv.livetv.model.EpgRef
import com.nuvio.tv.livetv.parse.EpgSnapshot
import com.nuvio.tv.livetv.parse.XmltvNowNextParser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.kxml2.io.KXmlParser
import okio.Buffer
import java.io.File
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class LiveSourceManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private var now = 1_000_000L
    private var ids = 0
    private val io = UnconfinedTestDispatcher()
    private lateinit var store: SourceStore
    private lateinit var channels: ChannelRepository
    private lateinit var guides: GuideRepository

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        store = SourceStore(tmp.root, io)
        channels = ChannelRepository(tmp.root, { now }, io)
        guides = GuideRepository(tmp.root, io)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun manager(maxTextBytes: Long = SourceFetcher.MAX_TEXT_BYTES) = LiveSourceManager(
        store,
        channels,
        guides,
        // Metadata is IptvMetadataRepositoryTest's subject: here it always fails fast (refused port).
        IptvMetadataRepository(tmp.root, SourceFetcher(OkHttpClient(), io), { now }, io, "http://127.0.0.1:1/"),
        SourceFetcher(OkHttpClient(), io, maxTextBytes),
        StremioTvAdapter(SourceFetcher(OkHttpClient(), io)),
        XmltvNowNextParser({ KXmlParser() }),
        { File(PRESET_PATH).inputStream() },
        { now },
        { "s${++ids}" },
        io
    )

    private fun playlist(vararg ids: String) = buildString {
        append("#EXTM3U\n")
        for (id in ids) append("#EXTINF:-1 tvg-id=\"$id.in\",${id.uppercase()}\nhttps://example.org/$id.m3u8\n")
    }

    private fun url() = server.url("/list.m3u").toString()

    private suspend fun added(manager: LiveSourceManager, body: String): LiveSource {
        server.enqueue(MockResponse().setBody(body))
        val result = manager.addUrl(url())
        assertTrue("$result", result is LiveSourceManager.AddResult.Added)
        return (result as LiveSourceManager.AddResult.Added).source
    }

    @Test
    fun `adding a playlist stores the source and its channels`() = runTest {
        val source = added(manager(), playlist("a", "b"))
        assertEquals(SourceKind.M3U, source.kind)
        assertEquals(server.hostName, source.name)
        assertEquals(2, source.channelCount)
        assertEquals(listOf(source), store.sources.value)
        assertEquals(listOf("s1#id:a.in", "s1#id:b.in"), channels.channels.value.map { it.key })

        val reread = SourceStore(tmp.root, io)
        reread.load()
        assertEquals(listOf(source), reread.sources.value)
    }

    @Test
    fun `content that is not a playlist is refused and nothing is stored`() = runTest {
        val manager = manager()
        server.enqueue(MockResponse().setBody("<html><body>Login</body></html>"))
        assertEquals(LiveSourceManager.AddResult.Refused("Not a playlist, guide or Stremio addon"), manager.addUrl(url()))
        server.enqueue(MockResponse().setBody("#EXTM3U\n"))
        assertEquals(LiveSourceManager.AddResult.Refused("No channels in this playlist"), manager.addUrl(url()))
        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(LiveSourceManager.AddResult.Refused("HTTP 403"), manager.addUrl(url()))
        assertEquals(LiveSourceManager.AddResult.Refused("Not a web address"), manager.addUrl("list.m3u"))
        assertTrue(store.sources.value.isEmpty())
        assertTrue(channels.channels.value.isEmpty())
    }

    @Test
    fun `the same URL is not added twice`() = runTest {
        val manager = manager()
        added(manager, playlist("a"))
        assertEquals(LiveSourceManager.AddResult.Refused("Already added"), manager.addUrl(url()))
    }

    @Test
    fun `a check stores nothing, and adding it stores without fetching again`() = runTest {
        val manager = manager()
        server.enqueue(MockResponse().setBody(playlist("a", "b", "c")))
        val ready = manager.check(url()) as LiveSourceManager.CheckResult.Ready
        assertEquals(SourceKind.M3U, ready.checked.source.kind)
        assertEquals(3, ready.checked.listed)
        assertTrue(store.sources.value.isEmpty())
        assertTrue(channels.channels.value.isEmpty())

        val added = manager.add(ready.checked) as LiveSourceManager.AddResult.Added
        assertEquals(1, server.requestCount)
        assertEquals(listOf(added.source), store.sources.value)
        assertEquals(3, channels.channels.value.size)
    }

    @Test
    fun `a checked URL added meanwhile is refused at add`() = runTest {
        val manager = manager()
        server.enqueue(MockResponse().setBody(playlist("a")))
        val first = (manager.check(url()) as LiveSourceManager.CheckResult.Ready).checked
        server.enqueue(MockResponse().setBody(playlist("a")))
        val second = (manager.check(url()) as LiveSourceManager.CheckResult.Ready).checked
        assertTrue(manager.add(first) is LiveSourceManager.AddResult.Added)
        assertEquals(LiveSourceManager.AddResult.Refused("Already added"), manager.add(second))
        assertEquals(1, store.sources.value.size)
        assertEquals(1, channels.channels.value.size)
    }

    @Test
    fun `rename trims, persists, and refuses blank names and unknown sources`() = runTest {
        val manager = manager()
        val source = added(manager, playlist("a"))
        assertTrue(manager.rename(source.id, "  My playlist "))
        assertEquals("My playlist", store.sources.value.single().name)
        assertEquals(false, manager.rename(source.id, "   "))
        assertEquals(false, manager.rename("missing", "X"))
        assertEquals("My playlist", store.sources.value.single().name)

        val reread = SourceStore(tmp.root, io)
        reread.load()
        assertEquals("My playlist", reread.sources.value.single().name)
    }

    @Test
    fun `a refresh merges the fresh listing and marks dropped channels lost`() = runTest {
        val manager = manager()
        val source = added(manager, playlist("a", "b"))
        now += 5_000
        server.enqueue(MockResponse().setBody(playlist("b", "c")))
        val refreshed = manager.refresh(source.id)!!
        assertEquals(now, refreshed.lastRefreshMs)
        assertNull(refreshed.lastError)
        assertEquals(2, refreshed.channelCount)
        val byKey = channels.channels.value.associateBy { it.key }
        assertEquals(setOf("s1#id:a.in", "s1#id:b.in", "s1#id:c.in"), byKey.keys)
        assertEquals(now, byKey.getValue("s1#id:a.in").lostAtMs)
        assertNull(byKey.getValue("s1#id:c.in").lostAtMs)
    }

    @Test
    fun `a failed refresh keeps the old channels and records the reason`() = runTest {
        val manager = manager(maxTextBytes = 400)
        val source = added(manager, playlist("a", "b"))
        val before = channels.channels.value

        server.enqueue(MockResponse().setBody(playlist(*Array(20) { "x$it" })))
        val tooLarge = manager.refresh(source.id)!!
        assertEquals("Larger than 0 MiB", tooLarge.lastError)
        assertEquals(source.lastRefreshMs, tooLarge.lastRefreshMs)
        assertEquals(before, channels.channels.value)

        server.enqueue(MockResponse().setBody("#EXTM3U\n"))
        assertEquals("No channels in this playlist", manager.refresh(source.id)!!.lastError)
        server.enqueue(MockResponse().setBody("{\"id\":\"x\"}"))
        assertEquals("No longer an M3U playlist", manager.refresh(source.id)!!.lastError)
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals("HTTP 500", manager.refresh(source.id)!!.lastError)
        assertEquals(before, channels.channels.value)

        server.enqueue(MockResponse().setBody(playlist("a")))
        assertNull(manager.refresh(source.id)!!.lastError)
    }

    @Test
    fun `the broadcaster preset parses to seven numbered channels`() = runTest {
        val preset = manager().addBroadcasters()
        assertEquals(LiveSourceManager.PRESET_ID, preset.id)
        assertEquals(7, preset.channelCount)
        assertNull(preset.lastError)
        val list = channels.channels.value
        assertEquals((1..7).map { it.toString() }, list.map { it.channelNumber })
        assertEquals(
            listOf("DW English", "France 24 English", "NHK World-Japan", "Al Jazeera English", "DD News", "DD Sports", "DD Bangla"),
            list.map { it.name }
        )
        assertEquals(2, list.first().streams.size)
        assertEquals(listOf("de", "fr", "jp", "qa", "in", "in", "in"), list.map { it.country })
        assertTrue(list.all { ch -> ch.streams.all { it.url.startsWith("https://") } })
        assertEquals(0, server.requestCount)

        // Spec 49 D27 F5: the India guides are Sources' suggestion now, never added with the preset.
        assertEquals(listOf(LiveSourceManager.PRESET_ID), store.sources.value.map { it.id })

        // Adding again refreshes the one preset source instead of duplicating it.
        manager().addBroadcasters()
        assertEquals(1, store.sources.value.size)
        assertEquals(7, channels.channels.value.size)
    }

    @Test
    fun `removing a source drops its channels`() = runTest {
        val manager = manager()
        val source = added(manager, playlist("a"))
        manager.addBroadcasters()
        assertTrue(manager.remove(source.id))
        assertEquals(LiveSourceManager.PRESET_ID, store.sources.value.first().id)
        assertTrue(store.sources.value.none { it.id == source.id })
        assertTrue(channels.channels.value.all { it.sourceId == LiveSourceManager.PRESET_ID })
        assertNotNull(manager.refresh(LiveSourceManager.PRESET_ID))
        assertNull(manager.refresh(source.id))
    }

    @Test
    fun `a failed channel write refuses the add and leaves no record`() = runTest {
        // A regular file where the channel cache directory should be: every channel write fails.
        channels = ChannelRepository(tmp.newFile("blocked"), { now }, io)
        server.enqueue(MockResponse().setBody(playlist("a")))
        val result = manager().addUrl(url())
        assertTrue("$result", (result as LiveSourceManager.AddResult.Refused).reason.startsWith("Could not save"))
        assertTrue(store.sources.value.isEmpty())
    }

    @Test
    fun `a failed channel write on refresh is recorded, not thrown`() = runTest {
        store.upsert(LiveSource(LiveSourceManager.PRESET_ID, SourceKind.M3U, "B", LiveSourceManager.PRESET_URL, 0))
        channels = ChannelRepository(tmp.newFile("blocked"), { now }, io)
        val refreshed = manager().refresh(LiveSourceManager.PRESET_ID)!!
        assertTrue(refreshed.lastError!!.startsWith("Could not save"))
        assertNull(refreshed.lastRefreshMs)
    }

    private fun epgBody() = Buffer().readFrom(javaClass.getResourceAsStream("/livetv/epg-pw-in-trimmed.xml.gz")!!)

    private val guideAt = Instant.parse("2026-10-19T02:15:00Z").toEpochMilli()

    @Test
    fun `a guide is added, matched, cached and read back on a cold start`() = runTest {
        now = guideAt
        val manager = manager()
        added(manager, playlist("StarJalsha").replace("STARJALSHA", "Star Jalsha HD"))
        server.enqueue(MockResponse().setBody(epgBody()))
        val result = manager.addUrl(server.url("/xmltv/epg_IN.xml.gz").toString())
        val guide = (result as LiveSourceManager.AddResult.Added).source
        assertEquals(SourceKind.XMLTV, guide.kind)
        assertEquals("in", guide.country)
        assertEquals(1, guide.channelCount) // Star Jalsha matched by name inside India
        assertNull(guide.lastError)
        assertEquals("Bigg Boss Bangla", guides.guides.value.getValue(guide.id).nowNext("542998", now).first?.title)

        val cold = GuideRepository(tmp.root, io)
        cold.load(listOf(guide.id))
        val (cur, next) = cold.guides.value.getValue(guide.id).nowNext("542998", now)
        assertEquals("Bigg Boss Bangla", cur?.title)
        assertEquals("Ajmeri Gems House", next?.title)

        assertTrue(manager.remove(guide.id))
        assertTrue(guides.guides.value.isEmpty())
        assertTrue(File(tmp.root, "epg-${guide.id}.json").exists().not())
    }

    @Test
    fun `a cut-short guide never replaces a complete one`() = runTest {
        now = guideAt
        val manager = manager()
        server.enqueue(MockResponse().setBody(epgBody()))
        val guide = (manager.addUrl(server.url("/epg_IN.xml.gz").toString()) as LiveSourceManager.AddResult.Added).source
        val before = guides.guides.value.getValue(guide.id)

        now += 60_000
        server.enqueue(MockResponse().setBody(epgHalf()))
        val refreshed = manager.refresh(guide.id)!!
        assertEquals("Guide file incomplete; kept what was read", refreshed.lastError)
        assertEquals(guide.lastRefreshMs, refreshed.lastRefreshMs)
        assertEquals(before, guides.guides.value.getValue(guide.id))

        server.enqueue(MockResponse().setBody("#EXTM3U\n#EXTINF:-1,A\nhttp://x/a\n"))
        assertEquals("No longer an XMLTV guide", manager.refresh(guide.id)!!.lastError)
    }

    private fun epgHalf(): String {
        val full = javaClass.getResourceAsStream("/livetv/epg-pw-in-trimmed.xml.gz")!!.use {
            java.util.zip.GZIPInputStream(it).readBytes().decodeToString()
        }
        return full.substring(0, full.length / 2)
    }

    @Test
    fun `a partial guide is kept until a complete one arrives, and beats an expired complete one`() = runTest {
        now = guideAt
        val manager = manager()
        server.enqueue(MockResponse().setBody(epgHalf()))
        val guide = (manager.addUrl(server.url("/epg_IN.xml").toString()) as LiveSourceManager.AddResult.Added).source
        assertEquals("Guide file incomplete; kept what was read", guide.lastError)
        assertTrue(!guides.guides.value.getValue(guide.id).complete)

        // A partial over a partial is taken (nothing better is cached).
        now += 2L * 60 * 60 * 1000
        server.enqueue(MockResponse().setBody(epgHalf()))
        assertEquals(now, manager.refresh(guide.id)!!.lastRefreshMs)

        server.enqueue(MockResponse().setBody(epgBody()))
        val complete = manager.refresh(guide.id)!!
        assertNull(complete.lastError)
        assertTrue(guides.guides.value.getValue(guide.id).complete)

        // Once the complete snapshot's window has passed, a partial replaces it.
        now += 7L * 60 * 60 * 1000
        server.enqueue(MockResponse().setBody(epgHalf()))
        val late = manager.refresh(guide.id)!!
        assertEquals(now, late.lastRefreshMs)
        assertTrue(!guides.guides.value.getValue(guide.id).complete)
    }

    @Test
    fun `removing a guide clears overrides that point at it and leaves channels alone`() = runTest {
        now = guideAt
        val manager = manager()
        added(manager, playlist("a"))
        server.enqueue(MockResponse().setBody(epgBody()))
        val guide = (manager.addUrl(server.url("/epg_IN.xml.gz").toString()) as LiveSourceManager.AddResult.Added).source
        channels.setOverride("s1#id:a.in", EpgOverride(EpgRef(guide.id, "542998")))
        channels.setOverride("s1#id:b.in", EpgOverride(null))
        val before = channels.channels.value
        assertTrue(manager.remove(guide.id))
        assertEquals(mapOf("s1#id:b.in" to EpgOverride(null)), channels.overrides.value)
        assertEquals(before, channels.channels.value)
    }

    @Test
    fun `an unreadable guide cache is skipped and makes the guide stale`() = runTest {
        now = guideAt
        server.enqueue(MockResponse().setBody(epgBody()))
        val guide = (manager().addUrl(server.url("/epg_IN.xml.gz").toString()) as LiveSourceManager.AddResult.Added).source
        File(tmp.root, "epg-${guide.id}.json").writeText("{not json")

        guides = GuideRepository(tmp.root, io)
        server.enqueue(MockResponse().setBody(epgBody()))
        val requests = server.requestCount
        manager().refreshStale()
        assertEquals(requests + 1, server.requestCount)
        assertTrue(guides.guides.value.getValue(guide.id).complete)
    }

    @Test
    fun `stale rule`() {
        val hour = 60L * 60 * 1000
        val m3u = LiveSource("a", SourceKind.M3U, "A", "https://x/a.m3u", 0, lastRefreshMs = 0)
        assertTrue(LiveSourceManager.isStale(m3u.copy(lastRefreshMs = null), null, 0))
        assertTrue(!LiveSourceManager.isStale(m3u, null, 23 * hour))
        assertTrue(LiveSourceManager.isStale(m3u, null, 24 * hour))

        val guide = m3u.copy(kind = SourceKind.XMLTV)
        val snapshot = EpgSnapshot(emptyMap(), emptyMap(), 0, 6 * hour, complete = true)
        assertTrue(LiveSourceManager.isStale(guide, null, hour))
        assertTrue(!LiveSourceManager.isStale(guide, snapshot, 4 * hour))
        assertTrue(LiveSourceManager.isStale(guide, snapshot, 5 * hour))

        val partial = snapshot.copy(complete = false)
        assertTrue(!LiveSourceManager.isStale(guide, partial, hour / 2))
        assertTrue(LiveSourceManager.isStale(guide, partial, hour))

        val stremio = m3u.copy(kind = SourceKind.STREMIO)
        assertTrue(!LiveSourceManager.isStale(stremio, null, 23 * hour))
        assertTrue(LiveSourceManager.isStale(stremio, null, 24 * hour))
    }

    @Test
    fun `refreshStale fetches only what is stale`() = runTest {
        now = guideAt
        val manager = manager()
        val list = added(manager, playlist("a"))
        server.enqueue(MockResponse().setBody(epgBody()))
        val guide = (manager.addUrl(server.url("/epg_IN.xml.gz").toString()) as LiveSourceManager.AddResult.Added).source
        val requests = server.requestCount

        now += 60L * 60 * 1000
        manager.refreshStale()
        assertEquals(requests, server.requestCount)

        now = guideAt + 5L * 60 * 60 * 1000 // guide window nearly over, playlist still fresh
        server.enqueue(MockResponse().setBody(epgBody()))
        manager.refreshStale()
        assertEquals(requests + 1, server.requestCount)
        assertEquals(now, store.get(guide.id)!!.lastRefreshMs)
        assertEquals(list.lastRefreshMs, store.get(list.id)!!.lastRefreshMs)
    }

    @Test
    fun `the India guides are added once as records, keeping a copy a source already holds`() = runTest {
        val manager = manager()
        val epgPw = LiveSourceManager.INDIA_GUIDES[0]
        val mine = LiveSource("mine", SourceKind.XMLTV, "Mine", epgPw.url, 0L)
        store.upsert(mine)

        val guides = manager.addIndiaGuides()!!
        assertEquals(listOf(mine, LiveSourceManager.INDIA_GUIDES[1].copy(addedAtMs = now)), guides)
        assertTrue(guides.all { it.kind == SourceKind.XMLTV && it.lastRefreshMs == null })
        assertEquals("in", guides[1].country)
        assertEquals(0, server.requestCount) // fetched by the caller's refresh, not here

        now += 1_000
        assertEquals(guides, manager.addIndiaGuides())
        assertEquals(2, store.sources.value.size)
    }

    @Test
    fun `the Pluto guide is added once as a record, and not by Add broadcasters`() = runTest {
        val manager = manager()
        manager.addBroadcasters()
        assertTrue(store.sources.value.none { it.url == LiveSourceManager.PLUTO_GUIDE.url })

        val guide = manager.addPlutoGuide()!!
        assertEquals(LiveSourceManager.PLUTO_GUIDE.copy(addedAtMs = now), guide)
        assertEquals(SourceKind.XMLTV, guide.kind)
        assertNull(guide.country) // never matched by name
        assertNull(guide.lastRefreshMs) // fetched by the caller's refresh, not here
        assertEquals(0, server.requestCount)

        // A source already holding the URL (here the record itself) is returned, not duplicated.
        now += 1_000
        assertEquals(guide, manager.addPlutoGuide())
        assertEquals(1, store.sources.value.count { it.url == LiveSourceManager.PLUTO_GUIDE.url })
    }

    @Test
    fun `guide country comes from the file name`() {
        fun c(url: String) = LiveSourceManager.guessCountry(url.toHttpUrl())
        assertEquals("in", c("https://epg.pw/xmltv/epg_IN.xml.gz"))
        assertEquals("in", c("https://www.free-epg.de/api/epg/in.xml.gz"))
        assertEquals("ca", c("https://example.org/guide-ca.xml"))
        assertNull(c("https://example.org/guide.xml"))
        assertNull(c("https://example.org/epg"))
    }

    private companion object {
        /** Unit tests run with the module directory as the working directory. */
        const val PRESET_PATH = "src/main/assets/livetv/broadcasters.m3u"
    }
}
