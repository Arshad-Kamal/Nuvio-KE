package com.nuvio.tv.ui.screens.profile

import com.nuvio.tv.core.server.FakeUploadSession
import com.nuvio.tv.core.server.newSessionToken
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import com.nuvio.tv.core.server.PhotoCodec
import com.nuvio.tv.core.server.PhotoBounds
import com.nuvio.tv.core.server.PhotoUploadServer
import com.nuvio.tv.data.local.MyPhoto
import com.nuvio.tv.data.local.MyPhotoAddResult
import com.nuvio.tv.data.local.MyPhotoKind
import com.nuvio.tv.data.local.MyPhotosStore
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 44d: one upload session at a time, confirm by pending id, stale sessions dropped, the lifetime stop. */
@OptIn(ExperimentalCoroutinesApi::class)
class PhotoUploadControllerTest {

    private val photos = MutableStateFlow<List<MyPhoto>?>(emptyList())
    private val added = mutableListOf<Pair<MyPhotoKind, ByteArray>>()
    private var addResult: MyPhotoAddResult = MyPhotoAddResult.Failed
    private val started = mutableListOf<PhotoUploadServer>()
    private var startResult: (() -> PhotoUploadStart)? = null

    private val acceptingCodec = object : PhotoCodec {
        override fun bounds(bytes: ByteArray): PhotoBounds = PhotoBounds("image/jpeg", 512, 512)
        override fun reencodeJpeg(bytes: ByteArray, width: Int, height: Int): ByteArray = bytes
    }

    @After
    fun stopServers() = started.forEach { it.stop() }

    /**
     * A real server on loopback, wired to the controller's callbacks as the starter wires them, so
     * the tests see its slot (409 / 200) and whether it still runs.
     */
    private fun TestScope.controller(
        addPhoto: suspend (MyPhotoKind, ByteArray) -> MyPhotoAddResult = { kind, jpeg ->
            added += kind to jpeg
            addResult
        }
    ): PhotoUploadController = PhotoUploadController(
        scope = backgroundScope,
        start = { isFull, onPending ->
            startResult?.invoke() ?: PhotoUploadServer(newSessionToken(), { "" }, isFull, acceptingCodec, onPending, "127.0.0.1", 0)
                .also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
                .let { server ->
                    started += server
                    PhotoUploadStart.Started(server, server.url("10.0.0.2"))
                }
        },
        photos = photos,
        addPhoto = addPhoto
    )

    /** POSTs an avatar to [server] under its own token (the QR address's path); the status. */
    private fun upload(server: PhotoUploadServer, body: ByteArray = byteArrayOf(1, 2)): NanoHTTPD.Response.Status {
        val path = java.net.URI(server.url("10.0.0.2")).path + "photo"
        return server.serve(FakeUploadSession(path, body = body)).status as NanoHTTPD.Response.Status
    }

    private fun urlOf(n: Int): String = started[n - 1].url("10.0.0.2")

    private fun myPhoto(n: Int) = MyPhoto("avatar-$n", MyPhotoKind.Avatar, Instant.fromEpochMilliseconds(n.toLong()), File("x"))

    @Test
    fun `open at the cap or before the gallery loads says full and starts nothing`() = runTest {
        val controller = controller()
        photos.value = List(MyPhotosStore.CAP) { myPhoto(it) }
        assertEquals(PhotoUploadOpen.Full, controller.open())
        photos.value = null
        assertEquals(PhotoUploadOpen.Full, controller.open())
        assertTrue(started.isEmpty())
        assertNull(controller.state.value.url)
    }

    @Test
    fun `open shows the address, and a failed start says why`() = runTest {
        val controller = controller()
        assertEquals(PhotoUploadOpen.Opened, controller.open())
        assertEquals(urlOf(1), controller.state.value.url)
        assertTrue(started.single().isAlive)
        startResult = { PhotoUploadStart.NoNetwork }
        assertEquals(PhotoUploadOpen.NoNetwork, controller.open())
        assertNull(controller.state.value.url)
        assertFalse(started.single().isAlive)
        startResult = { PhotoUploadStart.NoPort }
        assertEquals(PhotoUploadOpen.NoPort, controller.open())
    }

