package com.nuvio.tv.core.server

import com.nuvio.tv.data.local.MyPhotoKind
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 44d: the upload server's token, guard, POST order, size and dimension checks, and its one pending slot. */
class PhotoUploadServerTest {

    private val token = "0123456789abcdef0123456789abcdef"
    private val upload = ByteArray(64) { it.toByte() }
    private val reencoded = byteArrayOf(9, 9, 9)

    /** Reports an exact-size JPEG; the "re-encode" returns [reencoded]. Records what it was handed. */
    private inner class FakeCodec(
        var bounds: PhotoBounds? = PhotoBounds("image/jpeg", 512, 512),
        var output: ByteArray? = reencoded
    ) : PhotoCodec {
        val boundsInputs = mutableListOf<ByteArray>()
        val reencodeCalls = mutableListOf<Triple<ByteArray, Int, Int>>()
        var onReencode: () -> Unit = {}
        override fun bounds(bytes: ByteArray): PhotoBounds? = bounds.also { boundsInputs += bytes }
        override fun reencodeJpeg(bytes: ByteArray, width: Int, height: Int): ByteArray? {
            reencodeCalls += Triple(bytes, width, height)
            onReencode()
            return output
        }
    }

    private val pending = mutableListOf<PendingPhoto>()
    private var full = false
    private val codec = FakeCodec()

    private fun newServer(hostname: String? = null, port: Int = 0) = PhotoUploadServer(
        token = token,
        pageHtml = { "<html>page</html>" },
        isGalleryFull = { full },
        codec = codec,
        onPending = { _, photo -> pending += photo },
        hostname = hostname,
        port = port
    )

    private fun post(
        server: PhotoUploadServer,
        kind: String? = "avatar",
        body: ByteArray = upload,
        declaredLength: String? = body.size.toString(),
        path: String = "/u/$token/photo",
        origin: String? = null
    ): NanoHTTPD.Response =
        server.serve(FakeUploadSession(path, kind = kind, body = body, declaredLength = declaredLength, origin = origin))

    private fun get(server: PhotoUploadServer, path: String, host: String = FAKE_HOST): NanoHTTPD.Response =
        server.serve(FakeUploadSession(path, method = NanoHTTPD.Method.GET, kind = null, host = host))

    @Test
    fun `the page answers only under its token`() {
        val server = newServer()
        val page = get(server, "/u/$token/")
        assertEquals(Status.OK, page.status)
        assertEquals("<html>page</html>", page.bodyText())
        assertEquals(Status.OK, get(server, "/u/$token").status)
        assertEquals(Status.NOT_FOUND, get(server, "/u/ffffffffffffffffffffffffffffffff/").status)
        assertEquals(Status.NOT_FOUND, get(server, "/u/${token}0/").status)
        assertEquals(Status.NOT_FOUND, get(server, "/").status)
        assertEquals(Status.NOT_FOUND, post(server, path = "/u/other/photo").status)
        assertTrue(pending.isEmpty())
    }

    @Test
    fun `every answer is uncached and sends no referrer`() {
        val server = newServer()
        listOf(get(server, "/u/$token/"), get(server, "/nope"), post(server)).forEach { response ->
            assertEquals("no-store", response.getHeader("Cache-Control"))
            assertEquals("no-referrer", response.getHeader("Referrer-Policy"))
        }
        val csp = get(server, "/u/$token/").getHeader("Content-Security-Policy").orEmpty().split(';').map { it.trim() }
        listOf(
            "default-src 'none'", "script-src 'unsafe-inline'", "style-src 'unsafe-inline'", "img-src blob: data:",
            "connect-src 'self'", "base-uri 'none'", "form-action 'none'", "frame-ancestors 'none'"
        ).forEach { assertTrue(it, it in csp) }
        assertEquals(8, csp.size)
    }

    @Test
    fun `the guard runs before the token`() {
        val server = newServer()
        assertEquals(Status.FORBIDDEN, get(server, "/u/$token/", host = "evil.example").status)
        assertEquals(Status.FORBIDDEN, post(server, origin = "http://evil.example").status)
        assertEquals(Status.FORBIDDEN, get(server, "/u/wrong/", host = "evil.example").status)
        val wrongToken = FakeUploadSession("/u/wrong/photo", body = upload, origin = "http://evil.example")
        assertEquals(Status.FORBIDDEN, server.serve(wrongToken).status)
        assertTrue(pending.isEmpty())
    }

