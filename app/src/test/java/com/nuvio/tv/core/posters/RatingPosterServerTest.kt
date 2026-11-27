package com.nuvio.tv.core.posters

import android.content.Context
import com.nuvio.tv.core.server.FAKE_HOST
import com.nuvio.tv.core.server.FakeUploadSession
import com.nuvio.tv.core.server.assertNoAbsoluteSameOriginPath
import com.nuvio.tv.core.server.bodyText
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import io.mockk.every
import io.mockk.mockk
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Spec 114 S3: the phone page's token, guard, body rules and answers (never the address), and its TV-side lifecycle. */
class RatingPosterServerTest {

    private val token = "0123456789abcdef0123456789abcdef"
    private val sent = mutableListOf<String>()
    private var verdict: RatingPosterCheck = RatingPosterCheck.Saved("posters.example.com", isHttp = false)

    private fun newServer(submit: (RatingPosterServer, String) -> RatingPosterCheck = { _, address -> sent += address; verdict }) =
        RatingPosterServer(token, { "<html>page</html>" }, submit, "127.0.0.1", 0, stopGraceMs = 0, stopWaitMs = 5_000)

    private fun post(
        server: RatingPosterServer,
        body: String = ADDRESS,
        declaredLength: String? = body.toByteArray().size.toString(),
        path: String = "/r/$token/address",
        origin: String? = null,
    ): NanoHTTPD.Response = server.serve(
        FakeUploadSession(path, kind = null, body = body.toByteArray(), declaredLength = declaredLength, origin = origin)
    )

    private fun get(server: RatingPosterServer, path: String, host: String = FAKE_HOST): NanoHTTPD.Response =
        server.serve(FakeUploadSession(path, method = NanoHTTPD.Method.GET, kind = null, host = host))

    private fun json(response: NanoHTTPD.Response) = Json.parseToJsonElement(response.bodyText()).jsonObject

