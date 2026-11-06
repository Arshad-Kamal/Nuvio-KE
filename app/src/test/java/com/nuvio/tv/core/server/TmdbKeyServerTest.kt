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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Spec 52 S3: the key page's token, guard, body rules, answer statuses, and a close that never cuts an answer. */
class TmdbKeyServerTest {

    private val token = "0123456789abcdef0123456789abcdef"
    private val sent = mutableListOf<String>()
    private var answer = TmdbKeyAnswer.SAVED
    private val started = mutableListOf<TmdbKeyServer>()

    @After
    fun stopServers() = started.forEach { it.stop() }

    private fun newServer(
        stopGraceMs: Long = 0,
        stopWaitMs: Long = 5_000,
        submit: (TmdbKeyServer, String) -> TmdbKeyAnswer = { _, key -> sent += key; answer }
    ) = TmdbKeyServer(token, { "<html>page</html>" }, submit, "127.0.0.1", 0, stopGraceMs, stopWaitMs)

    private fun post(
        server: TmdbKeyServer,
        body: String = KEY,
        declaredLength: String? = body.toByteArray().size.toString(),
        path: String = "/k/$token/key",
        origin: String? = null
    ): NanoHTTPD.Response = server.serve(
        FakeUploadSession(path, kind = null, body = body.toByteArray(), declaredLength = declaredLength, origin = origin)
    )

    private fun get(server: TmdbKeyServer, path: String, host: String = FAKE_HOST): NanoHTTPD.Response =
        server.serve(FakeUploadSession(path, method = NanoHTTPD.Method.GET, kind = null, host = host))

