package com.nuvio.tv.core.backup

import android.content.Context
import com.nuvio.tv.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.io.encoding.Base64
import kotlin.time.Clock

/** Spec 101 R10: the one switch for every restore action (and S6's upload route). */
object BackupFeatures {
    /** On since S3 landed the startup applier. Turn off to ship a build that offers export only. */
    const val RESTORE_ENABLED = true
}

/** Where a restore reads its backup from. */
sealed interface RestoreSource {
    /** A pre-restore copy from [PreRestoreCopies.list]: read as a trusted copy only when it is one (R7). */
    data class Copy(val file: File) : RestoreSource

    /** Any other backup (a manual backup, the phone upload); [compressedLimit] caps its raw bytes. */
    class Stream(val compressedLimit: Long? = null, val open: () -> InputStream) : RestoreSource
}

/** What the user ticked on the restore screen (mockup b8, b9, b10). */
data class RestoreRequest(
    /** Backup profile ids; each restores into its match (R1). */
    val profiles: Set<Int>,
    /** This TV settings (D5: unticked by default). */
    val device: Boolean,
    /** Passwords and keys: opened with [pin] from a manual backup, unsealed from a copy. */
    val secrets: Boolean,
    val pin: String? = null,
) {
    /** Never prints the PIN. */
    override fun toString(): String = "RestoreRequest(profiles=$profiles, device=$device, secrets=$secrets)"
}

sealed interface RestoreStageResult {
    /** Staged, with [copy] saved first. Restart the app (AppRestarter) to apply it. */
    data class Staged(val copy: File) : RestoreStageResult

    /** The file did not read as a restorable backup: NeedsNewerApp, TooOld or Invalid (b12, b6). */
    data class Refused(val read: BackupReadResult) : RestoreStageResult

    /** Passwords and keys ticked and the PIN does not open them (b10); nothing was written. */
    data object WrongPin : RestoreStageResult

    /** Not enough free space on this TV to stage it; nothing was written. */
    data object NoSpace : RestoreStageResult

    /** The pre-restore copy could not be made, so nothing restores (R9). */
    data class CopyFailed(val export: BackupExportResult) : RestoreStageResult

    /** [detail] is fixed text for logs. */
    data class Failed(val detail: String) : RestoreStageResult

    /** [BackupFeatures.RESTORE_ENABLED] is off. */
    data object Disabled : RestoreStageResult
}

/**
 * Spec 101 S3, R4-R6, R9: turns a confirmed restore into staged files that the startup applier
 * (RestoreApplier, before any store opens) writes after a restart. Order: read and check the backup and
 * open its secrets (a wrong PIN changes nothing); stage the chosen parts; make the pre-restore copy;
 * then write the marker. The backup's body is dropped before the copy is exported, so the two never hold
 * their memory budget at once. Anything failing before the marker leaves no staging behind.
 */
