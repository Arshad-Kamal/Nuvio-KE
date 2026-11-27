package com.nuvio.tv.core.server

import com.nuvio.tv.data.local.UserSubtitleFont
import com.nuvio.tv.data.local.UserSubtitleFontAddResult
import com.nuvio.tv.data.local.UserSubtitleFontStore
import android.content.Context
import fi.iki.elonen.NanoHTTPD
import io.mockk.every
import io.mockk.mockk
import fi.iki.elonen.NanoHTTPD.Response.Status
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 132 S2: the font page's token, guard, POST order, size cap, one upload at a time, result words. */
class SubtitleFontServerTest {

    private val token = "0123456789abcdef0123456789abcdef"
    private val upload = ByteArray(64) { it.toByte() }
    private val font = UserSubtitleFont("font-1-x", "Montserrat Medium", "M.otf", 64, Instant.fromEpochMilliseconds(1), File("x"))

    private var count = 0
    private var answer: UserSubtitleFontAddResult = UserSubtitleFontAddResult.Added(font)
    private var onAdd: () -> Unit = {}
    private val added = mutableListOf<Pair<String, ByteArray>>()

    private fun newServer() = SubtitleFontServer(
        token = token,
        pageHtml = { "<html>page</html>" },
        fontCount = { count },
        addFont = { name, bytes ->
            onAdd()
            added += name to bytes
            if (answer is UserSubtitleFontAddResult.Added) count++
            answer
        },
        hostname = null,
        port = 0
    )

    private fun session(
        name: String? = "Montserrat-Medium.otf",
        body: ByteArray = upload,
        declaredLength: String? = body.size.toString(),
        path: String = "/f/$token/font",
        origin: String? = null,
        failAfter: Int? = null
    ) = FakeUploadSession(
        path, kind = null, body = body, declaredLength = declaredLength, origin = origin, failAfter = failAfter,
        extraParameters = name?.let { mapOf("name" to it) }.orEmpty()
    )

    private fun post(server: SubtitleFontServer, session: FakeUploadSession = session()): NanoHTTPD.Response =
        server.serve(session)

    private fun get(server: SubtitleFontServer, path: String, host: String = FAKE_HOST): NanoHTTPD.Response =
        server.serve(FakeUploadSession(path, method = NanoHTTPD.Method.GET, kind = null, host = host))

    private fun word(response: NanoHTTPD.Response): String = json(response)["result"]!!.jsonPrimitive.content
    private fun json(response: NanoHTTPD.Response) = Json.parseToJsonElement(response.bodyText()).jsonObject

    @Test
    fun `the page answers only under its token`() {
        val server = newServer()
        val page = get(server, "/f/$token/")
        assertEquals(Status.OK, page.status)
        assertEquals("<html>page</html>", page.bodyText())
        assertEquals(Status.OK, get(server, "/f/$token").status)
        assertEquals(Status.NOT_FOUND, get(server, "/f/ffffffffffffffffffffffffffffffff/").status)
        assertEquals(Status.NOT_FOUND, get(server, "/f/${token}0/").status)
        assertEquals(Status.NOT_FOUND, get(server, "/f/$token/font").status)
        assertEquals(Status.NOT_FOUND, get(server, "/").status)
        assertTrue(added.isEmpty())
    }

    @Test
    fun `every answer is uncached, sends no referrer, and the page allows only a blob font`() {
        val server = newServer()
        listOf(get(server, "/f/$token/"), get(server, "/nope"), post(server)).forEach { response ->
            assertEquals("no-store", response.getHeader("Cache-Control"))
            assertEquals("no-referrer", response.getHeader("Referrer-Policy"))
        }
        val csp = get(server, "/f/$token/").getHeader("Content-Security-Policy").orEmpty().split(';').map { it.trim() }
        listOf(
            "default-src 'none'", "script-src 'unsafe-inline'", "style-src 'unsafe-inline'", "font-src blob:",
            "connect-src 'self'", "base-uri 'none'", "form-action 'none'", "frame-ancestors 'none'"
        ).forEach { assertTrue(it, it in csp) }
        assertEquals(8, csp.size)
    }

