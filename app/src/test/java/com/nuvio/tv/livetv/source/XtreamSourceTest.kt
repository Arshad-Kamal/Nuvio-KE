package com.nuvio.tv.livetv.source

import com.nuvio.tv.livetv.data.ChannelRepository
import com.nuvio.tv.livetv.data.GuideRepository
import com.nuvio.tv.livetv.data.localDayStartMs
import com.nuvio.tv.livetv.model.EpgOverride
import com.nuvio.tv.livetv.model.EpgRef
import com.nuvio.tv.livetv.parse.M3uParser
import com.nuvio.tv.livetv.parse.XtreamUrls
import com.nuvio.tv.livetv.parse.XmltvNowNextParser
import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.rules.TemporaryFolder
import org.kxml2.io.KXmlParser
import java.io.File
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Tests too big for the full suite's shared test JVM (Spec 109 D26): `testFullDebugUnitTest` skips them and its
 * finalizer `testFullDebugLargeHeapUnitTest` runs them in their own JVM (app/build.gradle.kts).
 */
interface LargeHeap

/**
 * Spec 109 S1: Xtream logins against recorded `player_api.php` shapes (MockWebServer; no provider
 * account in the house, Spec 109 Q1). Panels differ in whether numbers come quoted, so the
 * fixtures mix both.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class XtreamSourceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val io = UnconfinedTestDispatcher()
    private var now = NOW_MS
    private var ids = 0
    private lateinit var store: SourceStore
    private lateinit var channels: ChannelRepository
    private lateinit var accounts: XtreamAccountStore
    private lateinit var guides: GuideRepository

    /** The `xmltv.php` answer as status to body; null = 404. */
    private var guideAnswer: Pair<Int, String>? = null

    /** The `get.php` answer (a pasted link's playlist) as status to body. */
    private var linkAnswer: Pair<Int, String> = 200 to LINK_PLAYLIST

    /** The answer per `action` (null key = the bare sign-in), as status to body. */
    private val routes = HashMap<String?, Pair<Int, String>>()

    /** A 200 body per `action` built straight into a buffer, never held as a String (large lists). */
    private val buffers = HashMap<String?, () -> Buffer>()
    private val seen = java.util.Collections.synchronizedList(mutableListOf<RecordedRequest>())

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = dispatcher()
        server.start()
        store = SourceStore(tmp.root, io)
        channels = ChannelRepository(tmp.root, { now }, io)
        accounts = XtreamAccountStore(tmp.root, io)
        guides = GuideRepository(tmp.root, io)
        routes[null] = 200 to SIGN_IN
        routes["get_live_categories"] = 200 to CATEGORIES
        routes["get_live_streams"] = 200 to STREAMS
    }

    /** One panel's answers ([routes], [buffers], [guideAnswer]); a second server (Edit login's move) shares them. */
    private fun dispatcher() = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                val url = request.requestUrl!!
                if (url.encodedPath == "/xmltv.php") {
                    val (code, body) = guideAnswer ?: (404 to "")
                    return MockResponse().setResponseCode(code).setBody(body)
                }
                if (url.encodedPath == "/get.php") return MockResponse().setResponseCode(linkAnswer.first).setBody(linkAnswer.second)
                if (url.encodedPath != "/player_api.php") return MockResponse().setResponseCode(404)
                buffers[url.queryParameter("action")]?.let { return MockResponse().setBody(it()) }
                val (code, body) = routes[url.queryParameter("action")] ?: (404 to "")
                return MockResponse().setResponseCode(code).setBody(body)
            }
        }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun manager(
        fetcher: SourceFetcher = SourceFetcher(OkHttpClient(), io),
        sourceStore: SourceStore = store,
        guideRepo: GuideRepository = guides,
        zone: ZoneId = ZoneId.systemDefault()
    ) = LiveSourceManager(
        sourceStore,
        channels,
        guideRepo,
        IptvMetadataRepository(tmp.root, SourceFetcher(OkHttpClient(), io), { now }, io, "http://127.0.0.1:1/"),
        fetcher,
        StremioTvAdapter(SourceFetcher(OkHttpClient(), io)),
        XmltvNowNextParser({ KXmlParser() }),
        { error("no preset here") },
        { now },
        { "x${++ids}" },
        io,
        zone = { zone },
        pack = PackStore(tmp.root, io),
        scans = PackScanStore(tmp.root, io),
        accounts = accounts
    )

    /** The server as a user types it: no scheme, a trailing slash. */
    private fun typed() = "${server.hostName}:${server.port}/"

    private fun base() = "http://${server.hostName}:${server.port}"

    private suspend fun addLogin(manager: LiveSourceManager, user: String = USER, pass: String = PASS): LiveSource {
        val check = manager.checkXtream(typed(), user, pass)
        assertTrue("$check", check is LiveSourceManager.CheckResult.Ready)
        val added = manager.add((check as LiveSourceManager.CheckResult.Ready).checked)
        assertTrue("$added", added is LiveSourceManager.AddResult.Added)
        return (added as LiveSourceManager.AddResult.Added).source
    }

    private suspend fun refused(manager: LiveSourceManager, user: String = USER, pass: String = PASS): String {
        val check = manager.checkXtream(typed(), user, pass)
        assertTrue("$check", check is LiveSourceManager.CheckResult.Refused)
        return (check as LiveSourceManager.CheckResult.Refused).reason
    }

    @Test
    fun `a login is checked, added and listed by stream id`() = runTest {
        val source = addLogin(manager())

        assertEquals(SourceKind.XTREAM, source.kind)
        assertEquals(base(), source.url)
        assertEquals(server.hostName, source.name)
        assertEquals(3, source.channelCount)
        assertEquals(XtreamAccountInfo(expiresAtMs = EXPIRES_S * 1000, maxConnections = 2, trial = false, categories = 2), source.account)
        assertEquals(XtreamLogin(base(), USER, PASS), accounts.get(source.id))

        val listed = channels.channels.value
        assertEquals(listOf("x1#xt:101", "x1#xt:102", "x1#xt:205"), listed.map { it.key })
        val bbc = listed.first()
        assertEquals("BBC One HD", bbc.name)
        assertEquals("1", bbc.channelNumber)
        assertEquals("http://logos.example.net/bbc1.png", bbc.logo)
        assertEquals("BBCOne.uk", bbc.tvgId)
        assertEquals("uk", bbc.country)
        assertEquals(listOf("UK | Entertainment"), bbc.groups)
        // category_ids (newer panels) wins over category_id; an unknown id adds no group.
        assertEquals(listOf("UK | Entertainment", "News"), listed[1].groups)
        // A blank name falls back to the id; no epg id, no country.
        assertEquals("205", listed[2].name)
        assertNull(listed[2].tvgId)
        assertEquals(listOf("${base()}/live/$USER/$PASS/101.ts"), bbc.streams.map { it.url })
        assertTrue(XtreamUrls.isLive(bbc.streams.single().url))
    }

    @Test
    fun `the sign-in sends the login in the query of player_api`() = runTest {
        addLogin(manager())
        val signIn = seen.first().requestUrl!!
        assertEquals("/player_api.php", signIn.encodedPath)
        assertEquals(USER, signIn.queryParameter("username"))
        assertEquals(PASS, signIn.queryParameter("password"))
        assertNull(signIn.queryParameter("action"))
        assertEquals(
            listOf(null, "get_live_categories", "get_live_streams"),
            seen.map { it.requestUrl!!.queryParameter("action") }
        )
    }

    @Test
    fun `refusals give fixed reasons and store nothing`() = runTest {
        val manager = manager()
        val cases = listOf(
            (200 to """{"user_info":{"auth":0}}""") to XtreamAdapter.LOGIN_REFUSED,
            (401 to "") to XtreamAdapter.LOGIN_REFUSED,
            (403 to "Forbidden") to XtreamAdapter.LOGIN_REFUSED,
            (404 to "") to XtreamAdapter.NOT_XTREAM,
            (200 to "<html><body>Welcome to nginx</body></html>") to XtreamAdapter.NOT_XTREAM,
            (200 to """{"server_info":{}}""") to XtreamAdapter.NOT_XTREAM,
            (200 to userInfo(status = "Expired")) to XtreamAdapter.EXPIRED,
            (200 to userInfo(expires = NOW_MS / 1000 - 60)) to XtreamAdapter.EXPIRED,
            (200 to userInfo(status = "Banned")) to XtreamAdapter.DISABLED,
            (200 to userInfo(status = "Disabled")) to XtreamAdapter.DISABLED,
            (500 to "") to "HTTP 500"
        )
        for ((answer, reason) in cases) {
            routes[null] = answer
            assertEquals("$answer", reason, refused(manager))
        }
        routes[null] = 200 to SIGN_IN
        routes["get_live_streams"] = 200 to "[]"
        assertEquals("No live channels on this account", refused(manager))
        routes["get_live_streams"] = 200 to """{"user_info":{"auth":0}}"""
        assertEquals(XtreamAdapter.UNREADABLE, refused(manager))
        assertEquals(LiveSourceManager.XTREAM_FILL_IN, refused(manager, pass = "  "))
        assertEquals(LiveSourceManager.XTREAM_FILL_IN, manager.checkXtream(" ", USER, PASS).let {
            (it as LiveSourceManager.CheckResult.Refused).reason
        })

        assertTrue(store.sources.value.isEmpty())
        assertTrue(channels.channels.value.isEmpty())
        assertFalse(File(tmp.root, "accounts.json").exists())
    }

    @Test
    fun `no reason, record or file but the login store carries a credential`() = runTest {
        val manager = manager()
        val failing = listOf(
            200 to """{"user_info":{"auth":0,"username":"$USER","password":"$PASS"}}""",
            401 to "bad $USER / $PASS",
            404 to "",
            500 to "$PASS",
            200 to "{\"user_info\":" + "[".repeat(5000)
        )
        for (answer in failing) {
            routes[null] = answer
            val reason = refused(manager)
            assertFalse(reason, reason.contains(USER) || reason.contains(PASS))
        }
        routes[null] = 200 to SIGN_IN
        val source = addLogin(manager)
        assertFalse("$source", source.toString().contains(USER) || source.toString().contains(PASS))
        assertFalse(accounts.get(source.id).toString().contains(PASS))
        // A refresh that fails keeps its reason free of them too.
        routes[null] = 403 to "$USER $PASS"
        val failed = manager.refresh(source.id)!!
        assertEquals(XtreamAdapter.LOGIN_REFUSED, failed.lastError)

        val files = tmp.root.walkTopDown().filter { it.isFile }.toList()
        assertTrue(files.any { it.name == "accounts.json" })
        for (file in files.filter { it.name == "sources.json" }) {
            val text = file.readText()
            assertFalse(file.name, text.contains(USER) || text.contains(PASS))
        }
    }

    @Test
    fun `the same server and username is added once, another username beside it`() = runTest {
        val manager = manager()
        addLogin(manager)
        assertEquals("Already added", refused(manager))
        addLogin(manager, user = "guest.room")
        assertEquals(2, store.sources.value.size)
    }

    @Test
    fun `a new password keeps the channel keys and moves the stream URLs`() = runTest {
        val manager = manager()
        val source = addLogin(manager)
        val keys = channels.channels.value.map { it.key }
        accounts.put(source.id, XtreamLogin(base(), USER, "n3w-Pa55"))
        now += 1_000
        val refreshed = manager.refresh(source.id)!!
        assertNull(refreshed.lastError)
        assertEquals(keys, channels.channels.value.map { it.key })
        assertEquals("${base()}/live/$USER/n3w-Pa55/101.ts", channels.channels.value.first().streams.single().url)
        assertEquals(USER, seen.last().requestUrl!!.queryParameter("username"))
        assertEquals("n3w-Pa55", seen.last().requestUrl!!.queryParameter("password"))
    }

    @Test
    fun `a record whose login is missing keeps its channels and asks for Edit login`() = runTest {
        val manager = manager()
        val source = addLogin(manager)
        accounts.remove(source.id)
        val refreshed = manager.refresh(source.id)!!
        assertEquals(LiveSourceManager.XTREAM_LOGIN_MISSING, refreshed.lastError)
        assertEquals(3, channels.channels.value.size)
        assertEquals(source.account, refreshed.account)
    }

    @Test
    fun `remove drops channels, login, guide, guide choices and record`() = runTest {
        guideAnswer = 200 to GUIDE
        val manager = manager()
        val source = addLogin(manager)
        manager.refresh(source.id)
        assertTrue(File(tmp.root, "epg-${source.id}.json").exists())
        channels.setOverride("other#id:a", EpgOverride(EpgRef(source.id, "BBCOne.uk")))
        assertTrue(manager.remove(source.id))
        assertTrue(store.sources.value.isEmpty())
        assertTrue(channels.channels.value.isEmpty())
        assertNull(accounts.get(source.id))
        assertNull(XtreamAccountStore(tmp.root, io).get(source.id))
        assertFalse(File(tmp.root, "epg-${source.id}.json").exists())
        assertNull(guides.guides.value[source.id])
        assertTrue(channels.overrides.value.isEmpty())
    }

    @Test
    fun `logins survive a restart`() = runTest {
        val source = addLogin(manager())
        assertEquals(XtreamLogin(base(), USER, PASS), XtreamAccountStore(tmp.root, io).get(source.id))
    }

    @Test
    fun `an Xtream source is stale after a day`() {
        val source = LiveSource("x", SourceKind.XTREAM, "n", "http://h", addedAtMs = 0, lastRefreshMs = NOW_MS)
        val zone = java.time.ZoneOffset.UTC
        assertFalse(LiveSourceManager.isStale(source, null, NOW_MS + LiveSourceManager.MAX_AGE_MS - 1, zone))
        assertTrue(LiveSourceManager.isStale(source, null, NOW_MS + LiveSourceManager.MAX_AGE_MS, zone))
    }

    @Test
    fun `no expiry, an m3u8-only account and quoted numbers`() = runTest {
        routes[null] = 200 to """{"user_info":{"auth":"1","status":"Active","exp_date":null,"is_trial":"1",
            "max_connections":"0","allowed_output_formats":["m3u8"]}}"""
        val source = addLogin(manager())
        assertEquals(XtreamAccountInfo(expiresAtMs = null, maxConnections = null, trial = true, categories = 2), source.account)
        assertTrue(channels.channels.value.first().streams.single().url.endsWith("/101.m3u8"))
    }

    @Test
    fun `an expiry past what milliseconds hold is no date, not an expired account`() = runTest {
        routes[null] = 200 to userInfo(expires = Long.MAX_VALUE / 100)
        assertNull(addLogin(manager()).account!!.expiresAtMs)
    }

    @Test
    fun `server addresses are normalised`() {
        assertEquals("http://tv.example.net:8080", XtreamAdapter.serverBase(" tv.example.net:8080/ "))
        assertEquals("https://tv.example.net", XtreamAdapter.serverBase("https://tv.example.net/player_api.php?username=a&password=b"))
        assertEquals(
            "http://tv.example.net:8080",
            XtreamAdapter.serverBase("http://u:p@tv.example.net:8080/get.php?username=a&password=b&type=m3u_plus#x")
        )
        assertEquals("http://tv.example.net/panel", XtreamAdapter.serverBase("tv.example.net/panel/"))
        assertNull(XtreamAdapter.serverBase("   "))
        assertNull(XtreamAdapter.serverBase("not a host at all"))
    }

    @Test
    fun `output extension`() {
        assertEquals("ts", XtreamAdapter.outputExtension(emptyList()))
        assertEquals("ts", XtreamAdapter.outputExtension(listOf("m3u8", "ts", "rtmp")))
        assertEquals("m3u8", XtreamAdapter.outputExtension(listOf("m3u8", "rtmp")))
        assertEquals("ts", XtreamAdapter.outputExtension(listOf("rtmp")))
    }

    @Test
    fun `nesting past the cap is unreadable, inside strings it is text`() {
        fun read(
            text: String,
            depth: Int = XtreamAdapter.MAX_DEPTH,
            string: Int = XtreamAdapter.MAX_STRING_BYTES,
            element: Int = XtreamAdapter.MAX_ELEMENT_BYTES
        ) = JsonBoundedInputStream(text.byteInputStream(), depth, string, element).readBytes().decodeToString()
        fun refused(
            text: String,
            depth: Int = XtreamAdapter.MAX_DEPTH,
            string: Int = XtreamAdapter.MAX_STRING_BYTES,
            element: Int = XtreamAdapter.MAX_ELEMENT_BYTES
        ) {
            val e = runCatching { read(text, depth, string, element) }.exceptionOrNull()
            assertEquals(text, XtreamAdapter.UNREADABLE, (e as? SourceFetchException)?.message)
        }
        refused("[".repeat(XtreamAdapter.MAX_DEPTH + 1))
        // Brackets inside a string, an escaped quote and an escaped backslash before the closing quote.
        val text = """{"a":"[[[[\"{{{{","b":"x\\"}"""
        assertEquals(text, read(text, depth = 2))
        // After an escaped backslash the next quote closes the string: brackets after it count.
        refused("""{"b":"x\\",[[["""", depth = 3)
        // Closing brackets free depth again.
        assertEquals("[[]],[[]]", read("[[]],[[]]", depth = 2))
        // One string past the byte cap; one of exactly the cap and a longer document of short strings pass.
        refused("[\"" + "a".repeat(11) + "\"]", string = 10)
        assertEquals("[\"" + "a".repeat(10) + "\"]", read("[\"" + "a".repeat(10) + "\"]", string = 10))
        // An unquoted literal is bounded the same way.
        refused("[" + "1".repeat(11) + "]", string = 10)
        assertEquals("[1234567890, true, null]", read("[1234567890, true, null]", string = 10))
        assertEquals("[\"aaaa\",\"bbbb\",\"cccc\"]", read("[\"aaaa\",\"bbbb\",\"cccc\"]", string = 10))
        // One element below the top level past its byte cap (its closing bracket, back at the top
        // level, is not counted: 12 bytes here); each element counts afresh, the top level not at all.
        refused("[{\"a\":[0,0,0]}]", element = 11)
        assertEquals("[{\"a\":[0,0,0]}]", read("[{\"a\":[0,0,0]}]", element = 12))
        assertEquals("[{\"a\":[0,0]},{\"a\":[0,0]}]", read("[{\"a\":[0,0]},{\"a\":[0,0]}]", element = 11))
        assertEquals("[1,2,3,4,5,6,7,8,9,10,11,12]", read("[1,2,3,4,5,6,7,8,9,10,11,12]", element = 2))
        refused("{\"user_info\":{\"a\":\"bbbbbb\"}}", element = 12)
        // skip() scans what it skips.
        val skipped = JsonBoundedInputStream("[[[".byteInputStream(), 2, 10, 100)
        assertEquals(SourceFetchException::class.java, runCatching { skipped.skip(3) }.exceptionOrNull()?.javaClass)
    }

    @Test
    fun `a string longer than the cap refuses the listing`() = runTest {
        routes["get_live_streams"] = 200 to """[{"stream_id":1,"name":"${"n".repeat(XtreamAdapter.MAX_STRING_BYTES + 1)}"}]"""
        assertEquals(XtreamAdapter.UNREADABLE, refused(manager()))
    }

    /** A JSON array of [count] elements, [element] (1-based) each, written straight into a buffer. */
    private fun arrayBuffer(count: Int, element: (Int) -> String): Buffer {
        val out = Buffer().writeUtf8("[")
        for (n in 1..count) {
            if (n > 1) out.writeUtf8(",")
            out.writeUtf8(element(n))
        }
        return out.writeUtf8("]")
    }

    @Test
    @Category(LargeHeap::class)
    fun `a large account past 32 MiB is cut at MAX_CHANNELS, not refused`() = runTest {
        // Real panel entries (~360 bytes): the list passes the 32 MiB text cap before MAX_CHANNELS.
        val pad = "x".repeat(60)
        val streams = {
            arrayBuffer(XtreamAdapter.MAX_CHANNELS + 5) { n ->
                """{"num":$n,"name":"Channel $n HD","stream_type":"live","stream_id":$n,""" +
                    """"stream_icon":"http:\/\/logos.example.net\/images\/channels\/$pad\/$n.png","epg_channel_id":"Channel$n.uk",""" +
                    """"added":"1700000000","is_adult":"0","category_id":"12","category_ids":[12],"custom_sid":"",""" +
                    """"tv_archive":0,"direct_source":"","tv_archive_duration":0}"""
            }
        }
        assertTrue(streams().size > SourceFetcher.MAX_TEXT_BYTES)
        buffers["get_live_streams"] = streams
        val source = addLogin(manager())
        assertEquals(XtreamAdapter.MAX_CHANNELS, source.channelCount)
        assertEquals("x1#xt:${XtreamAdapter.MAX_CHANNELS}", channels.channels.value.last().key)
    }

    @Test
    fun `sign-in and categories take the fetcher's text cap, the stream list its playlist cap`() = runTest {
        // Small caps: 4 KiB text, 1 MiB playlist. Each answer below is ~8 KiB.
        val manager = manager(SourceFetcher(OkHttpClient(), io, maxTextBytes = 4096, maxPlaylistBytes = 1024 * 1024))
        val big = { arrayBuffer(200) { n -> """{"category_id":"$n","category_name":"Category $n"}""" } }
        val streams = { arrayBuffer(200) { n -> """{"stream_id":$n,"name":"Channel number $n"}""" } }
        assertTrue(big().size > 4096 && streams().size > 4096)
        buffers["get_live_streams"] = streams
        assertEquals(200, addLogin(manager).channelCount)
        buffers["get_live_categories"] = big
        assertTrue(refused(manager, user = "second.user").startsWith("Larger than"))
        buffers.remove("get_live_categories")
        routes[null] = 200 to SIGN_IN + " ".repeat(4096)
        assertTrue(refused(manager, user = "third.user").startsWith("Larger than"))
    }

    @Test
    fun `categories stop at MAX_CATEGORIES`() = runTest {
        routes["get_live_categories"] = 200 to (1..XtreamAdapter.MAX_CATEGORIES + 5).joinToString(",", "[", "]") {
            """{"category_id":"$it","category_name":"C$it"}"""
        }
        assertEquals(XtreamAdapter.MAX_CATEGORIES, addLogin(manager()).account!!.categories)
    }

    @Test
    @Category(LargeHeap::class)
    fun `the kept-text budget cuts a listing of long names, not refused`() = runTest {
        val name = "n".repeat(60_000)
        val count = 600 // ~36M chars of names, past M3uParser.MAX_KEPT_CHARS
        buffers["get_live_streams"] = { arrayBuffer(count) { n -> """{"stream_id":$n,"name":"$name"}""" } }
        val kept = addLogin(manager()).channelCount
        assertTrue("$kept", kept < count)
        assertTrue("$kept", kept >= M3uParser.MAX_KEPT_CHARS / (name.length + 200))
    }

    @Test
    fun `a stream's category list is read whole within the element cap`() = runTest {
        val ids = listOf("12") + List(20) { "99" } + "7"
        routes["get_live_streams"] = 200 to """[{"stream_id":1,"category_ids":[${ids.joinToString(",")}]},{"stream_id":2}]"""
        addLogin(manager())
        assertEquals(listOf("UK | Entertainment", "News"), channels.channels.value.first().groups)
        assertEquals(2, channels.channels.value.size)
    }

    @Test
    fun `an element past the byte cap is refused, a wide one under it decodes`() = runTest {
        // A JsonPrimitive field decodes the whole value before its type check (Spec 109 D8), so the
        // cap bounds every element. An unknown key would decode without it: it is skipped, not built.
        fun wide(bytes: Int) = List(bytes / 2) { "0" }.joinToString(",", "[", "]")
        val manager = manager()
        routes["get_live_streams"] = 200 to """[{"stream_id":1,"junk":${wide(XtreamAdapter.MAX_ELEMENT_BYTES)}}]"""
        assertEquals(XtreamAdapter.UNREADABLE, refused(manager))
        // The sign-in's user_info is one element too; an unreadable sign-in is no Xtream server.
        routes["get_live_streams"] = 200 to STREAMS
        routes[null] = 200 to """{"user_info":{"auth":1,"junk":${wide(XtreamAdapter.MAX_ELEMENT_BYTES)}}}"""
        assertEquals(XtreamAdapter.NOT_XTREAM, refused(manager))
        routes[null] = 200 to """{"user_info":{"auth":1,"junk":${wide(XtreamAdapter.MAX_ELEMENT_BYTES - 1024)}}}"""
        routes["get_live_streams"] = 200 to """[{"stream_id":1,"junk":${wide(XtreamAdapter.MAX_ELEMENT_BYTES - 1024)}}]"""
        assertEquals(1, addLogin(manager).channelCount)
    }

    @Test
    fun `a malformed element mid-list refuses the listing`() = runTest {
        routes["get_live_streams"] = 200 to """[{"stream_id":1,"name":"ok"},{"stream_id":{"x":1}}]"""
        assertEquals(XtreamAdapter.UNREADABLE, refused(manager()))
        routes["get_live_streams"] = 200 to """[{"stream_id":1},"""
        assertEquals(XtreamAdapter.UNREADABLE, refused(manager()))
    }

    @Test
    fun `a failed channel write rolls back the login and the record`() = runTest {
        // A non-empty directory where the channel cache goes: its write cannot land.
        File(tmp.root, "channels.json/x").mkdirs()
        val manager = manager()
        val check = manager.checkXtream(typed(), USER, PASS) as LiveSourceManager.CheckResult.Ready
        val added = manager.add(check.checked)
        assertTrue("$added", added is LiveSourceManager.AddResult.Refused)
        assertTrue(store.sources.value.isEmpty())
        assertNull(accounts.get(check.checked.source.id))
        assertTrue(accounts.all().isEmpty())
    }

    @Test
    fun `the add re-checks the duplicate a second dialog's check missed`() = runTest {
        val manager = manager()
        val first = manager.checkXtream(typed(), USER, PASS) as LiveSourceManager.CheckResult.Ready
        val second = manager.checkXtream(typed(), USER, PASS) as LiveSourceManager.CheckResult.Ready
        assertTrue(manager.add(first.checked) is LiveSourceManager.AddResult.Added)
        assertEquals(LiveSourceManager.AddResult.Refused("Already added"), manager.add(second.checked))
        assertEquals(1, store.sources.value.size)
        assertEquals(1, accounts.all().size)
    }

    @Test
    fun `a refresh records the account as the server now gives it`() = runTest {
        val manager = manager()
        val source = addLogin(manager)
        routes[null] = 200 to """{"user_info":{"auth":1,"status":"Active","exp_date":"${EXPIRES_S + 86_400}","max_connections":"4",
            "allowed_output_formats":null}}"""
        val refreshed = manager.refresh(source.id)!!
        assertEquals(XtreamAccountInfo(expiresAtMs = (EXPIRES_S + 86_400) * 1000, maxConnections = 4, categories = 2), refreshed.account)
    }

    @Test
    fun `reserved characters in the login round-trip, and the password keeps its spaces`() = runTest {
        val user = "a/b?c d"
        val pass = " p@ss#w%rd/ "
        val source = addLogin(manager(), user = user, pass = pass)
        assertEquals(XtreamLogin(base(), user, pass), accounts.get(source.id))
        val signIn = seen.first().requestUrl!!
        assertEquals(user, signIn.queryParameter("username"))
        assertEquals(pass, signIn.queryParameter("password"))
        val url = channels.channels.value.first().streams.single().url.toHttpUrl()
        assertEquals(listOf("live", user, pass, "101.ts"), url.pathSegments)
        assertTrue(XtreamUrls.isLive(url.toString()))
    }

    @Test
    fun `no sweep while sources_json is unreadable, a sweep once it is written`() = runTest {
        File(tmp.root, "sources.json").writeText("{not json")
        accounts.put("kept", XtreamLogin(base(), "kept.user", "kept-pass"))
        val manager = manager()
        manager.loadChannels()
        assertEquals(XtreamLogin(base(), "kept.user", "kept-pass"), accounts.get("kept"))
        // An add replaces the file; the next load (same process) sweeps.
        val source = addLogin(manager)
        manager.loadChannels()
        assertNull(accounts.get("kept"))
        assertEquals(XtreamLogin(base(), USER, PASS), accounts.get(source.id))
    }

    @Test
    fun `an empty list that was read sweeps every login`() = runTest {
        File(tmp.root, "sources.json").writeText("""{"version":1,"sources":[]}""")
        accounts.put("ghost", XtreamLogin(base(), "ghost.user", "ghost-pass"))
        manager().loadChannels()
        assertNull(accounts.get("ghost"))
    }

    @Test
    fun `no sources_json sweeps every login`() = runTest {
        accounts.put("ghost", XtreamLogin(base(), "ghost.user", "ghost-pass"))
        manager().loadChannels()
        assertNull(accounts.get("ghost"))
    }

    @Test
    fun `a login whose record is gone is swept at the first load`() = runTest {
        val manager = manager()
        val source = addLogin(manager)
        accounts.put("ghost", XtreamLogin(base(), "ghost.user", "ghost-pass"))
        // A new process (one sweep per manager).
        manager().loadChannels()
        assertNull(accounts.get("ghost"))
        assertEquals(XtreamLogin(base(), USER, PASS), accounts.get(source.id))
    }

    // --- S2: the login's guide, the expiry date, Edit login, pasted links ---

    @Test
    fun `a refresh caches the login's guide under its id and counts the channels it covers`() = runTest {
        guideAnswer = 200 to GUIDE
        val manager = manager()
        val source = addLogin(manager)
        assertNull(source.account!!.guideChannels) // the check stays quick: no guide yet
        val refreshed = manager.refresh(source.id)!!
        assertNull(refreshed.lastError)
        // BBCOne.uk is the login's; Other.uk is not, and counts for no login channel.
        assertEquals(1, refreshed.account!!.guideChannels)
        assertEquals(setOf("BBCOne.uk", "Other.uk"), guides.guides.value[source.id]!!.channels.keys)
        val guideCall = seen.last { it.requestUrl!!.encodedPath == "/xmltv.php" }.requestUrl!!
        assertEquals(USER, guideCall.queryParameter("username"))
        assertEquals(PASS, guideCall.queryParameter("password"))
        assertNoCredentialOutsideLogins(refreshed)
        // A cut-short guide the same day loses to the complete one, and is no error on the login.
        val cached = guides.guides.value[source.id]!!
        guideAnswer = 200 to GUIDE.substringBefore("<programme channel=\"Other.uk\"")
        val cut = manager.refresh(source.id)!!
        assertNull(cut.lastError)
        assertEquals(cached, guides.guides.value[source.id])
        // A failed guide keeps the cached guide and its count.
        guideAnswer = 404 to ""
        assertEquals(1, manager.refresh(source.id)!!.account!!.guideChannels)
        assertEquals(cached, guides.guides.value[source.id])
        assertTrue(File(tmp.root, "epg-${source.id}.json").exists())
        // A restart's load reads the login's guide back.
        val freshGuides = GuideRepository(tmp.root, io)
        manager(sourceStore = SourceStore(tmp.root, io), guideRepo = freshGuides).load()
        assertEquals(setOf("BBCOne.uk", "Other.uk"), freshGuides.guides.value[source.id]!!.channels.keys)
    }

    /** Stream URLs carry the login by design (channels.json, accounts.json); the record, its reason and guide files never do. */
    private fun assertNoCredentialOutsideLogins(source: LiveSource) {
        assertFalse("$source", source.toString().contains(USER) || source.toString().contains(PASS))
        val files = tmp.root.walkTopDown().filter { it.isFile && (it.name == "sources.json" || it.name.startsWith("epg-")) }.toList()
        assertTrue(files.any { it.name == "sources.json" })
        for (file in files) {
            val text = file.readText()
            assertFalse(file.name, text.contains(USER) || text.contains(PASS))
        }
    }

    @Test
    fun `a missing or broken guide leaves the login's status, cached guide and count alone`() = runTest {
        val manager = manager()
        val source = addLogin(manager)
        val failing = listOf(null, 500 to "$USER $PASS", 200 to "<html>$PASS</html>", 200 to "<tv>")
        // None cached yet: nothing is cached from them.
        for (answer in failing) {
            guideAnswer = answer
            val refreshed = manager.refresh(source.id)!!
            assertNull("$answer", refreshed.lastError)
            assertNull("$answer", refreshed.account!!.guideChannels)
            assertNull("$answer", guides.guides.value[source.id])
        }
        guideAnswer = 200 to GUIDE
        assertEquals(1, manager.refresh(source.id)!!.account!!.guideChannels)
        val cached = guides.guides.value[source.id]!!
        for (answer in failing) {
            guideAnswer = answer
            val refreshed = manager.refresh(source.id)!!
            assertNull("$answer", refreshed.lastError)
            assertEquals("$answer", 1, refreshed.account!!.guideChannels)
            assertEquals(3, refreshed.channelCount)
            assertEquals("$answer", cached, guides.guides.value[source.id])
            assertNoCredentialOutsideLogins(refreshed)
        }
    }

    @Test
    fun `a guide failing after midnight retries hourly, not on every open`() = runTest {
        guideAnswer = 200 to GUIDE
        val manager = manager(zone = ZoneOffset.UTC)
        val source = addLogin(manager)
        manager.refresh(source.id)
        guideAnswer = 404 to ""
        fun signIns() = seen.count { it.requestUrl!!.encodedPath == "/player_api.php" && it.requestUrl!!.queryParameter("action") == null }
        val before = signIns()
        // NOW_MS is 14:13 UTC: past the next midnight, under a day since the refresh.
        now = localDayStartMs(NOW_MS, ZoneOffset.UTC, 1) + 60_000
        manager.refreshStale()
        manager.refreshStale()
        assertEquals(before + 1, signIns())
        now += LiveSourceManager.GUIDE_MARGIN_MS
        manager.refreshStale()
        assertEquals(before + 2, signIns())
    }

    @Test
    fun `an Xtream login is stale when its cached guide no longer covers tomorrow, not for want of one`() {
        val hour = 60L * 60 * 1000
        val utc = java.time.ZoneOffset.UTC
        val source = LiveSource("x", SourceKind.XTREAM, "n", "http://h", addedAtMs = 0, lastRefreshMs = 20 * hour)
        val (start, end) = LiveSourceManager.dayWindow(20 * hour, utc)
        val window = com.nuvio.tv.livetv.parse.EpgSnapshot(emptyMap(), emptyMap(), start, end, complete = true)
        assertFalse(LiveSourceManager.isStale(source, null, 30 * hour, utc))
        assertFalse(LiveSourceManager.isStale(source, window, 23 * hour, utc))
        assertTrue(LiveSourceManager.isStale(source, window, 24 * hour, utc))
        // Refreshed after midnight with the guide still failing: retried an hour later.
        val retried = source.copy(lastRefreshMs = 24 * hour)
        assertFalse(LiveSourceManager.isStale(retried, window, 25 * hour - 1, utc))
        assertTrue(LiveSourceManager.isStale(retried, window, 25 * hour, utc))
    }

    @Test
    fun `an expired account keeps its channels and records the date the server gave`() = runTest {
        val manager = manager()
        val source = addLogin(manager)
        val ended = NOW_MS / 1000 - 3_600
        routes[null] = 200 to userInfo(expires = ended)
        val refreshed = manager.refresh(source.id)!!
        assertEquals(XtreamAdapter.EXPIRED, refreshed.lastError)
        assertEquals(ended * 1000, refreshed.account!!.expiresAtMs)
        assertEquals(3, channels.channels.value.size)
        // "Expired" with no date keeps the date it had.
        routes[null] = 200 to userInfo(status = "Expired").replace("\"exp_date\":\"$EXPIRES_S\",", "")
        assertEquals(ended * 1000, manager.refresh(source.id)!!.account!!.expiresAtMs)
    }

    @Test
    fun `edit login keeps the id, name, channel keys and guide, and moves the login`() = runTest {
        guideAnswer = 200 to GUIDE
        val manager = manager()
        val source = addLogin(manager)
        manager.refresh(source.id)
        val cached = guides.guides.value[source.id]!!
        assertTrue(manager.rename(source.id, "Living room"))
        val keys = channels.channels.value.map { it.key }
        val check = manager.checkXtreamEdit(source.id, typed(), "kamal.new", "n3w-Pa55")
        assertTrue("$check", check is LiveSourceManager.CheckResult.Ready)
        val saved = manager.saveEdit((check as LiveSourceManager.CheckResult.Ready).checked)
        assertTrue("$saved", saved is LiveSourceManager.AddResult.Added)
        val record = store.get(source.id)!!
        assertEquals("Living room", record.name)
        assertNull(record.lastError)
        assertEquals(listOf(source.id), store.sources.value.map { it.id })
        assertEquals(XtreamLogin(base(), "kamal.new", "n3w-Pa55"), accounts.get(source.id))
        assertEquals(keys, channels.channels.value.map { it.key })
        assertEquals("${base()}/live/kamal.new/n3w-Pa55/101.ts", channels.channels.value.first().streams.single().url)
        assertEquals("kamal.new", manager.xtreamUsername(source.id))
        // Same server: the guide and its count stay.
        assertEquals(1, record.account!!.guideChannels)
        assertEquals(cached, guides.guides.value[source.id])
    }

    @Test
    fun `edit login to another server needs the password, drops the old guide, then reads the new one`() = runTest {
        guideAnswer = 200 to GUIDE
        val manager = manager()
        val source = addLogin(manager)
        manager.refresh(source.id)
        val moved = MockWebServer()
        moved.dispatcher = dispatcher()
        moved.start()
        try {
            val typed = "${moved.hostName}:${moved.port}"
            // The stored password goes only to its own server.
            assertEquals(
                LiveSourceManager.CheckResult.Refused(LiveSourceManager.XTREAM_RETYPE_PASSWORD),
                manager.checkXtreamEdit(source.id, typed, USER, null)
            )
            assertEquals(0, moved.requestCount)
            // An unreadable or blank server with the stored password: refused before any request.
            val requests = seen.size
            assertEquals(
                LiveSourceManager.CheckResult.Refused("Not a web address"),
                manager.checkXtreamEdit(source.id, "not a host at all", USER, null)
            )
            assertEquals(
                LiveSourceManager.CheckResult.Refused(LiveSourceManager.XTREAM_FILL_IN),
                manager.checkXtreamEdit(source.id, " ", USER, null)
            )
            assertEquals(requests, seen.size)
            val before = store.get(source.id)!!
            val check = manager.checkXtreamEdit(source.id, typed, USER, PASS) as LiveSourceManager.CheckResult.Ready
            // A failed save keeps the old login, record, guide and count.
            val blocker = File(tmp.root, "channels.json.tmp").also { File(it, "x").mkdirs() }
            assertTrue(manager.saveEdit(check.checked) is LiveSourceManager.AddResult.Refused)
            assertEquals(XtreamLogin(base(), USER, PASS), accounts.get(source.id))
            assertEquals(before, store.get(source.id))
            assertEquals(1, before.account!!.guideChannels)
            assertTrue(File(tmp.root, "epg-${source.id}.json").exists())
            assertNotNull(guides.guides.value[source.id])
            blocker.deleteRecursively()
            assertTrue(manager.saveEdit(check.checked) is LiveSourceManager.AddResult.Added)
            val record = store.get(source.id)!!
            assertEquals("http://${moved.hostName}:${moved.port}", record.url)
            assertNull(record.account!!.guideChannels)
            assertNull(guides.guides.value[source.id])
            assertFalse(File(tmp.root, "epg-${source.id}.json").exists())
            // The guide alone, from the new server; the channels are not listed again.
            val listings = moved.requestCount
            now += 1_000
            assertEquals(1, manager.refreshGuide(source.id)!!.account!!.guideChannels)
            assertEquals(1, store.get(source.id)!!.account!!.guideChannels)
            assertEquals(listings + 1, moved.requestCount)
            assertEquals("/xmltv.php", seen.last().requestUrl!!.encodedPath)
            assertEquals(moved.port, seen.last().requestUrl!!.port)
            assertEquals(record.lastRefreshMs, store.get(source.id)!!.lastRefreshMs)
        } finally {
            moved.shutdown()
        }
    }

    @Test
    fun `edit login with the password unchanged signs in with the stored one`() = runTest {
        val manager = manager()
        val source = addLogin(manager)
        val check = manager.checkXtreamEdit(source.id, typed(), USER, null)
        assertTrue("$check", check is LiveSourceManager.CheckResult.Ready)
        assertEquals(PASS, seen.last().requestUrl!!.queryParameter("password"))
        // No stored login (a restored list): the password must be typed.
        accounts.remove(source.id)
        assertEquals(
            LiveSourceManager.CheckResult.Refused(LiveSourceManager.XTREAM_FILL_IN),
            manager.checkXtreamEdit(source.id, typed(), USER, null)
        )
        assertTrue(manager.checkXtreamEdit(source.id, typed(), USER, PASS) is LiveSourceManager.CheckResult.Ready)
    }

    @Test
    fun `edit login refuses another login's server and username, a refused sign-in and a removed source`() = runTest {
        val manager = manager()
        val first = addLogin(manager)
        val second = addLogin(manager, user = "guest.room")
        assertEquals(
            LiveSourceManager.CheckResult.Refused("Already added"),
            manager.checkXtreamEdit(second.id, typed(), USER, null)
        )
        routes[null] = 401 to ""
        assertEquals(
            LiveSourceManager.CheckResult.Refused(XtreamAdapter.LOGIN_REFUSED),
            manager.checkXtreamEdit(first.id, typed(), USER, "wrong")
        )
        assertEquals(XtreamLogin(base(), USER, PASS), accounts.get(first.id))
        routes[null] = 200 to SIGN_IN
        // Another login took the edited server and username after the check: the save re-checks.
        val taken = manager.checkXtreamEdit(first.id, typed(), "third.user", "p") as LiveSourceManager.CheckResult.Ready
        addLogin(manager, user = "third.user")
        assertEquals(LiveSourceManager.AddResult.Refused("Already added"), manager.saveEdit(taken.checked))
        assertEquals(XtreamLogin(base(), USER, PASS), accounts.get(first.id))
        val check = manager.checkXtreamEdit(first.id, typed(), USER, "n3w-Pa55") as LiveSourceManager.CheckResult.Ready
        assertTrue(manager.remove(first.id))
        assertEquals(LiveSourceManager.AddResult.Refused(LiveSourceManager.SOURCE_GONE), manager.saveEdit(check.checked))
        assertNull(accounts.get(first.id))
    }

    @Test
    fun `a failed channel write during an edit restores the old login and record`() = runTest {
        val manager = manager()
        val source = addLogin(manager)
        now += 1_000
        val check = manager.checkXtreamEdit(source.id, typed(), USER, "n3w-Pa55") as LiveSourceManager.CheckResult.Ready
        // A non-empty directory where the channel cache's temp file goes: its write cannot land.
        File(tmp.root, "channels.json.tmp/x").mkdirs()
        val saved = manager.saveEdit(check.checked)
        assertTrue("$saved", saved is LiveSourceManager.AddResult.Refused)
        assertEquals(XtreamLogin(base(), USER, PASS), accounts.get(source.id))
        assertEquals(source, store.get(source.id))
        assertEquals("${base()}/live/$USER/$PASS/101.ts", channels.channels.value.first().streams.single().url)
        // No login on this TV before the edit (a restored list): none after the failed one.
        accounts.remove(source.id)
        val typedIn = manager.checkXtreamEdit(source.id, typed(), USER, "n3w-Pa55") as LiveSourceManager.CheckResult.Ready
        assertTrue(manager.saveEdit(typedIn.checked) is LiveSourceManager.AddResult.Refused)
        assertNull(accounts.get(source.id))
        assertEquals(source, store.get(source.id))
    }

    @Test
    fun `a failed record write during an edit changes nothing`() = runTest {
        val manager = manager()
        val source = addLogin(manager)
        now += 1_000
        val check = manager.checkXtreamEdit(source.id, typed(), USER, "n3w-Pa55") as LiveSourceManager.CheckResult.Ready
        File(tmp.root, "sources.json.tmp/x").mkdirs()
        val saved = manager.saveEdit(check.checked)
        assertTrue("$saved", saved is LiveSourceManager.AddResult.Refused)
        assertEquals(XtreamLogin(base(), USER, PASS), accounts.get(source.id))
        assertEquals(source, store.get(source.id))
        assertEquals("${base()}/live/$USER/$PASS/101.ts", channels.channels.value.first().streams.single().url)
    }

    @Test
    fun `a pasted get_php link gives its login, other links none`() {
        assertEquals(
            XtreamLogin("http://tv.example.net:8080", "kamal.home", "s3cr3t Pa55"),
            XtreamAdapter.linkLogin(" http://tv.example.net:8080/get.php?username=kamal.home&password=s3cr3t%20Pa55&type=m3u_plus&output=ts ")
        )
        assertEquals(
            XtreamLogin("http://tv.example.net/panel", "a", "b"),
            XtreamAdapter.linkLogin("tv.example.net/panel/GET.PHP?username=a&password=b")
        )
        assertNull(XtreamAdapter.linkLogin("http://tv.example.net/get.php?username=a"))
        assertNull(XtreamAdapter.linkLogin("http://tv.example.net/get.php?username=%20&password=b"))
        assertNull(XtreamAdapter.linkLogin("http://tv.example.net/player_api.php?username=a&password=b"))
        assertNull(XtreamAdapter.linkLogin("http://tv.example.net/list.m3u"))
        assertNull(XtreamAdapter.linkLogin("not a link"))
    }

    private fun link(pass: String = PASS) = "${base()}/get.php?username=$USER&password=$pass&type=m3u_plus&output=ts"

    @Test
    fun `a pasted get_php link signs in as Xtream first and adds as a login`() = runTest {
        val manager = manager()
        val check = manager.checkLink(link()) as LiveSourceManager.CheckResult.Ready
        assertEquals(SourceKind.XTREAM, check.checked.source.kind)
        assertEquals(base(), check.checked.source.url)
        assertEquals(3, check.checked.listed)
        assertTrue(seen.none { it.requestUrl!!.encodedPath == "/get.php" })
        val added = manager.add(check.checked) as LiveSourceManager.AddResult.Added
        assertEquals(XtreamLogin(base(), USER, PASS), accounts.get(added.source.id))
        // Not a get.php link with a login: the playlist check alone, no sign-in.
        seen.clear()
        val plain = manager.checkLink("${base()}/get.php?username=$USER") as LiveSourceManager.CheckResult.Ready
        assertEquals(SourceKind.M3U, plain.checked.source.kind)
        assertTrue(seen.none { it.requestUrl!!.encodedPath == "/player_api.php" })
    }

    @Test
    fun `a refused sign-in falls back to the playlist check, its reason dropped`() = runTest {
        val manager = manager()
        for (answer in listOf(401 to "$USER $PASS", 404 to "", 200 to userInfo(status = "Expired"))) {
            routes[null] = answer
            seen.clear()
            val check = manager.checkLink(link()) as LiveSourceManager.CheckResult.Ready
            assertEquals("$answer", SourceKind.M3U, check.checked.source.kind)
            assertEquals(2, check.checked.listed)
            assertEquals(listOf("/player_api.php", "/get.php"), seen.map { it.requestUrl!!.encodedPath })
            assertNull(check.checked.source.lastError)
        }
        // The playlist adds as today; the same link again, its server still refusing, is the playlist it is.
        val check = manager.checkLink(link()) as LiveSourceManager.CheckResult.Ready
        assertTrue(manager.add(check.checked) is LiveSourceManager.AddResult.Added)
        assertEquals("Already added", (manager.checkLink(link()) as LiveSourceManager.CheckResult.Refused).reason)
        assertTrue(File(tmp.root, "accounts.json").let { !it.exists() || !it.readText().contains(PASS) })
        // Once the server signs in, the stored playlist's link is offered as a login (Q5: the upgrade on paste).
        routes[null] = 200 to SIGN_IN
        val upgrade = manager.checkLink(link()) as LiveSourceManager.CheckResult.Ready
        assertEquals(SourceKind.XTREAM, upgrade.checked.source.kind)
    }

    @Test
    fun `both refused gives the playlist check's reason, never the login`() = runTest {
        routes[null] = 401 to "bad $USER / $PASS"
        linkAnswer = 403 to "Forbidden $USER $PASS"
        val reason = (manager().checkLink(link()) as LiveSourceManager.CheckResult.Refused).reason
        assertEquals("HTTP 403", reason)
        assertFalse(reason, reason.contains(USER) || reason.contains(PASS))
        assertEquals(listOf("/player_api.php", "/get.php"), seen.map { it.requestUrl!!.encodedPath })
    }

    /**
     * Spec 109 S2.8 security sweep: every failing guide, refresh, Edit login, save and paste answer
     * echoes the login back, and a network failure's message quotes the request URL; no refusal
     * reason, lastError, log line, the login's record, sources.json or epg-*.json carries a username
     * or password (only the login store and the stream URLs in channels.json do; a get.php link
     * added as a playlist keeps its URL in its own record, as any playlist).
     */
    @Test
    fun `security sweep - failing guide, edit, save and paste answers never put the login outside the login store`() = runTest {
        val logs = java.util.Collections.synchronizedList(mutableListOf<String>())
        mockkStatic(Log::class)
        // Every overload: an unstubbed one would fall through to the android.jar stub unseen. A
        // Throwable is recorded with its message and cause (logcat prints them).
        fun thrown(t: Throwable?) = t?.let { " $it ${it.cause}" }.orEmpty()
        every { Log.v(any(), any()) } answers { logs += secondArg<String>(); 0 }
        every { Log.v(any(), any(), any()) } answers { logs += secondArg<String>() + thrown(thirdArg()); 0 }
        every { Log.d(any(), any()) } answers { logs += secondArg<String>(); 0 }
        every { Log.d(any(), any(), any()) } answers { logs += secondArg<String>() + thrown(thirdArg()); 0 }
        every { Log.i(any(), any()) } answers { logs += secondArg<String>(); 0 }
        every { Log.i(any(), any(), any()) } answers { logs += secondArg<String>() + thrown(thirdArg()); 0 }
        every { Log.w(any(), any<String>()) } answers { logs += secondArg<String>(); 0 }
        every { Log.w(any(), any<String>(), any()) } answers { logs += secondArg<String>() + thrown(thirdArg()); 0 }
        every { Log.w(any(), any<Throwable>()) } answers { logs += thrown(secondArg()); 0 }
        every { Log.e(any(), any()) } answers { logs += secondArg<String>(); 0 }
        every { Log.e(any(), any(), any()) } answers { logs += secondArg<String>() + thrown(thirdArg()); 0 }
        every { Log.wtf(any(), any<String>()) } answers { logs += secondArg<String>(); 0 }
        every { Log.wtf(any(), any<Throwable>()) } answers { logs += thrown(secondArg()); 0 }
        every { Log.wtf(any(), any(), any()) } answers { logs += secondArg<String>() + thrown(thirdArg()); 0 }
        every { Log.println(any(), any(), any()) } answers { logs += thirdArg<String>(); 0 }
        try {
            sweep(logs)
        } finally {
            unmockkStatic(Log::class)
        }
    }

    private suspend fun sweep(logs: List<String>) {
        val secrets = listOf(USER, PASS, NEW_PASS, GUEST)
        val echo = "$USER $PASS $NEW_PASS $GUEST"
        val manager = manager()
        val source = addLogin(manager)
        guideAnswer = 200 to GUIDE
        manager.refresh(source.id)
        val shown = mutableListOf<String>()

        // The guide (xmltv.php) failing: kept quiet (no lastError), only a class-name log line.
        for (answer in listOf(500 to echo, 200 to "<html>$echo</html>", 200 to "<tv>", 401 to echo)) {
            guideAnswer = answer
            assertNull("$answer", manager.refresh(source.id)!!.lastError)
        }
        assertTrue(logs.any { "xtream guide" in it })
        guideAnswer = 200 to GUIDE
        // The login's own refresh refused, unreadable or failing: every one records a reason.
        for (answer in listOf(403 to echo, 200 to """{"user_info":{"auth":0,"message":"$echo"}}""", 500 to echo,
            200 to "<html>$echo</html>")) {
            routes[null] = answer
            val error = manager.refresh(source.id)!!.lastError
            assertNotNull("$answer", error)
            shown += error!!
        }
        // Edit login: a new password refused or failing, an unreachable server; a moved server keeping
        // the stored password is refused before any request.
        for (answer in listOf(401 to echo, 500 to echo, 200 to userInfo(status = "Expired"), 200 to """{"user_info":"$echo"}""")) {
            routes[null] = answer
            shown += (manager.checkXtreamEdit(source.id, typed(), USER, NEW_PASS) as LiveSourceManager.CheckResult.Refused).reason
        }
        shown += (manager.checkXtreamEdit(source.id, "127.0.0.1:1", USER, NEW_PASS) as LiveSourceManager.CheckResult.Refused).reason
        shown += (manager.checkXtreamEdit(source.id, "127.0.0.1:1", USER, null) as LiveSourceManager.CheckResult.Refused).reason
        // A save whose channel write fails: refused, the old login and record restored.
        routes[null] = 200 to SIGN_IN
        val edit = manager.checkXtreamEdit(source.id, typed(), USER, NEW_PASS) as LiveSourceManager.CheckResult.Ready
        val blocker = File(tmp.root, "channels.json.tmp").also { File(it, "x").mkdirs() }
        shown += (manager.saveEdit(edit.checked) as LiveSourceManager.AddResult.Refused).reason
        blocker.deleteRecursively()
        // A pasted link: the sign-in and the playlist both failing, and an unreadable playlist.
        val pasted = "${base()}/get.php?username=$GUEST&password=$NEW_PASS&type=m3u_plus"
        for (playlist in listOf(403 to echo, 200 to "<html>$echo</html>", 500 to echo)) {
            routes[null] = 401 to echo
            linkAnswer = playlist
            shown += (manager.checkLink(pasted) as LiveSourceManager.CheckResult.Refused).reason
        }
        // A network failure whose message quotes the request URL (login in its query or path).
        routes[null] = 200 to SIGN_IN
        val quoted = java.util.Collections.synchronizedList(mutableListOf<String>())
        val quoting = OkHttpClient.Builder().addInterceptor {
            quoted += it.request().url.encodedPath
            throw java.io.IOException(it.request().url.toString())
        }.build()
        val offline = manager(fetcher = SourceFetcher(quoting, io))
        val offlineAnswers = listOf(
            offline.refresh(source.id)!!.lastError!!,
            (offline.checkXtreamEdit(source.id, typed(), USER, NEW_PASS) as LiveSourceManager.CheckResult.Refused).reason,
            (offline.checkLink(pasted) as LiveSourceManager.CheckResult.Refused).reason
        )
        assertEquals(List(3) { "Network error (IOException)" }, offlineAnswers)
        shown += offlineAnswers
        val guideLogs = logs.count { "xtream guide" in it }
        assertNotNull(offline.refreshGuide(source.id))
        assertEquals(guideLogs + 1, logs.count { "xtream guide" in it })
        // Each leg reached the network: sign-ins, the paste's playlist fallback and the guide.
        assertTrue("$quoted", quoted.containsAll(listOf("/player_api.php", "/get.php", "/xmltv.php")))

        assertEquals(17, shown.size)
        for (text in shown + logs) {
            assertFalse(text, secrets.any { it in text })
        }
        val record = store.sources.value.single()
        assertFalse("$record", secrets.any { it in record.toString() })
        assertEquals(XtreamLogin(base(), USER, PASS), accounts.get(source.id)) // nothing failing was stored
        val files = tmp.root.walkTopDown().filter { it.isFile && (it.name == "sources.json" || it.name.startsWith("epg-")) }.toList()
        assertTrue(files.map { it.name }.toSet().containsAll(setOf("sources.json", "epg-${source.id}.json")))
        for (file in files) {
            val text = file.readText()
            assertFalse(file.name, secrets.any { it in text })
        }
    }

    @Test
    fun `a link without a scheme is no web address and sends no sign-in`() = runTest {
        val reason = (manager().checkLink("${server.hostName}:${server.port}/get.php?username=$USER&password=$PASS")
            as LiveSourceManager.CheckResult.Refused).reason
        assertEquals("Not a web address", reason)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `a link whose login is already added is Already added only, no playlist request`() = runTest {
        val manager = manager()
        addLogin(manager)
        seen.clear()
        // Another password for the same server and username is still that login (D15).
        val check = manager.checkLink(link(pass = "other"))
        assertEquals("Already added", (check as LiveSourceManager.CheckResult.Refused).reason)
        assertTrue(seen.isEmpty())
    }

    private companion object {
        const val USER = "kamal.home"
        const val PASS = "s3cr3t-Pa55"
        const val NEW_PASS = "n3w-Pa55-x9"
        const val GUEST = "guest.room"
        const val NOW_MS = 1_790_000_000_000L
        const val EXPIRES_S = 1_803_945_600L // 1 Mar 2027

        /** What a panel's `get.php` serves: the account as an M3U. */
        val LINK_PLAYLIST = "#EXTM3U\n#EXTINF:-1 tvg-id=\"BBCOne.uk\",BBC One HD\nhttp://tv.example.net/live/a/b/101.ts\n" +
            "#EXTINF:-1,ITV1\nhttp://tv.example.net/live/a/b/102.ts\n"

        fun userInfo(status: String = "Active", expires: Long = EXPIRES_S) =
            """{"user_info":{"auth":1,"status":"$status","exp_date":"$expires","max_connections":"2"}}"""

        val SIGN_IN = """{"user_info":{"username":"$USER","password":"$PASS","message":"","auth":1,"status":"Active",
            "exp_date":"$EXPIRES_S","is_trial":"0","active_cons":"0","created_at":"1700000000","max_connections":"2",
            "allowed_output_formats":["m3u8","ts","rtmp"]},
            "server_info":{"url":"tv.example.net","port":"8080","https_port":"8443","server_protocol":"http",
            "rtmp_port":"8880","timezone":"Europe/London","timestamp_now":1790000000,"time_now":"2026-09-21 12:53:20"}}"""

        private fun xt(ms: Long) = java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z")
            .format(java.time.Instant.ofEpochMilli(ms).atOffset(java.time.ZoneOffset.UTC))

        /** The server's `xmltv.php`: one of the login's channels and one it does not list, on air at NOW_MS. */
        val GUIDE = """<?xml version="1.0" encoding="UTF-8"?>
            <tv generator-info-name="panel"><channel id="BBCOne.uk"><display-name>BBC One HD</display-name></channel>
            <channel id="Other.uk"><display-name>Other</display-name></channel>
            <programme channel="BBCOne.uk" start="${xt(NOW_MS - 3_600_000)}" stop="${xt(NOW_MS + 3_600_000)}"><title>News</title></programme>
            <programme channel="Other.uk" start="${xt(NOW_MS - 3_600_000)}" stop="${xt(NOW_MS + 3_600_000)}"><title>Film</title></programme>
            </tv>"""

        val CATEGORIES = """[{"category_id":"12","category_name":"UK | Entertainment","parent_id":0},
            {"category_id":"7","category_name":"News","parent_id":0}]"""

        val STREAMS = """[
            {"num":1,"name":"BBC One HD","stream_type":"live","stream_id":101,"stream_icon":"http://logos.example.net/bbc1.png",
             "epg_channel_id":"BBCOne.uk","added":"1700000000","category_id":"12","category_ids":null,"custom_sid":"","tv_archive":1,
             "direct_source":"","tv_archive_duration":7},
            {"num":"2","name":"Sky News","stream_type":"live","stream_id":"102","stream_icon":"","epg_channel_id":null,
             "category_id":"12","category_ids":[12,7,99]},
            {"num":3,"name":"","stream_type":"live","stream_id":205,"stream_icon":null,"epg_channel_id":"","category_id":"99"},
            {"num":4,"name":"Duplicate of 101","stream_type":"live","stream_id":101},
            {"num":5,"name":"No id","stream_type":"live"},
            {"num":6,"name":"Bad id","stream_type":"live","stream_id":"../../etc"}
        ]"""
    }
}