    @Test
    fun `confirm adds only the pending photo it names, then frees the slot`() = runTest {
        addResult = MyPhotoAddResult.Added(myPhoto(1))
        val controller = controller()
        controller.open()
        val server = started.single()
        assertEquals(Status.OK, upload(server, byteArrayOf(7, 7)))
        val first = controller.state.value.pending!!
        controller.confirm("other")
        runCurrent()
        assertTrue(added.isEmpty())
        assertSame(first, controller.state.value.pending)
        assertEquals(Status.CONFLICT, upload(server))

        controller.confirm(first.id)
        runCurrent()
        assertEquals(listOf(MyPhotoKind.Avatar), added.map { it.first })
        assertArrayEquals(byteArrayOf(7, 7), added.single().second)
        assertNull(controller.state.value.pending)
        assertEquals(PhotoUploadNotice.Added, controller.state.value.notice)
        assertEquals(urlOf(1), controller.state.value.url)
        assertEquals(Status.OK, upload(server))
    }

    @Test
    fun `a stale OK after a discard cannot add the next photo`() = runTest {
        val controller = controller()
        controller.open()
        val server = started.single()
        upload(server)
        val first = controller.state.value.pending!!.id
        controller.discard(first)
        assertNull(controller.state.value.pending)
        assertEquals(Status.OK, upload(server))
        val second = controller.state.value.pending!!.id
        controller.confirm(first)
        controller.discard(first)
        runCurrent()
        assertTrue(added.isEmpty())
        assertEquals(second, controller.state.value.pending?.id)
    }

    @Test
    fun `a photo from a closed or replaced session is dropped, and replaced servers stop`() = runTest {
        val controller = controller()
        controller.open()
        val first = started.single()
        controller.open()
        assertFalse(first.isAlive)
        // A decode that outlived its session: the stopped server still publishes, the controller drops it.
        upload(first)
        assertNull(controller.state.value.pending)
        upload(started.last())
        assertTrue(controller.state.value.pending != null)
        controller.close()
        assertFalse(started.last().isAlive)
    }

    @Test
    fun `close drops the card and the pending photo`() = runTest {
        val controller = controller()
        controller.open()
        upload(started.single())
        val id = controller.state.value.pending!!.id
        controller.close()
        assertEquals(PhotoUploadState(), controller.state.value)
        controller.confirm(id)
        runCurrent()
        assertTrue(added.isEmpty())
    }

    @Test
    fun `the lifetime closes the card but keeps a photo already shown`() = runTest {
        addResult = MyPhotoAddResult.Full
        val controller = controller()
        controller.open()
        val server = started.single()
        upload(server)
        val id = controller.state.value.pending!!.id
        advanceTimeBy(PhotoUploadController.LIFETIME - 1.milliseconds)
        assertEquals(urlOf(1), controller.state.value.url)
        advanceTimeBy(2.milliseconds)
        assertNull(controller.state.value.url)
        assertFalse(server.isAlive)
        assertEquals(id, controller.state.value.pending?.id)

        controller.confirm(id)
        runCurrent()
        assertEquals(1, added.size)
        assertEquals(PhotoUploadNotice.Full, controller.state.value.notice)
        controller.noticeShown()
        assertNull(controller.state.value.notice)
    }

    @Test
    fun `the lifetime clears an outcome the card showed`() = runTest {
        addResult = MyPhotoAddResult.Failed
        val controller = controller()
        controller.open()
        upload(started.single())
        controller.confirm(controller.state.value.pending!!.id)
        runCurrent()
        assertEquals(PhotoUploadNotice.Failed, controller.state.value.notice)
        advanceTimeBy(PhotoUploadController.LIFETIME + 1.milliseconds)
        assertNull(controller.state.value.url)
        assertNull(controller.state.value.notice)
    }

    @Test
    fun `an outcome that lands after the card was closed is dropped`() = runTest {
        addResult = MyPhotoAddResult.Failed
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val controller = controller(addPhoto = { _, _ -> gate.await(); addResult })
        controller.open()
        upload(started.single())
        controller.confirm(controller.state.value.pending!!.id)
        runCurrent()
        // The slot stays held until the write is done: no second photo overtakes this one.
        assertEquals(Status.CONFLICT, upload(started.single()))
        controller.close()
        gate.complete(Unit)
        runCurrent()
        assertNull(controller.state.value.notice)
    }

    @Test
    fun `a new session restarts the lifetime`() = runTest {
        val controller = controller()
        controller.open()
        advanceTimeBy(PhotoUploadController.LIFETIME - 1.milliseconds)
        controller.open()
        advanceTimeBy(2.milliseconds)
        assertEquals(urlOf(2), controller.state.value.url)
        advanceTimeBy(PhotoUploadController.LIFETIME)
        assertNull(controller.state.value.url)
    }
}