    @Test
    fun `the guard runs before the token`() {
        val server = newServer()
        assertEquals(Status.FORBIDDEN, get(server, "/f/$token/", host = "evil.example").status)
        assertEquals(Status.FORBIDDEN, post(server, session(origin = "http://evil.example")).status)
        assertEquals(Status.FORBIDDEN, post(server, session(path = "/f/wrong/font", origin = "http://evil.example")).status)
        assertTrue(added.isEmpty())
    }

    @Test
    fun `a sent font reaches the store as sent and answers its family and count`() {
        count = 2
        val response = post(newServer())
        assertEquals(Status.OK, response.status)
        val body = json(response)
        assertEquals(SubtitleFontServer.SAVED, body["result"]!!.jsonPrimitive.content)
        assertEquals("Montserrat Medium", body["family"]!!.jsonPrimitive.content)
        assertEquals(3, body["count"]!!.jsonPrimitive.int)
        assertEquals(UserSubtitleFontStore.CAP, body["cap"]!!.jsonPrimitive.int)
        val (name, bytes) = added.single()
        assertEquals("Montserrat-Medium.otf", name)
        assertArrayEquals(upload, bytes)
    }

    @Test
    fun `each store result maps to its word and status`() {
        val server = newServer()
        fun check(result: UserSubtitleFontAddResult, status: NanoHTTPD.Response.IStatus, expected: String) {
            answer = result
            val response = post(server)
            assertEquals(status.requestStatus, response.status.requestStatus)
            assertEquals(expected, word(response))
        }
        check(UserSubtitleFontAddResult.AlreadyThere(font), Status.OK, SubtitleFontServer.ALREADY)
        check(UserSubtitleFontAddResult.Full, Status.CONFLICT, SubtitleFontServer.FULL)
        check(UserSubtitleFontAddResult.TooBig, Status.PAYLOAD_TOO_LARGE, SubtitleFontServer.TOO_BIG)
        check(UserSubtitleFontAddResult.NotAFont, UnprocessableEntity, SubtitleFontServer.NOT_A_FONT)
        check(UserSubtitleFontAddResult.Failed, Status.INTERNAL_ERROR, SubtitleFontServer.FAILED)
    }

    @Test
    fun `a full TV is refused before reading and the body is drained`() {
        count = UserSubtitleFontStore.CAP
        val server = newServer()
        val full = session()
        val response = post(server, full)
        assertEquals(Status.CONFLICT, response.status)
        assertEquals(SubtitleFontServer.FULL, word(response))
        assertEquals(0, full.stream.available())
        assertTrue(added.isEmpty())
    }

    @Test
    fun `a missing or overlong name is refused before reading`() {
        val server = newServer()
        val unnamed = session(name = null)
        assertEquals(Status.BAD_REQUEST, post(server, unnamed).status)
        assertEquals(0, unnamed.stream.available())
        assertEquals(Status.BAD_REQUEST, post(server, session(name = " ")).status)
        assertEquals(Status.BAD_REQUEST, post(server, session(name = "a".repeat(1025) + ".ttf")).status)
        // The name is checked before Content-Length: no 411 / 413 for an unnamed send.
        assertEquals(Status.BAD_REQUEST, post(server, session(name = null, declaredLength = null)).status)
        val unnamedTooBig = session(name = null, declaredLength = (UserSubtitleFontStore.MAX_BYTES + 1).toString())
        assertEquals(Status.BAD_REQUEST, post(server, unnamedTooBig).status)
        assertTrue(added.isEmpty())
        assertEquals(Status.OK, post(server).status)
    }

    @Test
    fun `length rules answer 411, 413 and 400 and free the upload`() {
        val server = newServer()
        assertEquals(Status.LENGTH_REQUIRED, post(server, session(declaredLength = null)).status)
        assertEquals(Status.LENGTH_REQUIRED, post(server, session(declaredLength = "-1")).status)
        val tooBig = post(server, session(declaredLength = (UserSubtitleFontStore.MAX_BYTES + 1).toString()))
        assertEquals(Status.PAYLOAD_TOO_LARGE, tooBig.status)
        assertEquals(SubtitleFontServer.TOO_BIG, word(tooBig))
        assertEquals(Status.BAD_REQUEST, post(server, session(declaredLength = (upload.size + 10).toString())).status)
        assertEquals(Status.BAD_REQUEST, post(server, session(failAfter = 10)).status)
        assertTrue(added.isEmpty())
        assertEquals(Status.OK, post(server).status)
    }

