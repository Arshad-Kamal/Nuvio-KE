package com.nuvio.tv.core.backup

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock

/** A manual backup this install saved. [id] is the folder's own handle (a content URI or a path). */
data class SavedBackup(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    /** Newest is largest: MediaStore's row id, or the file's modified time. */
    internal val order: Long,
)

/** Where manual backups live (R9): Downloads › Nuvio KE backups. */
internal interface BackupFolder {
    /** This install's backups in the folder. */
    fun list(): List<SavedBackup>

    /** Copies [source] in as [name] and syncs it; a failure leaves no partial file behind. */
    fun save(source: File, name: String): SavedBackup

    fun delete(backup: SavedBackup): Boolean

    fun open(backup: SavedBackup): InputStream
}

/** Newest first: by [SavedBackup.order], then by name (names carry the minute). */
internal val newestFirst: Comparator<SavedBackup> = compareByDescending<SavedBackup> { it.order }.thenByDescending { it.name }

/** The backups beyond the newest [keep] (R9: keep 3), oldest last. */
internal fun backupsToPrune(backups: List<SavedBackup>, keep: Int): List<SavedBackup> =
    backups.sortedWith(newestFirst).drop(keep)

/**
 * Back up now (Spec 101 S2, R9, D8): exports to a private temp file, copies it into the backups folder,
 * then keeps the newest [KEEP] of this install's backups. A refused or failed export saves nothing and
 * deletes nothing.
 */
@Singleton
class ManualBackups internal constructor(
    private val exporter: BackupExporter,
    private val workDir: File,
    private val folder: () -> BackupFolder?,
    private val clock: Clock,
    private val io: CoroutineDispatcher,
) {
    @Inject
    constructor(@ApplicationContext context: Context, exporter: BackupExporter) : this(
        exporter,
        File(context.filesDir, RestoreDirs.DIR_NAME),
        { downloadsFolder(context) },
        Clock.System,
        Dispatchers.IO,
    )

    private val mutex = Mutex()

    /** [pin]: a valid backup PIN to seal the passwords and keys, or null to leave them out (D2). */
    suspend fun backUpNow(pin: String?): BackupExportResult = mutex.withLock {
        withContext(io) {
            val folder = folder() ?: return@withContext BackupExportResult.Failed("no backups folder")
            workDir.mkdirs()
            val temp = File(workDir, "manual.tmp")
            try {
                val written = exporter.export(temp, pin)
                if (written !is BackupExportResult.Written) return@withContext written
                val saved = storage { folder.save(temp, fileName(clock.now().toEpochMilliseconds())) }
                    ?: return@withContext BackupExportResult.Failed("save failed")
                // A prune that fails leaves an extra old backup; the save stands.
                storage { prune(folder) }
                BackupExportResult.Saved(saved)
            } finally {
                temp.delete()
            }
        }
    }

    /** This install's backups, newest first; empty when the folder cannot be read. */
    suspend fun list(): List<SavedBackup> = withContext(io) {
        storage { folder()?.list() }?.sortedWith(newestFirst).orEmpty()
    }

    /** Opens [backup] to read (the phone page's download); null when storage refuses. The caller closes it. */
    suspend fun open(backup: SavedBackup): InputStream? = withContext(io) {
        storage { folder()?.open(backup) }
    }

    /** [backup] as a restore's source; the stager opens it on its IO thread, and storage refusing is an IOException. */
    fun restoreSource(backup: SavedBackup): RestoreSource = RestoreSource.Stream {
        storage { folder()?.open(backup) } ?: throw IOException("backup did not open")
    }

    /** [block]'s result, or null (logged by type only) when storage refuses: I/O, or MediaStore's runtime errors. */
    private inline fun <T> storage(block: () -> T): T? =
        try {
            block()
        } catch (e: Exception) {
            val storageError = e is IOException || e is SecurityException || e is IllegalStateException || e is IllegalArgumentException
            if (e is CancellationException || !storageError) throw e
            Log.w(TAG, "storage: ${e.javaClass.simpleName}")
            null
        }

    private fun prune(folder: BackupFolder) {
        for (old in backupsToPrune(folder.list(), KEEP)) {
            // A backup that will not delete stays; the next save tries again.
            if (!folder.delete(old)) Log.w(TAG, "could not delete an old backup")
        }
    }

    internal companion object {
        const val KEEP = 3
        const val FOLDER = "Nuvio KE backups"
        /** D43: the file is gzip, so it says so (Arshad, ee0ab9b9). */
        const val EXTENSION = ".json.gz"
        const val MIME = "application/gzip"
        private const val TAG = "ManualBackups"

        /** "Nuvio KE backup 2026-11-16 2142.json.gz" (mockup b1, b4), in this TV's time zone. */
        fun fileName(nowMs: Long): String =
            "Nuvio KE backup " + SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).format(Date(nowMs)) + EXTENSION

        /**
         * API 29+: Downloads via MediaStore, which shows an app only the files it created (R9: this
         * install's). Below 29: the app's external files folder (no permission needed); null without one.
         */
        fun downloadsFolder(context: Context): BackupFolder? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStoreBackupFolder(context.contentResolver)
            } else {
                context.getExternalFilesDir(null)?.let { FileBackupFolder(File(it, FOLDER)) }
            }
    }
}

