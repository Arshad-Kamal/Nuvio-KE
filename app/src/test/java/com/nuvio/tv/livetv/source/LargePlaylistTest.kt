package com.nuvio.tv.livetv.source

import com.nuvio.tv.livetv.parse.M3uParser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.GZIPOutputStream

/** Spec 137: a full Xtream list past the old 32 MiB cap loads; the bigger cap is the playlist's alone. */
@OptIn(ExperimentalCoroutinesApi::class)
class LargePlaylistTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /**
     * An `m3u_plus` list generated as it is read, never held whole: [live] channels first, then
     * movie and series items (Xtream's order) until [vodBytes], then [live] more channels.
     */
    private class XtreamList(private val live: Int, private val vodBytes: Long) : InputStream() {
        private var chunk = "#EXTM3U x-tvg-url=\"http://xt.example/xmltv.php\"\n".toByteArray()
        private var pos = 0
        private var item = 0
        private var vodSent = 0L
        private var liveAfter = 0
        var total = 0L
            private set

        private fun nextChunk(): Boolean {
            val text = when {
                item < live -> live(item)
                vodSent < vodBytes -> vod(item).also { vodSent += it.length }
                liveAfter < live -> live(live + liveAfter++)
                else -> return false
            }
            item++
            chunk = text.toByteArray()
            pos = 0
            return true
        }

        private fun live(n: Int) = "#EXTINF:-1 tvg-id=\"ch$n.uk\" tvg-name=\"UK: Channel $n\" " +
            "tvg-logo=\"http://xt.example/logo/$n.png\" group-title=\"UK | General\",UK: Channel $n\n" +
            "http://xt.example:8080/user1/pass1/$n.ts\n"

        // ~330 bytes, the size P0 estimated for a VOD item; movies and episodes alternate.
        private fun vod(n: Int): String {
            val kind = if (n % 2 == 0) "movie" else "series"
            return "#EXTINF:-1 tvg-id=\"\" tvg-name=\"Title number $n (2024) [Multi-Sub]\" " +
                "tvg-logo=\"https://image.tmdb.org/t/p/w600_and_h900_bestv2/abcdefghijklmnopqrstuvw$n.jpg\" " +
                "group-title=\"VOD | Action & Adventure\",Title number $n (2024) [Multi-Sub]\n" +
                "http://xt.example:8080/$kind/user1/pass1/$n.mkv\n"
        }

        override fun read(): Int {
            if (pos == chunk.size && !nextChunk()) return -1
            total++
            return chunk[pos++].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (pos == chunk.size && !nextChunk()) return -1
            val n = minOf(len, chunk.size - pos)
            System.arraycopy(chunk, pos, b, off, n)
            pos += n
            total += n
            return n
        }
    }

    private fun assertLoads(raw: InputStream, live: Int) {
        val (kind, body) = SourceFetcher.sniff(raw)
        assertEquals(SourceKind.M3U, kind)
        val playlist = M3uParser.parse(body.reader(Charsets.UTF_8))
        assertEquals(2 * live, playlist.entries.size)
        assertEquals((0 until 2 * live).map { "UK: Channel $it" }, playlist.entries.map { it.title })
        assertEquals(listOf("http://xt.example/xmltv.php"), playlist.epgUrls)
    }

    @Test
    fun `a full Xtream list over 32 MiB, mostly movies and series, keeps its live channels`() {
        val list = XtreamList(live = 500, vodBytes = 40L * 1024 * 1024)
        val startNs = System.nanoTime()
        assertLoads(list, live = 500)
        val ms = (System.nanoTime() - startNs) / 1_000_000
        assertTrue(list.total > SourceFetcher.MAX_TEXT_BYTES)
        // Spec 137 D4: the desktop parse rate, recorded in the spec (stdout lands in the test report).
        println("Spec 137 parse rate: ${list.total / (1024 * 1024)} MiB in $ms ms = ${list.total * 1000 / 1024 / 1024 / maxOf(ms, 1)} MiB/s")
    }

    @Test
    fun `the same list gzipped loads too`() {
        val gz = ByteArrayOutputStream()
        GZIPOutputStream(gz).use { XtreamList(live = 50, vodBytes = 34L * 1024 * 1024).copyTo(it) }
        assertLoads(gz.toByteArray().inputStream(), live = 50)
    }

    @Test
    fun `the playlist cap applies to a source's playlist alone`() = runTest {
        val fetcher = SourceFetcher(OkHttpClient(), UnconfinedTestDispatcher(), maxTextBytes = 1_000, maxPlaylistBytes = 5_000)
        val url = server.url("/source").toString()
        suspend fun read(sourceCaps: Boolean = true) = fetcher.fetch(url, sourceCaps) { kind, body -> kind to body.readBytes().size }
        val playlist = "#EXTM3U\n" + "#EXTINF:-1,A\nhttp://x/a\n".repeat(150)

        // A playlist over the text cap and under its own (8 + 150 x 24 bytes) loads whole.
        server.enqueue(MockResponse().setBody(playlist))
        assertEquals(SourceKind.M3U to 3_608, read())

        server.enqueue(MockResponse().setBody("#EXTM3U\n" + "#EXTINF:-1,A\nhttp://x/a\n".repeat(250)))
        assertFails("Larger than 0 MiB") { read() }
        // A manifest and an undetected body (the pack's TSV) keep the text cap.
        server.enqueue(MockResponse().setBody("{\"id\":\"x\",\"pad\":\"" + "p".repeat(3_000) + "\"}"))
        assertFails("Larger than 0 MiB") { read() }
        server.enqueue(MockResponse().setBody("name\turl\n" + "a\thttp://x/a\n".repeat(250)))
        assertFails("Larger than 0 MiB") { read() }
        // A source's guide keeps the guide cap (500 MiB), far over both test caps.
        val guide = "<tv>" + "<x/>".repeat(1_500) + "</tv>"
        server.enqueue(MockResponse().setBody(guide))
        assertEquals(SourceKind.XMLTV to guide.length, read())
        // Any other fetch (an addon catalog, which reads its body whole) keeps the text cap whatever its head.
        server.enqueue(MockResponse().setBody(playlist))
        assertFails("Larger than 0 MiB") { read(sourceCaps = false) }
        server.enqueue(MockResponse().setBody("<tv>" + "<x/>".repeat(1_000) + "</tv>"))
        assertFails("Larger than 0 MiB") { read(sourceCaps = false) }
    }

    private suspend fun assertFails(message: String, block: suspend () -> Any) {
        try {
            block()
            fail("expected $message")
        } catch (e: SourceFetchException) {
            assertEquals(message, e.message)
        }
    }

}