    @Test
    fun `a POST under another token answers 404 after draining its body`() {
        val stale = session(path = "/f/ffffffffffffffffffffffffffffffff/font")
        val response = post(newServer(), stale)
        assertEquals(Status.NOT_FOUND, response.status)
        assertEquals(SubtitleFontServer.EXPIRED, word(response))
        assertEquals(0, stale.stream.available())
        assertEquals("close", response.getHeader("Connection"))
    }

    @Test
    fun `a second upload is refused while the first is being saved`() {
        val server = newServer()
        val saving = CountDownLatch(1)
        val finish = CountDownLatch(1)
        onAdd = {
            saving.countDown()
            finish.await(5, TimeUnit.SECONDS)
        }
        var first: NanoHTTPD.Response? = null
        val thread = Thread { first = post(server) }.apply { start() }
        assertTrue(saving.await(5, TimeUnit.SECONDS))
        onAdd = {}
        val busy = session()
        assertEquals(SubtitleFontServer.BUSY, word(post(server, busy)))
        assertEquals(0, busy.stream.available())
        finish.countDown()
        thread.join(5_000)
        assertEquals(Status.OK, first?.status)
        assertEquals(1, added.size)
    }

    @Test
    fun `every POST answer closes the connection`() {
        val server = newServer()
        assertEquals("close", post(server).getHeader("Connection"))
        assertNull(get(server, "/f/$token/").getHeader("Connection"))
    }

    @Test
    fun `the QR address carries the bound port and token`() {
        val server = SubtitleFontServer(token, { "" }, { 0 }, { _, _ -> answer }, hostname = "127.0.0.1", port = 0)
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        try {
            assertTrue(server.listeningPort > 0)
            assertEquals("http://192.0.2.119:${server.listeningPort}/f/$token/", server.url("192.0.2.119"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `a store that throws answers failed and frees the upload`() {
        val server = newServer()
        onAdd = { throw SecurityException("no access") }
        val response = post(server)
        assertEquals(Status.INTERNAL_ERROR, response.status)
        assertEquals(SubtitleFontServer.FAILED, word(response))
        onAdd = {}
        assertEquals(Status.OK, post(server).status)
    }

    @Test
    fun `the page escapes every text, leaves no template behind and handles every word`() {
        val context = mockk<Context>(relaxed = true) {
            every { getString(any()) } returns "it's"
        }
        val html = SubtitleFontWebPage.html(context)
        assertNoAbsoluteSameOriginPath(html)
        assertFalse("an unescaped quote", "'it's'" in html)
        assertTrue("it\\'s" in html)
        assertFalse("a Kotlin template left in", "\${" in html)
        // The placeholder pattern reaches the page as a JS regex with a literal dollar.
        assertTrue("/%(\\d)\\${'$'}[sd]/g" in html)
        assertTrue("const MAX_BYTES = ${UserSubtitleFontStore.MAX_BYTES};" in html)
        assertTrue("CAP = ${UserSubtitleFontStore.CAP}," in html)
        listOf(
            SubtitleFontServer.SAVED, SubtitleFontServer.ALREADY, SubtitleFontServer.FULL, SubtitleFontServer.TOO_BIG,
            SubtitleFontServer.NOT_A_FONT, SubtitleFontServer.BUSY
        ).forEach { assertTrue(it, "case '$it':" in html) }
        // Untrusted values reach the page only through textContent.
        assertFalse("innerHTML" in html)
        // Every data-t key names an entry of T (a missing one would show "undefined").
        val keys = Regex("""^\s+(\w+): '""", RegexOption.MULTILINE).findAll(html.substringAfter("const T = {").substringBefore("};"))
            .map { it.groupValues[1] }.toSet()
        val used = Regex("""data-t="(\w+)"""").findAll(html).map { it.groupValues[1] }.toSet()
        assertTrue(used.isNotEmpty())
        assertTrue("data-t keys missing from T: ${used - keys}", keys.containsAll(used))
    }
}