    @Test
    fun `a valid upload waits as the re-encoded photo, never the uploaded bytes`() {
        val server = newServer()
        val response = post(server)
        assertEquals(Status.OK, response.status)
        assertEquals(PhotoUploadServer.SENT, response.bodyText())
        assertEquals(1, pending.size)
        assertEquals(MyPhotoKind.Avatar, pending.single().kind)
        assertArrayEquals(reencoded, pending.single().jpeg)
        assertFalse(pending.single().jpeg.contentEquals(upload))
        assertArrayEquals(upload, codec.boundsInputs.single())
        val (bytes, width, height) = codec.reencodeCalls.single()
        assertArrayEquals(upload, bytes)
        assertEquals(512 to 512, width to height)
    }

    @Test
    fun `a background must be exactly 1920 by 1080`() {
        codec.bounds = PhotoBounds("image/jpeg", 1920, 1080)
        val server = newServer()
        assertEquals(Status.BAD_REQUEST, post(server, kind = "avatar").status)
        assertEquals(Status.OK, post(server, kind = "background").status)
        assertEquals(MyPhotoKind.Background, pending.single().kind)
        assertEquals(1920 to 1080, codec.reencodeCalls.single().let { it.second to it.third })
    }

    @Test
    fun `wrong size, a non-JPEG, an unreadable image or a failed re-encode is refused and frees the slot`() {
        val server = newServer()
        codec.bounds = PhotoBounds("image/jpeg", 512, 511)
        assertEquals(Status.BAD_REQUEST, post(server).status)
        codec.bounds = PhotoBounds("image/png", 512, 512)
        assertEquals(Status.BAD_REQUEST, post(server).status)
        codec.bounds = null
        assertEquals(Status.BAD_REQUEST, post(server).status)
        codec.bounds = PhotoBounds("image/jpeg", 512, 512)
        codec.output = null
        assertEquals(Status.BAD_REQUEST, post(server).status)
        assertTrue(pending.isEmpty())
        codec.output = reencoded
        assertEquals(Status.OK, post(server).status)
    }

    @Test
    fun `an unknown kind is refused`() {
        val server = newServer()
        assertEquals(Status.BAD_REQUEST, post(server, kind = "banner").status)
        assertEquals(Status.BAD_REQUEST, post(server, kind = null).status)
        assertTrue(pending.isEmpty())
    }

    @Test
    fun `a full gallery is refused before the slot and the body is drained`() {
        full = true
        val server = newServer()
        val session = FakeUploadSession("/u/$token/photo", body = upload)
        val response = server.serve(session)
        assertEquals(Status.CONFLICT, response.status)
        assertEquals(PhotoUploadServer.FULL, response.bodyText())
        assertEquals(0, session.stream.available())
        full = false
        assertEquals(Status.OK, post(server).status)
    }

    @Test
    fun `a busy answer drains the body, and full is checked before the slot`() {
        val server = newServer()
        assertEquals(Status.OK, post(server).status)
        val busy = FakeUploadSession("/u/$token/photo", body = upload)
        assertEquals(PhotoUploadServer.BUSY, server.serve(busy).bodyText())
        assertEquals(0, busy.stream.available())
        full = true
        assertEquals(PhotoUploadServer.FULL, post(server).bodyText())
    }

    @Test
    fun `a POST under another token answers 404 after draining its body`() {
        val server = newServer()
        val stale = FakeUploadSession("/u/ffffffffffffffffffffffffffffffff/photo", body = upload)
        val response = server.serve(stale)
        assertEquals(Status.NOT_FOUND, response.status)
        assertEquals(0, stale.stream.available())
        assertEquals("close", response.getHeader("Connection"))
    }

    @Test
    fun `a connection dropped mid-body answers 400 and frees the slot`() {
        val server = newServer()
        val dropped = FakeUploadSession("/u/$token/photo", body = upload, failAfter = 10)
        assertEquals(Status.BAD_REQUEST, server.serve(dropped).status)
        assertTrue(pending.isEmpty())
        assertEquals(Status.OK, post(server).status)
    }

