package com.nuvio.tv.core.backup

import android.content.Context
import com.nuvio.tv.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock

/** A pre-restore copy made, or why not. */
internal sealed interface CopyResult {
    data class Made(val file: File) : CopyResult

    /** [export] is TooLarge or Failed: the restore does not go ahead (R9). */
    data class NotMade(val export: BackupExportResult) : CopyResult
}

/**
 * The safety copies made before every restore (Spec 101 D11, R9): a full export of everything a restore
 * could replace, secrets unsealed, kept app-private in `files/backup_restore/copies/`, the newest [KEEP]
 * kept. A copy counts only once it reads back as a valid backup; until then it is a temp file the list
 * never shows. The sign-out wipe deletes the folder (D23, S4).
 */
@Singleton
class PreRestoreCopies internal constructor(
    private val dir: File,
    private val exporter: BackupExporter,
    private val versionCode: Int,
    private val clock: Clock,
    private val io: CoroutineDispatcher,
) {
    @Inject
    constructor(@ApplicationContext context: Context, exporter: BackupExporter) :
        this(RestoreDirs(context.filesDir).copies, exporter, BuildConfig.VERSION_CODE, Clock.System, Dispatchers.IO)

    /** The copies, newest first. */
    suspend fun list(): List<File> = withContext(io) { copies() }

    /** True for a file in this folder under a copy's name: the only source a copy-kind backup is read from (R7). */
    internal fun isCopy(file: File): Boolean =
        NAME.matches(file.name) && file.canonicalFile.parentFile == dir.canonicalFile

    internal suspend fun make(): CopyResult = withContext(io) {
        if (!dir.isDirectory && !dir.mkdirs()) return@withContext CopyResult.NotMade(BackupExportResult.Failed("no copies folder"))
        val tmp = File(dir, "copy.tmp")
        try {
            val written = exporter.exportCopy(tmp)
            if (written !is BackupExportResult.Written) return@withContext CopyResult.NotMade(written)
            val read = tmp.inputStream().use { BackupCodec.read(it, versionCode, trustedCopy = true) }
            if (read !is BackupReadResult.Ok) return@withContext CopyResult.NotMade(BackupExportResult.Failed("copy does not read back"))
            val target = freeName(clock.now().toEpochMilliseconds())
            if (!tmp.renameTo(target)) return@withContext CopyResult.NotMade(BackupExportResult.Failed("copy rename"))
            CopyResult.Made(target)
        } catch (e: IOException) {
            CopyResult.NotMade(BackupExportResult.Failed("copy io: ${e.javaClass.simpleName}"))
        } finally {
            tmp.delete()
        }
    }

    /** Keeps the newest [KEEP] and every file in [keep] (the copy just made, the copy being restored: R9). */
    internal suspend fun prune(keep: Set<File>) = withContext(io) {
        // Best effort: a prune that fails leaves an extra copy, never a failed restore.
        try {
            val kept = keep.mapTo(HashSet()) { it.canonicalFile }
            for (old in copies().drop(KEEP)) {
                if (old.canonicalFile !in kept) old.delete()
            }
        } catch (_: IOException) {
        }
    }

    private fun copies(): List<File> =
        dir.listFiles().orEmpty()
            .filter { it.isFile && NAME.matches(it.name) }
            .sortedByDescending { NAME.matchEntire(it.name)!!.groupValues[1].toLong() }

    /** `copy-<millis>.json.gz`, the next free millisecond when two copies share one. */
    private fun freeName(nowMs: Long): File =
        generateSequence(nowMs) { it + 1 }.map { File(dir, "copy-$it${ManualBackups.EXTENSION}") }.first { !it.exists() }

    internal companion object {
        const val KEEP = 2
        private val NAME = Regex("""copy-(\d{1,18})""" + Regex.escape(ManualBackups.EXTENSION))
    }
}