@Singleton
class RestoreStager internal constructor(
    private val filesDir: File,
    private val sharedPrefs: SharedPrefsFiles,
    private val accountPrefsName: String,
    private val copies: PreRestoreCopies,
    private val versionCode: Int,
    private val clock: Clock,
    private val io: CoroutineDispatcher,
    private val enabled: Boolean,
    private val usableSpace: () -> Long = { filesDir.usableSpace },
) {
    @Inject
    constructor(@ApplicationContext context: Context, copies: PreRestoreCopies) : this(
        context.filesDir,
        // Named: the catalog guard reads a capitalised name after `filesDir,` as a store constant.
        sharedPrefs = ContextSharedPrefsFiles(context),
        BackupAccountCheck.accountPrefsName(context),
        copies,
        BuildConfig.VERSION_CODE,
        Clock.System,
        Dispatchers.IO,
        BackupFeatures.RESTORE_ENABLED,
    )

    private val dirs = RestoreDirs(filesDir)
    private val mutex = Mutex()

    suspend fun stage(source: RestoreSource, request: RestoreRequest): RestoreStageResult = mutex.withLock {
        withContext(io) {
            if (!enabled) return@withContext RestoreStageResult.Disabled
            // An earlier staging still here was never applied (its restart did not happen): this one replaces it.
            dirs.discardStaging()
            var markerWritten = false
            try {
                val signedOut = BackupAccountCheck.isSignedOut(filesDir, sharedPrefs.read(accountPrefsName))
                val profiles = when (val payload = stagePayload(source, request, signedOut)) {
                    is Payload.Staged -> payload.profiles
                    is Payload.NotStaged -> return@withContext payload.result
                }
                val copy = when (val made = copies.make()) {
                    is CopyResult.Made -> made.file
                    is CopyResult.NotMade -> return@withContext RestoreStageResult.CopyFailed(made.export)
                }
                // From the marker on the restore is confirmed: a cancelled caller must not leave the copies
                // unpruned or the marker half-done.
                withContext(NonCancellable) {
                    try {
                        val marker = RestoreMarker(versionCode, clock.now().toEpochMilliseconds(), attempts = 0, signedOut, profiles)
                        PrefsFiles.write(dirs.marker, marker.toPreferences())
                        markerWritten = true
                    } finally {
                        // The new copy and the one being restored are never pruned (R9), whatever the clock did.
                        copies.prune(keep = setOfNotNull(copy, (source as? RestoreSource.Copy)?.file))
                    }
                }
                RestoreStageResult.Staged(copy)
            } catch (e: Exception) {
                // I/O, or a content provider refusing the source; anything else is a bug and propagates.
                if (e !is IOException && e !is SecurityException) throw e
                RestoreStageResult.Failed("stage: ${e.javaClass.simpleName}")
            } finally {
                if (!markerWritten) dirs.discardStaging()
            }
        }
    }

    /** R5's signed-out test, as staging reads it: the restore screen shows b8 when true, b9 when false. */
    suspend fun isSignedOut(): Boolean = withContext(io) {
        BackupAccountCheck.isSignedOut(filesDir, sharedPrefs.read(accountPrefsName))
    }

    private sealed interface Payload {
        data class Staged(val profiles: List<BackupProfile>) : Payload
        data class NotStaged(val result: RestoreStageResult) : Payload
    }

    /** Reads, checks and stages the backup; its body is unreachable once this returns. */
    private suspend fun stagePayload(source: RestoreSource, request: RestoreRequest, signedOut: Boolean): Payload {
        val trustedCopy = source is RestoreSource.Copy && copies.isCopy(source.file)
        val read = when (source) {
            is RestoreSource.Copy -> source.file.inputStream().use { BackupCodec.read(it, versionCode, trustedCopy = trustedCopy) }
            is RestoreSource.Stream -> source.open().use { BackupCodec.read(it, versionCode, source.compressedLimit) }
        }
        if (read !is BackupReadResult.Ok) return Payload.NotStaged(RestoreStageResult.Refused(read))
        val secrets = when {
            !request.secrets -> emptyList()
            read.header.kind == BackupKind.PRE_RESTORE_COPY -> read.body.copySecrets
            read.body.sealedSecrets == null -> emptyList()
            else -> try {
                BackupCodec.openSecrets(read, request.pin ?: return Payload.NotStaged(RestoreStageResult.WrongPin))
            } catch (_: WrongBackupPinException) {
                return Payload.NotStaged(RestoreStageResult.WrongPin)
            } catch (e: BackupCheckException) {
                val reason = if (e.tooLarge) BackupReadResult.Reason.TOO_LARGE else BackupReadResult.Reason.DAMAGED
                return Payload.NotStaged(RestoreStageResult.Refused(BackupReadResult.Invalid(reason, e.detail)))
            }
        }
        val ticked = request.profiles.filterTo(HashSet()) { id -> read.header.profiles.any { it.id == id } }
        val toStage = (read.body.entries + secrets).filter { isChosen(it, ticked, request.device, signedOut) }
        // The staged files, then the apply's temp file and the copy beside them: SectionCost is at least
        // each entry's size on disk.
        if (usableSpace() < 2 * toStage.sumOf { SectionCost.of(it) } + SPACE_MARGIN_BYTES) return Payload.NotStaged(RestoreStageResult.NoSpace)
        for (id in ticked) {
            if (!dirs.stagedProfile(id).mkdirs()) throw IOException("no staging folder")
        }
        for (entry in toStage) writeStaged(entry)
        return Payload.Staged(read.header.profiles)
    }

    /**
     * R4 at staging: a ticked profile's stores (signed in, only those the account does not sync, D29);
     * This TV settings when ticked; the passwords and keys already chosen by the caller; My photos with
     * any ticked profile. The applier applies the same rule again.
     */
    private fun isChosen(entry: BackupEntry, ticked: Set<Int>, device: Boolean, signedOut: Boolean): Boolean {
        val store = BackupCatalog.store(entry.store) ?: return false
        if (!store.restores(signedOut)) return false
        return when {
            entry is BackupEntry.Photo -> ticked.isNotEmpty()
            store.perProfile -> (entry as BackupEntry.Prefs).profile in ticked
            store.group == BackupGroup.DEVICE -> device
            else -> true
        }
    }

    /** [entry] passed BackupChecks, so its store is in the catalog. */
    private suspend fun writeStaged(entry: BackupEntry) {
        val store = checkNotNull(BackupCatalog.store(entry.store))
        when (entry) {
            is BackupEntry.Prefs -> {
                val file = if (store.perProfile) dirs.stagedProfileStore(entry.profile!!, store) else dirs.stagedTvStore(store)
                PrefsFiles.write(file, backupPreferences(entry.values))
            }
            is BackupEntry.TextFile -> writeAtomically(dirs.stagedTvStore(store)) { it.write(entry.text.encodeToByteArray()) }
            is BackupEntry.Photo -> writeAtomically(File(dirs.stagedPhotos, entry.name)) { it.write(Base64.decode(entry.data)) }
        }
    }

    private companion object {
        const val SPACE_MARGIN_BYTES = 16L * 1024 * 1024
    }
}
