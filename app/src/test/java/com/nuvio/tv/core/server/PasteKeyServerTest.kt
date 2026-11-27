package com.nuvio.tv.core.server

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Spec 106 K3: the paste page's token, guard, body rules, answer statuses, and a close that never cuts an answer. */
class PasteKeyServerTest {

    private val token = "0123456789abcdef0123456789abcdef"
    private val sent = mutableListOf<String>()
    private var answer: PasteCheck = PasteCheck.Saved
    private val started = mutableListOf<PasteKeyServer>()

    @After
    fun stopServers() = started.forEach { it.stop() }

    private fun newServer(
        stopGraceMs: Long = 0,
        stopWaitMs: Long = 5_000,
        submit: (PasteKeyServer, String) -> PasteCheck = { _, value -> sent += value; answer }
    ) = PasteKeyServer(token, { "<html>page</html>" }, submit, MAX_BODY, "127.0.0.1", 0, stopGraceMs, stopWaitMs)

    private fun post(
        server: PasteKeyServer,
        body: String = VALUE,
        declaredLength: String? = body.toByteArray().size.toString(),
        path: String = "/p/$token/value",
        origin: String? = null
    ): NanoHTTPD.Response = server.serve(
        FakeUploadSession(path, kind = null, body = body.toByteArray(), declaredLength = declaredLength, origin = origin)
    )

    private fun get(server: PasteKeyServer, path: String, host: String = FAKE_HOST): NanoHTTPD.Response =
        server.serve(FakeUploadSession(path, method = NanoHTTPD.Method.GET, kind = null, host = host))

    @Test
    fun `the page answers only under its token, with the inline-only policy and no caching`() {
        val server = newServer()
        val page = get(server, "/p/$token/")
        assertEquals(Status.OK, page.status)
        assertEquals("<html>page</html>", page.bodyText())
        assertEquals(INLINE_PAGE_CSP, page.getHeader("Content-Security-Policy"))
        assertEquals("no-store", page.getHeader("Cache-Control"))
        assertEquals(Status.NOT_FOUND, get(server, "/p/ffffffffffffffffffffffffffffffff/").status)
        assertEquals(Status.NOT_FOUND, get(server, "/k/$token/").status)
        assertEquals(Status.NOT_FOUND, get(server, "/p/$token/value").status)
        assertEquals(Status.NOT_FOUND, post(server, path = "/p/$token/").status)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a send under another token answers 404 after draining its body`() {
        val server = newServer()
        val stale = FakeUploadSession("/p/ffffffffffffffffffffffffffffffff/value", kind = null, body = VALUE.toByteArray())
        assertEquals(Status.NOT_FOUND, server.serve(stale).status)
        assertEquals(0, stale.stream.available())
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `the guard runs before the token and the check`() {
        val server = newServer()
        assertEquals(Status.FORBIDDEN, get(server, "/p/$token/", host = "evil.example").status)
        assertEquals(Status.FORBIDDEN, post(server, origin = "http://evil.example").status)
        assertTrue(sent.isEmpty())
        assertEquals(Status.OK, post(server, origin = "http://127.0.0.1:8160").status)
        assertEquals(listOf(VALUE), sent)
    }

    @Test
    fun `a send is checked trimmed, and each outcome has its status without the value`() {
        val server = newServer()
        assertEquals(Status.OK, post(server, body = "  $VALUE\n").status)
        assertEquals(VALUE, sent.single())
        val expected = mapOf(
            PasteCheck.Saved to 200,
            PasteCheck.Refused to 422,
            PasteCheck.Unreachable to 503,
            PasteCheck.Closed to 410
        )
        expected.forEach { (outcome, status) ->
            answer = outcome
            val response = post(server)
            assertEquals(outcome.toString(), status, response.status.requestStatus)
            assertTrue(!response.bodyText().contains(VALUE))
        }
    }

    @Test
    fun `length rules and an empty value answer 411, 413 and 400 without a check`() {
        val server = newServer()
        assertEquals(Status.LENGTH_REQUIRED, post(server, declaredLength = null).status)
        assertEquals(Status.PAYLOAD_TOO_LARGE, post(server, declaredLength = (MAX_BODY + 1).toString()).status)
        assertEquals(Status.BAD_REQUEST, post(server, declaredLength = (VALUE.length + 10).toString()).status)
        assertEquals(Status.BAD_REQUEST, post(server, body = " \n\t").status)
        val dropped = FakeUploadSession("/p/$token/value", kind = null, body = VALUE.toByteArray(), failAfter = 4)
        assertEquals(Status.BAD_REQUEST, server.serve(dropped).status)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `after close a send answers 410 at once, drained and unchecked`() {
        val server = newServer()
        server.close()
        assertTrue(server.isClosed)
        val late = FakeUploadSession("/p/$token/value", kind = null, body = VALUE.toByteArray())
        assertEquals(Status.GONE, server.serve(late).status)
        assertEquals(0, late.stream.available())
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a close during a check still delivers the answer, then the server stops`() {
        val checking = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val server = newServer(stopGraceMs = 200) { _, value ->
            sent += value
            checking.countDown()
            finish.await(5, TimeUnit.SECONDS)
            PasteCheck.Saved
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
        awaitStopped(server)
    }

    @Test
    fun `a close waits for a stuck check at most its cap`() {
        val checking = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = newServer(stopWaitMs = 300) { _, _ ->
            checking.countDown()
            release.await(10, TimeUnit.SECONDS)
            PasteCheck.Saved
        }.also(::startOnLoopback)
        val client = Thread { runCatching { rawPost(server.listeningPort) } }.apply { start() }
        assertTrue(checking.await(5, TimeUnit.SECONDS))

        server.close()
        awaitStopped(server)
        release.countDown()
        client.join(5_000)
    }

    private fun startOnLoopback(server: PasteKeyServer) {
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        started += server
    }

    private fun rawPost(port: Int): String = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 10_000
        val body = VALUE.toByteArray(StandardCharsets.UTF_8)
        val head = "POST /p/$token/value HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: text/plain\r\n" +
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

    private fun awaitStopped(server: PasteKeyServer) {
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

    private companion object {
        const val VALUE = "a3f9c2e17b4d5e6f708192a3b4c5d6e7f80e58d61c7f3a"
        const val MAX_BODY = 1_024
    }
}