@RequiresApi(Build.VERSION_CODES.Q)
internal class MediaStoreBackupFolder(private val resolver: ContentResolver) : BackupFolder {
    private val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
    private val relativePath = "Download/${ManualBackups.FOLDER}/"

    override fun list(): List<SavedBackup> {
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE)
        // Pending (unfinished) rows are left out of a query by default.
        return resolver.query(collection, projection, "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf(relativePath), null)
            ?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(0)
                        add(SavedBackup(ContentUris.withAppendedId(collection, id).toString(), cursor.getString(1).orEmpty(), cursor.getLong(2), id))
                    }
                }
            }.orEmpty()
    }

    override fun save(source: File, name: String): SavedBackup {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, ManualBackups.MIME)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("insert refused")
        try {
            val pfd = resolver.openFileDescriptor(uri, "w") ?: throw FileNotFoundException("no descriptor")
            ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { out ->
                source.inputStream().use { it.copyTo(out) }
                out.fd.sync()
            }
            // MediaStore renames a clash ("… (1).json.gz"); read back the name it kept.
            val kept = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
            val published = resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            if (published != 1) throw IOException("publish refused")
            return SavedBackup(uri.toString(), kept ?: name, source.length(), ContentUris.parseId(uri))
        } catch (e: Throwable) {
            // Cancellation included: never leave a half-written or hidden row.
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    override fun delete(backup: SavedBackup): Boolean =
        try {
            resolver.delete(android.net.Uri.parse(backup.id), null, null) > 0
        } catch (_: SecurityException) {
            false
        }

    override fun open(backup: SavedBackup): InputStream =
        resolver.openInputStream(android.net.Uri.parse(backup.id)) ?: throw FileNotFoundException("no stream")
}

/** Below API 29 (R9): a plain folder, written as `.tmp` then renamed so a listed file is always whole. */
internal class FileBackupFolder(private val dir: File) : BackupFolder {

    override fun list(): List<SavedBackup> =
        dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(ManualBackups.EXTENSION) }
            .map { SavedBackup(it.path, it.name, it.length(), it.lastModified()) }

    override fun save(source: File, name: String): SavedBackup {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("no folder")
        // Leftovers of a save cut off by a kill.
        dir.listFiles().orEmpty().filter { it.name.endsWith(TMP) }.forEach { it.delete() }
        val target = freeName(name)
        val tmp = File(dir, target.name + TMP)
        try {
            FileOutputStream(tmp).use { out ->
                source.inputStream().use { it.copyTo(out) }
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) throw IOException("rename failed")
        } finally {
            tmp.delete()
        }
        return SavedBackup(target.path, target.name, target.length(), target.lastModified())
    }

    override fun delete(backup: SavedBackup): Boolean = File(backup.id).delete()

    override fun open(backup: SavedBackup): InputStream = File(backup.id).inputStream()

    /** [name], or "<name> (2).json.gz" and up when taken (two backups in one minute). */
    private fun freeName(name: String): File {
        val base = name.removeSuffix(ManualBackups.EXTENSION)
        return generateSequence(1) { it + 1 }
            .map { n -> File(dir, if (n == 1) name else "$base ($n)${ManualBackups.EXTENSION}") }
            .first { !it.exists() }
    }

    private companion object {
        const val TMP = ".tmp"
    }
}
