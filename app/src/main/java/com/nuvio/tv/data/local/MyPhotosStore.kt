package com.nuvio.tv.data.local

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What a My photo is for, at the exact size the phone sends (Spec 44 D6): a 512x512 avatar or a 1920x1080 background. */
enum class MyPhotoKind(val prefix: String, val width: Int, val height: Int) {
    Avatar("avatar", 512, 512),
    Background("background", 1920, 1080)
}

/** One photo in My photos; [id] is the file name without `.jpg` and is never reused. */
data class MyPhoto(val id: String, val kind: MyPhotoKind, val addedAt: Instant, val file: File)

sealed interface MyPhotoAddResult {
    data class Added(val photo: MyPhoto) : MyPhotoAddResult
    /** At [MyPhotosStore.CAP] (D5): remove one first. */
    data object Full : MyPhotoAddResult
    data object Failed : MyPhotoAddResult
}

/**
 * The shared My photos gallery (Spec 44 D4/D5, Design "My photos store"): every profile on this TV
 * picks from it, nothing is synced. The folder is the index: `<kind>-<addedAtMillis>-<uuid>.jpg`,
 * listed newest first, at most [CAP] files. An add writes `<name>.tmp` then renames, so a crash
 * leaves only a `.tmp` that the next load deletes. Remove deletes the file; a look entry naming
 * it resolves to the synced value (no sweep). Sign-out deletes everything ([deleteAll], D8).
 */
@Singleton
class MyPhotosStore internal constructor(
    private val dir: File,
    private val clock: Clock,
    ioDispatcher: CoroutineDispatcher
) {
    @Inject
    constructor(@ApplicationContext context: Context) :
        this(File(context.filesDir, DIR_NAME), Clock.System, Dispatchers.IO)

    private val io = ioDispatcher
    private val mutex = Mutex()
    private val _photos = MutableStateFlow<List<MyPhoto>?>(null)

    /** Newest first; null until the folder has been read (an editor must wait, or it would drop a photo pick). */
    val photos: StateFlow<List<MyPhoto>?> = _photos.asStateFlow()

    init {
        CoroutineScope(SupervisorJob() + io).launch { load() }
    }

    /**
     * Reads the folder (sweeping leftover `.tmp` files) and publishes the list. An unreadable folder
     * publishes none rather than crash the app (the gallery then shows empty).
     */
    internal suspend fun load() = mutex.withLock {
        withContext(io) {
            _photos.value = try {
                dir.listFiles { file -> file.name.endsWith(TMP_SUFFIX) }?.forEach { it.delete() }
                scan()
            } catch (e: SecurityException) {
                Log.w(TAG, "My photos not readable: ${e.javaClass.simpleName}")
                emptyList()
            }
        }
    }

    /** Adds [jpeg] as a [kind] photo: [MyPhotoAddResult.Full] at the cap, Failed when it cannot be written. */
    suspend fun add(kind: MyPhotoKind, jpeg: ByteArray): MyPhotoAddResult = mutex.withLock {
        withContext(io) {
            val current = _photos.value ?: scan()
            if (current.size >= CAP) return@withContext MyPhotoAddResult.Full
            val addedAt = clock.now()
            val id = "${kind.prefix}-${addedAt.toEpochMilliseconds()}-${UUID.randomUUID()}"
            val file = File(dir, "$id$JPG")
            val tmp = File(dir, "$id$JPG$TMP_SUFFIX")
            try {
                if (!dir.isDirectory && !dir.mkdirs()) throw IOException("no folder")
                tmp.writeBytes(jpeg)
                if (!tmp.renameTo(file)) throw IOException("rename failed")
            } catch (e: IOException) {
                tmp.delete()
                Log.w(TAG, "Photo not added: ${e.javaClass.simpleName}")
                return@withContext MyPhotoAddResult.Failed
            }
            // Millisecond precision, as a later scan parses it from the name: in-memory and reloaded lists stay equal.
            val photo = MyPhoto(id, kind, Instant.fromEpochMilliseconds(addedAt.toEpochMilliseconds()), file)
            _photos.value = listOf(photo) + current
            MyPhotoAddResult.Added(photo)
        }
    }

    /** Deletes photo [id] (only an id the store listed); false when it is unknown or the file stays. */
    suspend fun remove(id: String): Boolean = mutex.withLock {
        withContext(io) {
            val current = _photos.value ?: scan()
            val photo = current.firstOrNull { it.id == id } ?: return@withContext false
            if (photo.file.exists() && !photo.file.delete()) return@withContext false
            _photos.value = current - photo
            true
        }
    }

    /**
     * Sign-out (D8): the next account starts with an empty gallery. The list is re-read afterwards,
     * so a file that would not delete stays listed (and removable) instead of hidden; that case
     * throws for the caller's log.
     */
    suspend fun deleteAll() = mutex.withLock {
        withContext(io) {
            val deleted = dir.deleteRecursively()
            _photos.value = scan()
            if (!deleted) throw IOException("My photos not fully deleted")
        }
    }

    private fun scan(): List<MyPhoto> =
        dir.listFiles().orEmpty()
            .mapNotNull(::parse)
            .sortedByDescending { it.addedAt }

    private fun parse(file: File): MyPhoto? {
        val match = NAME.matchEntire(file.name) ?: return null
        val (prefix, millis) = match.destructured
        val kind = MyPhotoKind.entries.firstOrNull { it.prefix == prefix } ?: return null
        val addedAt = millis.toLongOrNull() ?: return null
        return MyPhoto(file.name.removeSuffix(JPG), kind, Instant.fromEpochMilliseconds(addedAt), file)
    }

    companion object {
        private const val TAG = "MyPhotosStore"
        /** Under `filesDir`. */
        const val DIR_NAME = "my_photos"
        /** D5: avatars and backgrounds together. */
        const val CAP = 12
        private const val JPG = ".jpg"
        private const val TMP_SUFFIX = ".tmp"
        // `<kind prefix>-<millis>-<uuid>.jpg`; anything else in the folder is ignored.
        private val NAME = Regex(
            """([a-z]+)-(\d{1,19})-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.jpg"""
        )
    }
}
