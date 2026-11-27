package com.nuvio.tv.core.backup

import com.nuvio.tv.core.server.FakeGetSession
import com.nuvio.tv.core.server.FakeUploadSession
import com.nuvio.tv.core.server.bodyText
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Spec 101 S6: the phone page's token, guard, POST order, upload-to-file, the TV's check and its one slot. */
class BackupPhoneServerTest {

    private val token = "0123456789abcdef0123456789abcdef"
    private val root: File = Files.createTempDirectory("phone").toFile()
    private val uploadDir = File(root, "phone")
    private val pending = mutableListOf<PendingUpload>()
    private val files = mapOf("42" to BackupFixtures.bytes())
    private val opened = mutableListOf<String>()
    private val items = listOf(
        PhoneBackupItem("42", "16 Nov 2026, 21:42", "Backup · 2 profiles · 1.6 MB"),
        PhoneBackupItem("7", "t", "d"),
    )

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun server(mode: BackupPhoneMode = BackupPhoneMode.RESTORE, enabled: Boolean = true) =
        BackupPhoneServer(token, mode, { "<html>page</html>" }, { items }, { id -> opened += id; files[id]?.let { PhoneBackupFile("Nuvio KE backup 2026-11-16 2142.json.gz", it.inputStream()) } }, uploadDir, BackupFixtures.INSTALLED, enabled, { _, u -> pending += u }, null, 0)

    private fun post(
        server: BackupPhoneServer,
        body: ByteArray = BackupFixtures.bytes(),
        declaredLength: String? = body.size.toString(),
        path: String = "/r/$token/backup",
        host: String = "127.0.0.1:8180",
        origin: String? = null,
        failAfter: Int? = null,
    ): NanoHTTPD.Response = server.serve(
        FakeUploadSession(path, kind = null, body = body, declaredLength = declaredLength, host = host, origin = origin, failAfter = failAfter),
    )

    private fun uploads() = uploadDir.listFiles().orEmpty().toList()

    @Test
    fun `the page is served under the token only, with the inline CSP and no-store`() {
        val s = server()
        val ok = s.serve(FakeGetSession("/r/$token/"))
        assertEquals(NanoHTTPD.Response.Status.OK, ok.status)
        assertEquals("<html>page</html>", ok.bodyText())
        assertTrue(ok.getHeader("Content-Security-Policy").contains("default-src 'none'"))
        assertEquals("no-store", ok.getHeader("Cache-Control"))
        assertEquals(NanoHTTPD.Response.Status.NOT_FOUND, s.serve(FakeGetSession("/r/ffffffffffffffffffffffffffffffff/")).status)
        assertEquals(NanoHTTPD.Response.Status.NOT_FOUND, s.serve(FakeGetSession("/u/$token/")).status)
    }

    @Test
    fun `a foreign Host or a cross-origin post is refused before anything is read`() {
        val s = server()
        assertEquals(NanoHTTPD.Response.Status.FORBIDDEN, s.serve(FakeGetSession("/r/$token/", host = "evil.example")).status)
        assertEquals(NanoHTTPD.Response.Status.FORBIDDEN, post(s, origin = "http://evil.example").status)
        assertTrue(pending.isEmpty() && uploads().isEmpty())
    }

    @Test
    fun `a good backup is received to a file, checked, and handed over with its header`() {
        val answer = post(server())
        assertEquals(NanoHTTPD.Response.Status.OK, answer.status)
        assertEquals(BackupPhoneServer.SENT, answer.bodyText())
        assertEquals("close", answer.getHeader("Connection"))
        val upload = pending.single()
        assertEquals(BackupFixtures.header(), upload.header)
        assertTrue(upload.file.isFile && upload.file.parentFile == uploadDir)
        assertTrue(upload.file.readBytes().contentEquals(BackupFixtures.bytes()))
    }

    @Test
    fun `one upload waits at a time, and release deletes it and frees the slot`() {
        val s = server()
        post(s)
        val first = pending.single()
        val busy = post(s)
        assertEquals(NanoHTTPD.Response.Status.CONFLICT, busy.status)
        assertEquals(BackupPhoneServer.BUSY, busy.bodyText())
        s.release("another id")
        assertTrue(first.file.exists())
        s.release(first.id)
        assertFalse(first.file.exists())
        assertEquals(NanoHTTPD.Response.Status.OK, post(s).status)
        assertEquals(2, pending.size)
    }