    @Test
    fun `the page answers only under its token`() {
        val server = newServer()
        val page = get(server, "/k/$token/")
        assertEquals(Status.OK, page.status)
        assertEquals("<html>page</html>", page.bodyText())
        assertEquals(Status.OK, get(server, "/k/$token").status)
        assertEquals(Status.NOT_FOUND, get(server, "/k/ffffffffffffffffffffffffffffffff/").status)
        assertEquals(Status.NOT_FOUND, get(server, "/u/$token/").status)
        assertEquals(Status.NOT_FOUND, get(server, "/k/$token/key").status)
        assertEquals(Status.NOT_FOUND, post(server, path = "/k/$token/").status)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a send under another token answers 404 after draining its body`() {
        val server = newServer()
        val stale = FakeUploadSession("/k/ffffffffffffffffffffffffffffffff/key", kind = null, body = KEY.toByteArray())
        val response = server.serve(stale)
        assertEquals(Status.NOT_FOUND, response.status)
        assertEquals(0, stale.stream.available())
        assertEquals("close", response.getHeader("Connection"))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `every answer is uncached and sends no referrer, and the page allows nothing outside itself`() {
        val server = newServer()
        listOf(get(server, "/k/$token/"), get(server, "/nope"), post(server)).forEach { response ->
            assertEquals("no-store", response.getHeader("Cache-Control"))
            assertEquals("no-referrer", response.getHeader("Referrer-Policy"))
        }
        val csp = get(server, "/k/$token/").getHeader("Content-Security-Policy").orEmpty().split(';').map { it.trim() }
        listOf(
            "default-src 'none'", "script-src 'unsafe-inline'", "style-src 'unsafe-inline'", "connect-src 'self'",
            "base-uri 'none'", "form-action 'none'", "frame-ancestors 'none'"
        ).forEach { assertTrue(it, it in csp) }
        assertEquals(7, csp.size)
    }

    @Test
    fun `the guard runs before the token and the check`() {
        val server = newServer()
        assertEquals(Status.FORBIDDEN, get(server, "/k/$token/", host = "evil.example").status)
        assertEquals(Status.FORBIDDEN, post(server, origin = "http://evil.example").status)
        assertEquals(Status.OK, post(server, origin = "http://127.0.0.1:8120").status)
        // Wrong token and a foreign host: the guard answers first (403, not the token's 404).
        assertEquals(Status.FORBIDDEN, get(server, "/k/wrong/", host = "evil.example").status)
        val foreign = FakeUploadSession("/k/wrong/key", kind = null, body = KEY.toByteArray(), origin = "http://evil.example")
        assertEquals(Status.FORBIDDEN, server.serve(foreign).status)
        assertEquals(listOf(KEY), sent)
    }

    @Test
    fun `a send is checked with whitespace dropped, and each outcome has its status`() {
        val server = newServer()
        assertEquals(Status.OK, post(server, body = "  ${KEY.chunked(8).joinToString(" ")}\n").status)
        assertEquals(KEY, sent.single())

        val expected = mapOf(
            TmdbKeyAnswer.SAVED to 200,
            TmdbKeyAnswer.ALREADY_SAVED to 409,
            TmdbKeyAnswer.REJECTED to 422,
            TmdbKeyAnswer.UNREACHABLE to 503,
            TmdbKeyAnswer.CLOSED to 410
        )
        TmdbKeyAnswer.entries.forEach { outcome ->
            answer = outcome
            val response = post(server)
            assertEquals(outcome.name, expected.getValue(outcome), response.status.requestStatus)
            assertEquals(outcome.word, response.bodyText())
        }
        assertEquals("422 Unprocessable Entity", TmdbKeyAnswer.REJECTED.status.description)
    }

    @Test
    fun `length rules and an empty key answer 411, 413 and 400 without a check`() {
        val server = newServer()
        assertEquals(Status.LENGTH_REQUIRED, post(server, declaredLength = null).status)
        assertEquals(
            Status.PAYLOAD_TOO_LARGE,
            post(server, declaredLength = (TmdbKeyServer.MAX_KEY_BODY_BYTES + 1).toString()).status
        )
        assertEquals(Status.BAD_REQUEST, post(server, declaredLength = (KEY.length + 10).toString()).status)
        assertEquals(Status.BAD_REQUEST, post(server, body = " \n\t").status)
        assertEquals(Status.BAD_REQUEST, post(server, body = "").status)
        assertTrue(sent.isEmpty())
        val dropped = FakeUploadSession("/k/$token/key", kind = null, body = KEY.toByteArray(), failAfter = 4)
        assertEquals(Status.BAD_REQUEST, server.serve(dropped).status)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `every send answer closes the connection`() {
        val server = newServer()
        assertEquals("close", post(server).getHeader("Connection"))
        assertEquals("close", post(server, body = "").getHeader("Connection"))
        assertNull(get(server, "/k/$token/").getHeader("Connection"))
    }

    @Test
    fun `after close a send answers 410 at once, drained and unchecked`() {
        val server = newServer()
        server.close()
        assertTrue(server.isClosed)
        val late = FakeUploadSession("/k/$token/key", kind = null, body = KEY.toByteArray())
        val response = server.serve(late)
        assertEquals(Status.GONE, response.status)
        assertEquals(0, late.stream.available())
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a close during a check still delivers the answer, then the server stops`() {
        val checking = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val server = newServer(stopGraceMs = 200) { _, key ->
            sent += key
            checking.countDown()
            finish.await(5, TimeUnit.SECONDS)
            TmdbKeyAnswer.SAVED
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
        assertTrue(reply, reply.endsWith("saved"))
        awaitStopped(server)
    }

    @Test
    fun `a close fired as the answer is decided (the panel closing on accept) still delivers it`() {
        lateinit var server: TmdbKeyServer
        server = newServer(stopGraceMs = 300) { _, key ->
            sent += key
            server.close()
            TmdbKeyAnswer.SAVED
        }.also(::startOnLoopback)

        val reply = rawPost(server.listeningPort)

        assertTrue(reply, reply.startsWith("HTTP/1.1 200"))
        assertTrue(reply, reply.endsWith("saved"))
        awaitStopped(server)
    }

    @Test
    fun `the 422 status line reaches the phone`() {
        answer = TmdbKeyAnswer.REJECTED
        val server = newServer().also(::startOnLoopback)
        val reply = rawPost(server.listeningPort)
        assertTrue(reply, reply.startsWith("HTTP/1.1 422 Unprocessable Entity"))
    }

    @Test
    fun `a close waits for a stuck check at most its cap`() {
        val checking = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = newServer(stopWaitMs = 300) { _, _ ->
            checking.countDown()
            release.await(10, TimeUnit.SECONDS)
            TmdbKeyAnswer.SAVED
        }.also(::startOnLoopback)
        val client = Thread { runCatching { rawPost(server.listeningPort) } }.apply { start() }
        assertTrue(checking.await(5, TimeUnit.SECONDS))

        server.close()
        awaitStopped(server)
        release.countDown()
        client.join(5_000)
    }

    private fun startOnLoopback(server: TmdbKeyServer) {
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        started += server
    }

    /** POSTs [KEY] over a real socket; the raw reply (status line to body) until the server closes. */
    private fun rawPost(port: Int): String = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 10_000
        val body = KEY.toByteArray(StandardCharsets.UTF_8)
        val head = "POST /k/$token/key HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: text/plain\r\n" +
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

    private fun awaitStopped(server: TmdbKeyServer) {
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
        const val KEY = "4c1e9a7b2f6d08e35a9c1b7f42d6e0a8"
    }
}