    @Test
    fun `the page answers only under its token, uncached, with the inline-only CSP`() {
        val server = newServer()
        val page = get(server, "/r/$token/")
        assertEquals(Status.OK, page.status)
        assertEquals("<html>page</html>", page.bodyText())
        assertEquals("no-store", page.getHeader("Cache-Control"))
        assertEquals("no-referrer", page.getHeader("Referrer-Policy"))
        assertTrue(page.getHeader("Content-Security-Policy").orEmpty().startsWith("default-src 'none'"))
        assertEquals(Status.NOT_FOUND, get(server, "/r/ffffffffffffffffffffffffffffffff/").status)
        assertEquals(Status.NOT_FOUND, get(server, "/k/$token/").status)
        assertEquals(Status.NOT_FOUND, get(server, "/r/$token/address").status)
        assertEquals(Status.NOT_FOUND, post(server, path = "/r/ffffffffffffffffffffffffffffffff/address").status)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `the guard runs before the token and the check`() {
        val server = newServer()
        assertEquals(Status.FORBIDDEN, get(server, "/r/$token/", host = "evil.example").status)
        assertEquals(Status.FORBIDDEN, post(server, origin = "http://evil.example").status)
        assertTrue(sent.isEmpty())
        assertEquals(Status.OK, post(server, origin = "http://127.0.0.1:8150").status)
        assertEquals(listOf(ADDRESS), sent)
    }

    @Test
    fun `a send is trimmed and checked, and saved answers the host and http, never the address`() {
        val server = newServer()
        verdict = RatingPosterCheck.Saved("192.168.1.20:8000", isHttp = true)
        val response = post(server, body = "  $ADDRESS\n")
        assertEquals(Status.OK, response.status)
        assertEquals(ADDRESS, sent.single())
        val body = response.bodyText()
        assertEquals("""{"host":"192.168.1.20:8000","http":true}""", body)
        assertEquals("close", response.getHeader("Connection"))
    }

    @Test
    fun `each refusal answers 422 with its word, host and code only`() {
        val server = newServer()
        val expected = listOf(
            RatingPosterCheck.KeyRejected("pp.example.com", 401) to """{"problem":"key","host":"pp.example.com","code":401}""",
            RatingPosterCheck.NotFound("pp.example.com") to """{"problem":"address","host":"pp.example.com"}""",
            RatingPosterCheck.NoPoster("pp.example.com") to """{"problem":"no_poster","host":"pp.example.com"}""",
            RatingPosterCheck.Untestable to """{"problem":"untestable"}""",
            RatingPosterCheck.Refused(RatingPosterTemplate.Refusal.HTTP_NOT_HOME_NETWORK) to """{"problem":"https"}""",
            RatingPosterCheck.Refused(RatingPosterTemplate.Refusal.NO_ID_PLACEHOLDER) to """{"problem":"no_id"}""",
            RatingPosterCheck.Refused(RatingPosterTemplate.Refusal.USERINFO) to """{"problem":"format"}""",
            RatingPosterCheck.Refused(RatingPosterTemplate.Refusal.NOT_A_URL) to """{"problem":"format"}""",
        )
        for ((check, body) in expected) {
            verdict = check
            val response = post(server)
            assertEquals(check.toString(), 422, response.status.requestStatus)
            assertEquals(body, response.bodyText())
        }
        verdict = RatingPosterCheck.Closed
        assertEquals(Status.GONE, post(server).status)
    }

    @Test
    fun `length rules and an empty address answer without a check`() {
        val server = newServer()
        assertEquals(Status.LENGTH_REQUIRED, post(server, declaredLength = null).status)
        assertEquals(
            Status.PAYLOAD_TOO_LARGE,
            post(server, declaredLength = (RatingPosterServer.MAX_ADDRESS_BODY_BYTES + 1).toString()).status,
        )
        assertEquals(Status.BAD_REQUEST, post(server, declaredLength = (ADDRESS.length + 10).toString()).status)
        assertEquals(Status.BAD_REQUEST, post(server, body = " \n\t").status)
        val dropped = FakeUploadSession("/r/$token/address", kind = null, body = ADDRESS.toByteArray(), failAfter = 4)
        assertEquals(Status.BAD_REQUEST, server.serve(dropped).status)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `after close a send answers 410 at once, drained and unchecked`() {
        val server = newServer()
        server.close()
        val late = FakeUploadSession("/r/$token/address", kind = null, body = ADDRESS.toByteArray())
        assertEquals(Status.GONE, server.serve(late).status)
        assertEquals(0, late.stream.available())
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `the page has no absolute same-origin path and sets every text as text`() {
        val context = mockk<Context>(relaxed = true) {
            every { getString(any()) } returns "Text with <b>tags</b> and 'quotes'"
        }
        val html = RatingPosterWebPage.html(context)
        assertNoAbsoluteSameOriginPath(html)
        assertFalse("innerHTML" in html)
        assertTrue("fetch(base + 'address'" in html)
        assertTrue("'Text with <b>tags</b> and \\'quotes\\''" in html)
        // The %1${'$'}s fill in the JS is the literal app-string placeholder.
        assertTrue("split('%1\$s')" in html)
        assertFalse("a Kotlin template left in", "\${" in html)
        // Every word the server can answer has its case ("format" is the page's default).
        val words = (REFUSALS + RatingPosterTemplate.Refusal.entries.map { RatingPosterCheck.Refused(it) })
            .mapNotNull { RatingPosterServer.problemOf(it) }
            .toSet()
        assertTrue("format" in words)
        (words - "format").forEach { assertTrue(it, "case '$it':" in html) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `the phone page binds sends to the panel's profile, closes, and expires`() {
        val scope = TestScope(StandardTestDispatcher())
        val checks = mutableListOf<Pair<String, Int>>()
        var server: RatingPosterServer? = null
        val page = RatingPosterPhonePage(
            scope = scope,
            start = { submit -> newServer(submit).also { server = it }.let { StartedPosterServer(it, it.url("10.0.0.2")) } },
            checkAndSave = { input, profileId, stillWanted ->
                checks += input to profileId
                if (stillWanted()) RatingPosterCheck.Saved("h", false) else RatingPosterCheck.Closed
            },
            lifetime = 100.milliseconds,
        )
        page.open(profileId = 4)
        assertTrue(page.url.value!!.endsWith("/r/$token/"))
        assertEquals(Status.OK, post(server!!).status)
        assertEquals(listOf(ADDRESS to 4), checks)

        val first = server!!
        page.open(profileId = 5)
        assertTrue(first.isClosed)
        assertEquals(Status.GONE, post(first).status)
        assertEquals(1, checks.size)

        scope.advanceTimeBy(150)
        scope.runCurrent()
        assertNull(page.url.value)
        assertTrue(server!!.isClosed)
    }

    @Test
    fun `a close during a check still delivers the answer, then the server stops`() {
        val checking = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val server = newServerWithGrace(200) { _, address ->
            sent += address
            checking.countDown()
            finish.await(5, TimeUnit.SECONDS)
            RatingPosterCheck.Saved("pp.example.com", isHttp = false)
        }.also(::startOnLoopback)
        var reply = ""
        val client = Thread { reply = rawPost(server.listeningPort) }.apply { start() }
        assertTrue(checking.await(5, TimeUnit.SECONDS))

        server.close()
        Thread.sleep(300) // past the grace: only the send in flight keeps the server up
        assertTrue(server.isAlive)
        finish.countDown()
        client.join(5_000)

        assertTrue(reply, reply.startsWith("HTTP/1.1 200"))
        assertTrue(reply, reply.endsWith("""{"host":"pp.example.com","http":false}"""))
        awaitStopped(server)
    }

    @Test
    fun `a close waits for a stuck check at most its cap`() {
        val checking = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = RatingPosterServer(token, { "" }, { _, _ ->
            checking.countDown()
            release.await(10, TimeUnit.SECONDS)
            RatingPosterCheck.Closed
        }, "127.0.0.1", 0, stopGraceMs = 0, stopWaitMs = 300).also(::startOnLoopback)
        val client = Thread { runCatching { rawPost(server.listeningPort) } }.apply { start() }
        assertTrue(checking.await(5, TimeUnit.SECONDS))

        server.close()
        awaitStopped(server)
        release.countDown()
        client.join(5_000)
    }

    @Test
    fun `sends run one at a time, and one queued behind a closed page answers Closed unchecked`() {
        val firstChecking = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        val servers = mutableListOf<RatingPosterServer>()
        val page = RatingPosterPhonePage(
            scope = TestScope(StandardTestDispatcher()),
            start = { submit -> newServer(submit).also { servers += it }.let { StartedPosterServer(it, it.url("10.0.0.2")) } },
            checkAndSave = { input, _, _ ->
                order += "start $input"
                if (input == "first") {
                    firstChecking.countDown()
                    releaseFirst.await(5, TimeUnit.SECONDS)
                }
                order += "end $input"
                RatingPosterCheck.Saved("h", false)
            },
        )
        page.open(profileId = 1)
        val server = servers.single()
        var firstStatus: Status? = null
        var secondStatus: Status? = null
        val first = Thread { firstStatus = post(server, body = "first").status as Status }.apply { start() }
        assertTrue(firstChecking.await(5, TimeUnit.SECONDS))
        val second = Thread { secondStatus = post(server, body = "second").status as Status }.apply { start() }
        awaitBlocked(second)
        assertEquals(listOf("start first"), order.toList())
        releaseFirst.countDown()
        first.join(5_000)
        second.join(5_000)
        assertEquals(listOf("start first", "end first", "start second", "end second"), order.toList())
        assertEquals(Status.OK, firstStatus)
        assertEquals(Status.OK, secondStatus)

        // A send waiting while its page closes is answered without a check.
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closingOrder = mutableListOf<String>()
        val page2 = RatingPosterPhonePage(
            scope = TestScope(StandardTestDispatcher()),
            start = { submit -> newServer(submit).also { servers += it }.let { StartedPosterServer(it, it.url("10.0.0.2")) } },
            checkAndSave = { input, _, stillWanted ->
                closingOrder += input
                held.countDown()
                release.await(5, TimeUnit.SECONDS)
                if (stillWanted()) RatingPosterCheck.Saved("h", false) else RatingPosterCheck.Closed
            },
        )
        page2.open(profileId = 1)
        val server2 = servers.last()
        var inFlight: Int? = null
        val a = Thread { inFlight = post(server2, body = "a").status.requestStatus }.apply { start() }
        assertTrue(held.await(5, TimeUnit.SECONDS))
        var queued: Int? = null
        val b = Thread { queued = post(server2, body = "b").status.requestStatus }.apply { start() }
        awaitBlocked(b)
        page2.close()
        release.countDown()
        a.join(5_000)
        b.join(5_000)
        assertEquals(listOf("a"), closingOrder)
        // The check in flight learns its page closed after its poster came back: nothing saved, 410.
        assertEquals(410, inFlight)
        assertEquals(410, queued)
    }

    /** Waits until [thread] is parked on a monitor: the only one on a send's path is the page's sendLock. */
    private fun awaitBlocked(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (thread.state != Thread.State.BLOCKED) {
            if (System.nanoTime() > deadline) fail("the send never waited on sendLock")
            Thread.sleep(5)
        }
    }

    private fun newServerWithGrace(
        graceMs: Long,
        submit: (RatingPosterServer, String) -> RatingPosterCheck,
    ) = RatingPosterServer(token, { "" }, submit, "127.0.0.1", 0, stopGraceMs = graceMs, stopWaitMs = 5_000)

    private fun startOnLoopback(server: RatingPosterServer) {
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        started += server
    }

    @After
    fun stopServers() = started.forEach { it.stop() }

    /** POSTs [ADDRESS] over a real socket; the raw reply (status line to body) until the server closes. */
    private fun rawPost(port: Int): String = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 10_000
        val body = ADDRESS.toByteArray(StandardCharsets.UTF_8)
        val head = "POST /r/$token/address HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: text/plain\r\n" +
            "Content-Length: ${body.size}\r\n\r\n"
        socket.getOutputStream().apply {
            write(head.toByteArray(StandardCharsets.US_ASCII))
            write(body)
            flush()
        }
        val received = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        try {
            while (true) {
                val read = socket.getInputStream().read(buffer)
                if (read <= 0) break
                received.write(buffer, 0, read)
            }
        } catch (_: IOException) {
            // Reset: what was read still counts.
        }
        received.toString(StandardCharsets.UTF_8.name())
    }

    private fun awaitStopped(server: RatingPosterServer) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val refused = try {
                Socket("127.0.0.1", server.listeningPort).close()
                false
            } catch (_: ConnectException) {
                true
            }
            if (refused && !server.isAlive) return
            Thread.sleep(20)
        }
        fail("the server did not stop")
    }

    private val started = mutableListOf<RatingPosterServer>()

    private companion object {
        const val ADDRESS = "https://pp.example.com/poster?imdb_id={imdb_id}&access_key=SECRETKEY1234"
        val REFUSALS = listOf(
            RatingPosterCheck.KeyRejected("h", 401),
            RatingPosterCheck.NotFound("h"),
            RatingPosterCheck.NoPoster("h"),
            RatingPosterCheck.Untestable,
        )
    }
}