    @Test
    fun `refused files answer with the reason and leave nothing behind`() {
        val s = server()
        val cases = mapOf(
            BackupFixtures.gzip("not a backup\n") to BackupPhoneServer.NOT_A_BACKUP,
            "plain text".encodeToByteArray() to BackupPhoneServer.NOT_A_BACKUP,
            BackupFixtures.bytes(BackupFixtures.header(appVersionCode = BackupFixtures.INSTALLED + 1)) to BackupPhoneServer.NEEDS_NEWER_APP,
            BackupFixtures.bytes(BackupFixtures.header(appVersionCode = MIN_BACKUP_VERSION_CODE - 1)) to BackupPhoneServer.TOO_OLD,
            BackupFixtures.gzip(BackupFixtures.gunzip(BackupFixtures.bytes()).dropLast(5)) to BackupPhoneServer.DAMAGED,
        )
        for ((body, reason) in cases) {
            val answer = post(s, body)
            assertEquals(reason, 422, answer.status.requestStatus)
            assertEquals(reason, answer.bodyText())
        }
        assertTrue(pending.isEmpty() && uploads().isEmpty())
        // The slot is free again.
        assertEquals(NanoHTTPD.Response.Status.OK, post(s).status)
    }

    @Test
    fun `length is required, and a body over 20 MB is refused unread`() {
        val s = server()
        assertEquals(NanoHTTPD.Response.Status.LENGTH_REQUIRED, post(s, declaredLength = null).status)
        val big = post(s, declaredLength = (BackupLimits.PHONE_COMPRESSED_MAX_BYTES + 1).toString())
        assertEquals(NanoHTTPD.Response.Status.PAYLOAD_TOO_LARGE, big.status)
        assertEquals(BackupPhoneServer.TOO_LARGE, big.bodyText())
        assertTrue(pending.isEmpty() && uploads().isEmpty())
    }

