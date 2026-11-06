package com.nuvio.tv.data.local

import java.io.File
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import java.io.IOException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assume.assumeFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Spec 44c: the My photos folder is the index (newest first, cap 12, `.tmp` sweep, remove, sign-out). */
class MyPhotosStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var nowMillis = 1_000L
    // Sub-millisecond nanos, as a real clock gives: the store keeps millisecond precision.
    private val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochSeconds(0, (nowMillis++) * 1_000_000 + 123_456)
    }
    private val jpeg = byteArrayOf(1, 2, 3)

    private fun dir(): File = File(tmp.root, MyPhotosStore.DIR_NAME)

    // Unconfined: the init load and every call run inline, so state is settled on return.
    private fun store(): MyPhotosStore = MyPhotosStore(dir(), clock, Dispatchers.Unconfined)

    private suspend fun MyPhotosStore.addOk(kind: MyPhotoKind): MyPhoto =
        (add(kind, jpeg) as MyPhotoAddResult.Added).photo

    @Test
    fun `adds list newest first and survive a reload`() = runTest {
        val store = store()
        assertEquals(emptyList<MyPhoto>(), store.photos.value)
        val first = store.addOk(MyPhotoKind.Avatar)
        val second = store.addOk(MyPhotoKind.Background)
        assertEquals(listOf(second, first), store.photos.value)
        assertTrue(first.id.startsWith("avatar-1000-"))
        assertEquals(Instant.fromEpochMilliseconds(1_000), first.addedAt)
        assertTrue(first.file.readBytes().contentEquals(jpeg))
        assertEquals(listOf(second, first), store().photos.value)
    }

    @Test
    fun `the list is null until the folder has been read`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val store = MyPhotosStore(dir(), clock, io)
        assertNull(store.photos.value)
        advanceUntilIdle()
        assertEquals(emptyList<MyPhoto>(), store.photos.value)
    }

    @Test
    fun `only exact store names are listed`() = runTest {
        dir().mkdirs()
        val uuid = "0123abcd-0000-4000-8000-0123456789ab"
        listOf(
            "sticker-5-$uuid.jpg", // unknown kind
            "avatar-5-$uuid.png",
            "avatar-x-$uuid.jpg",
            "avatar-5-$uuid.jpg.bak",
            "avatar-5-0123ABCD-0000-4000-8000-0123456789AB.jpg",
            "avatar-99999999999999999999-$uuid.jpg" // over 19 digits
        ).forEach { File(dir(), it).writeBytes(jpeg) }
        File(dir(), "background-7-$uuid.jpg").writeBytes(jpeg)
        assertEquals(listOf("background-7-$uuid"), store().photos.value!!.map { it.id })
    }

    @Test
    fun `a failed delete keeps the photo listed`() = runTest {
        assumeFalse("root ignores folder permissions", System.getProperty("user.name") == "root")
        val store = store()
        val photo = store.addOk(MyPhotoKind.Avatar)
        dir().setWritable(false)
        try {
            assertFalse(store.remove(photo.id))
            assertEquals(listOf(photo), store.photos.value)
            // Sign-out: what would not delete stays listed (removable later), and the caller hears of it.
            assertTrue(runCatching { store.deleteAll() }.exceptionOrNull() is IOException)
            assertEquals(listOf(photo), store.photos.value)
        } finally {
            dir().setWritable(true)
        }
    }

    @Test
    fun `the gallery is full at twelve, avatars and backgrounds together`() = runTest {
        val store = store()
        repeat(6) { store.addOk(MyPhotoKind.Avatar) }
        repeat(6) { store.addOk(MyPhotoKind.Background) }
        assertEquals(MyPhotoAddResult.Full, store.add(MyPhotoKind.Avatar, jpeg))
        assertEquals(MyPhotosStore.CAP, dir().listFiles()!!.size)
        // Removing one frees a space.
        assertTrue(store.remove(store.photos.value!!.last().id))
        assertTrue(store.add(MyPhotoKind.Avatar, jpeg) is MyPhotoAddResult.Added)
    }

    @Test
    fun `a load sweeps leftover tmp files and ignores foreign names`() = runTest {
        val kept = store().addOk(MyPhotoKind.Avatar)
        val leftover = File(dir(), "avatar-5-00000000-0000-0000-0000-000000000000.jpg.tmp").apply { writeBytes(jpeg) }
        File(dir(), "notes.txt").writeText("x")
        val reloaded = store()
        assertFalse(leftover.exists())
        assertEquals(listOf(kept), reloaded.photos.value)
    }

    @Test
    fun `remove deletes only a listed photo`() = runTest {
        val store = store()
        val photo = store.addOk(MyPhotoKind.Avatar)
        assertFalse(store.remove("../notes"))
        assertFalse(store.remove("avatar-1-unknown"))
        assertTrue(store.remove(photo.id))
        assertFalse(photo.file.exists())
        assertEquals(emptyList<MyPhoto>(), store.photos.value)
        assertFalse(store.remove(photo.id))
    }

    @Test
    fun `sign-out deletes the folder and empties the list`() = runTest {
        val store = store()
        store.addOk(MyPhotoKind.Avatar)
        store.deleteAll()
        assertFalse(dir().exists())
        assertEquals(emptyList<MyPhoto>(), store.photos.value)
        // A later add recreates the folder.
        assertTrue(store.add(MyPhotoKind.Background, jpeg) is MyPhotoAddResult.Added)
    }
}