    @Test
    fun `a second upload is refused while the first is still being decoded`() {
        val server = newServer()
        val decoding = java.util.concurrent.CountDownLatch(1)
        val finish = java.util.concurrent.CountDownLatch(1)
        codec.onReencode = {
            decoding.countDown()
            finish.await(5, java.util.concurrent.TimeUnit.SECONDS)
        }
        var first: NanoHTTPD.Response? = null
        val thread = Thread { first = post(server) }.apply { start() }
        assertTrue(decoding.await(5, java.util.concurrent.TimeUnit.SECONDS))
        codec.onReencode = {}
        assertEquals(PhotoUploadServer.BUSY, post(server).bodyText())
        finish.countDown()
        thread.join(5_000)
        assertEquals(Status.OK, first?.status)
        assertEquals(1, pending.size)
    }

    @Test
    fun `one photo waits at a time until it is released by id`() {
        val server = newServer()
        assertEquals(Status.OK, post(server).status)
        val busy = post(server)
        assertEquals(Status.CONFLICT, busy.status)
        assertEquals(PhotoUploadServer.BUSY, busy.bodyText())
        server.release("not-the-pending-id")
        assertEquals(Status.CONFLICT, post(server).status)
        server.release(pending.single().id)
        assertEquals(Status.OK, post(server).status)
        assertEquals(2, pending.size)
    }

    @Test
    fun `length rules answer 411, 413 and 400 and free the slot`() {
        val server = newServer()
        assertEquals(Status.LENGTH_REQUIRED, post(server, declaredLength = null).status)
        assertEquals(Status.LENGTH_REQUIRED, post(server, declaredLength = "abc").status)
        assertEquals(Status.LENGTH_REQUIRED, post(server, declaredLength = "-1").status)
        assertEquals(
            Status.PAYLOAD_TOO_LARGE,
            post(server, declaredLength = (PhotoUploadServer.MAX_PHOTO_BYTES + 1).toString()).status
        )
        assertEquals(Status.BAD_REQUEST, post(server, declaredLength = (upload.size + 10).toString()).status)
        assertTrue(pending.isEmpty())
        assertEquals(Status.OK, post(server).status)
    }

    @Test
    fun `every POST answer closes the connection`() {
        val server = newServer()
        assertEquals("close", post(server).getHeader("Connection"))
        assertEquals("close", post(server).getHeader("Connection")) // busy
        assertNull(get(server, "/u/$token/").getHeader("Connection"))
    }

    @Test
    fun `tokens are 128-bit hex and fresh each time`() {
        val first = newSessionToken()
        assertTrue(first.matches(Regex("[0-9a-f]{32}")))
        assertFalse(first == newSessionToken())
    }

    /**
     * m4 on the real NanoHTTPD 2.3.1 socket loop: an answer sent before the body is read must not let
     * the leftover body (here a second request) be served on the same keep-alive connection. Full
     * (409) drains the body; a bad kind (400) leaves it unread, so only Connection: close stops it.
     */
    @Test
    fun `an early answer does not serve a body left on a keep-alive connection`() {
        full = true
        assertEarlyAnswerServesOnce(kind = "avatar", statusLine = "HTTP/1.1 409")
        full = false
        assertEarlyAnswerServesOnce(kind = "banner", statusLine = "HTTP/1.1 400")
    }

    private fun assertEarlyAnswerServesOnce(kind: String, statusLine: String) {
        val served = AtomicInteger()
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            val inner = newServer()
            override fun serve(session: IHTTPSession): Response {
                served.incrementAndGet()
                return inner.serve(session)
            }
        }
        server.start(2_000, true)
        try {
            val smuggled = "GET /u/$token/ HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
            Socket("127.0.0.1", server.listeningPort).use { socket ->
                socket.soTimeout = 3_000
                val head = "POST /u/$token/photo?kind=$kind HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                    "Content-Type: image/jpeg\r\nContent-Length: ${smuggled.size}\r\nConnection: keep-alive\r\n\r\n"
                socket.getOutputStream().apply {
                    write(head.toByteArray(StandardCharsets.US_ASCII))
                    write(smuggled)
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
                } catch (_: SocketTimeoutException) {
                    // The connection stayed open: the assertions below fail.
                } catch (_: java.net.SocketException) {
                    // Reset after the answer (unread bytes on close): what was read still counts.
                }
                val text = received.toString(StandardCharsets.US_ASCII.name())
                assertTrue(text, text.startsWith(statusLine))
                assertTrue(text, text.contains("Connection: close"))
            }
            assertEquals(1, served.get())
        } finally {
            server.stop()
        }
    }
}