    @Test
    fun `a body cut off or short leaves no file and frees the slot`() {
        val s = server()
        val bytes = BackupFixtures.bytes()
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, post(s, bytes, failAfter = 10).status)
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, post(s, bytes, declaredLength = (bytes.size + 50).toString()).status)
        assertTrue(pending.isEmpty() && uploads().isEmpty())
        assertEquals(NanoHTTPD.Response.Status.OK, post(s).status)
    }

    @Test
    fun `the save page and a build with restore off take no upload`() {
        assertEquals(NanoHTTPD.Response.Status.NOT_FOUND, post(server(BackupPhoneMode.SAVE)).status)
        assertEquals(NanoHTTPD.Response.Status.NOT_FOUND, post(server(enabled = false)).status)
        assertEquals(NanoHTTPD.Response.Status.NOT_FOUND, post(server(), path = "/r/ffffffffffffffffffffffffffffffff/backup").status)
        assertTrue(pending.isEmpty() && uploads().isEmpty())
    }

    @Test
    fun `the save page lists manual backups and serves one by its id only`() {
        val s = server(BackupPhoneMode.SAVE)
        val list = s.serve(FakeGetSession("/r/$token/list"))
        assertEquals(NanoHTTPD.Response.Status.OK, list.status)
        val json = kotlinx.serialization.json.Json.parseToJsonElement(list.bodyText()).toString()
        assertTrue(json, json.contains("\"id\":\"42\"") && json.contains("\"title\":\"16 Nov 2026, 21:42\""))
        val file = s.serve(FakeGetSession("/r/$token/f/42"))
        assertEquals(NanoHTTPD.Response.Status.OK, file.status)
        assertEquals(ManualBackups.MIME, file.mimeType)
        assertEquals("attachment; filename=\"Nuvio KE backup 2026-11-16 2142.json.gz\"", file.getHeader("Content-Disposition"))
        assertTrue(file.data.readBytes().contentEquals(BackupFixtures.bytes()))
        // An id not in the list, a path, and the other token: nothing opened.
        for (path in listOf("/r/$token/f/43", "/r/$token/f/../../datastore", "/r/$token/f/", "/r/ffffffffffffffffffffffffffffffff/f/42")) {
            assertEquals(path, NanoHTTPD.Response.Status.NOT_FOUND, s.serve(FakeGetSession(path)).status)
        }
        // The path trick reaches the opener only as an opaque id, which matches no file.
        assertEquals(listOf("42", "43", "../../datastore", ""), opened)
        // Quotes and control characters never reach the header.
        assertEquals("badname.json.gz", BackupPhoneServer.safeFileName("bad\"name\r\n.json.gz"))
        // The restore page lists nothing and serves nothing.
        assertEquals(NanoHTTPD.Response.Status.NOT_FOUND, server().serve(FakeGetSession("/r/$token/list")).status)
        assertEquals(NanoHTTPD.Response.Status.NOT_FOUND, server().serve(FakeGetSession("/r/$token/f/42")).status)
    }

    @Test
    fun `readHeader reads a backup's header and refuses anything else`() {
        assertEquals(BackupFixtures.header(), BackupCodec.readHeader(BackupFixtures.bytes().inputStream()))
        assertNull(BackupCodec.readHeader("plain".byteInputStream()))
        val other = BackupFixtures.gunzip(BackupFixtures.bytes()).replaceFirst(BACKUP_FORMAT_ID, "other-format")
        assertNull(BackupCodec.readHeader(BackupFixtures.gzip(other).inputStream()))
        // A safety copy's header reads only as a trusted copy (the restore list, R7).
        val copy = BackupFixtures.bytes(BackupFixtures.header(kind = BackupKind.PRE_RESTORE_COPY))
        assertNull(BackupCodec.readHeader(copy.inputStream()))
        assertEquals(BackupKind.PRE_RESTORE_COPY, BackupCodec.readHeader(copy.inputStream(), trustedCopy = true)?.kind)
    }

    /** A body whose stream blocks after its first bytes until [release] opens. */
    private class GatedSession(private val inner: FakeUploadSession, body: ByteArray) : NanoHTTPD.IHTTPSession by inner {
        val release = java.util.concurrent.CountDownLatch(1)
        val reading = java.util.concurrent.CountDownLatch(1)
        private val input = object : java.io.InputStream() {
            private var pos = 0
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= body.size) return -1
                if (pos > 0) { reading.countDown(); assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
                val n = minOf(len, body.size - pos, 16)
                body.copyInto(b, off, pos, pos + n)
                pos += n
                return n
            }
        }
        override fun getInputStream(): java.io.InputStream = input
    }

    private fun gated(body: ByteArray = BackupFixtures.bytes()) =
        GatedSession(FakeUploadSession("/r/$token/backup", kind = null, body = body), body)

    @Test
    fun `a second post while the first is still receiving is busy, its body drained`() {
        val s = server()
        val first = gated()
        val firstAnswer = java.util.concurrent.atomic.AtomicReference<NanoHTTPD.Response>()
        val t = Thread { firstAnswer.set(s.serve(first)) }.apply { start() }
        assertTrue(first.reading.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val second = FakeUploadSession("/r/$token/backup", kind = null, body = ByteArray(200_000) { 1 })
        val busy = s.serve(second)
        assertEquals(NanoHTTPD.Response.Status.CONFLICT, busy.status)
        assertEquals(BackupPhoneServer.BUSY, busy.bodyText())
        assertEquals(0, second.stream.available())
        first.release.countDown()
        t.join(5_000)
        assertFalse(t.isAlive)
        assertEquals(NanoHTTPD.Response.Status.OK, firstAnswer.get().status)
        assertEquals(1, pending.size)
    }

    @Test
    fun `stop while a post is receiving publishes nothing and leaves no file`() {
        val s = server()
        val first = gated()
        val answer = java.util.concurrent.atomic.AtomicReference<NanoHTTPD.Response>()
        val t = Thread { answer.set(s.serve(first)) }.apply { start() }
        assertTrue(first.reading.await(5, java.util.concurrent.TimeUnit.SECONDS))
        s.stop()
        first.release.countDown()
        t.join(5_000)
        assertFalse(t.isAlive)
        // Its file was swept mid-write (400) or the slot moved before publishing (404): never "sent".
        assertTrue("${answer.get().status}", answer.get().status != NanoHTTPD.Response.Status.OK)
        assertTrue(pending.isEmpty() && uploads().isEmpty())
    }

    @Test
    fun `a pre-restore copy and an oversized section from the phone are refused`() {
        val s = server()
        val copy = BackupFixtures.bytes(BackupFixtures.header(kind = BackupKind.PRE_RESTORE_COPY))
        assertEquals(BackupPhoneServer.DAMAGED, post(s, copy).bodyText())
        val bomb = BackupFixtures.gzip(BackupFixtures.gunzip(BackupFixtures.bytes()).lines().first() + "\n" + "x".repeat(33 * 1024 * 1024))
        assertEquals(BackupPhoneServer.TOO_LARGE, post(s, bomb).bodyText())
        assertTrue(pending.isEmpty() && uploads().isEmpty())
    }

    @Test
    fun `script-safe text cannot close the page's script`() {
        with(BackupPhoneWebPage) {
            assertEquals("\\x3C/script>\\r\\u2028", "</script>\r\u2028".scriptSafe())
        }
    }

    @Test
    fun `stop deletes what was received, stopListening keeps a waiting upload`() {
        val s = server()
        post(s)
        val upload = pending.single()
        s.stopListening()
        assertTrue(upload.file.exists())
        s.stop()
        assertFalse(upload.file.exists())
    }

    @Test
    fun `a new session clears uploads a killed process left`() {
        uploadDir.mkdirs()
        val leftover = File(uploadDir, "upload-old.json.gz").apply { writeText("x") }
        val s = BackupPhoneServer.startOnAvailablePort(
            BackupPhoneMode.RESTORE, { "" }, { emptyList() }, { _: String -> null }, uploadDir, BackupFixtures.INSTALLED, { _, _ -> }, startPort = 18180,
        )
        try {
            assertFalse(leftover.exists())
            assertTrue(s != null && s.listeningPort in 18180..18189)
        } finally {
            s?.stop()
        }
    }

    // --- The session ----------------------------------------------------------------------------

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a session hands over its uploads, keeps a pending one past its 10 minutes, and close deletes it`() {
        val scope = TestScope(StandardTestDispatcher())
        val servers = mutableListOf<BackupPhoneServer>()
        val session = BackupPhoneSession(scope, { mode, handler ->
            val s = BackupPhoneServer(token, mode, { "" }, { emptyList() }, { _: String -> null }, uploadDir, BackupFixtures.INSTALLED, true, handler, null, 0)
            servers += s
            BackupPhoneStart.Started(s, "http://192.168.0.2:8180/r/$token/")
        })
        assertEquals(BackupPhoneOpen.Opened, session.open(BackupPhoneMode.RESTORE))
        assertEquals(BackupPhoneMode.RESTORE, session.state.value.mode)
        post(servers.single())
        val upload = session.state.value.pending!!
        scope.advanceTimeBy(BackupPhoneSession.LIFETIME.inWholeMilliseconds + 1)
        scope.runCurrent()
        assertNull(session.state.value.url)
        assertEquals(upload, session.state.value.pending)
        assertTrue(upload.file.exists())
        session.close()
        assertFalse(upload.file.exists())
        assertEquals(BackupPhoneState(), session.state.value)
        // An upload from a session already closed is dropped and its file deleted.
        val stale = servers.single()
        post(stale)
        assertNull(session.state.value.pending)
        assertTrue(uploads().isEmpty())
    }

    @Test
    fun `release on the session frees the slot and deletes the file`() {
        val scope = TestScope(StandardTestDispatcher())
        var server: BackupPhoneServer? = null
        val session = BackupPhoneSession(scope, { mode, handler ->
            server = BackupPhoneServer(token, mode, { "" }, { emptyList() }, { _: String -> null }, uploadDir, BackupFixtures.INSTALLED, true, handler, null, 0)
            BackupPhoneStart.Started(server!!, "u")
        })
        session.open(BackupPhoneMode.RESTORE)
        post(server!!)
        val upload = session.state.value.pending!!
        session.release(upload.id)
        assertNull(session.state.value.pending)
        assertFalse(upload.file.exists())
        assertEquals(NanoHTTPD.Response.Status.OK, post(server!!).status)
        assertEquals(BackupPhoneOpen.NoNetwork, BackupPhoneSession(scope, { _, _ -> BackupPhoneStart.NoNetwork }).open(BackupPhoneMode.SAVE))
    }
}
