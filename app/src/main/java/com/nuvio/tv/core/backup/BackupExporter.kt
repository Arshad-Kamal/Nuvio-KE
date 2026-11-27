package com.nuvio.tv.core.backup

import android.content.Context
import android.os.Build
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.local.ProfileDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock

/** What an export did (Spec 101 R9, D41). */
sealed interface BackupExportResult {
    /** The file was written and synced; [sizeBytes] is its size on disk. */
    data class Written(val sizeBytes: Long) : BackupExportResult

    /** Saved as a manual backup (ManualBackups). */
    data class Saved(val backup: SavedBackup) : BackupExportResult

    /**
     * Over a limit (D41, b13): nothing was kept. [store] and [profile] name the one part over its own limit
     * (a [BackupCatalog] id; profile for per-profile stores); both null when the backup as a whole is over
     * (size cap, memory budget, the phone's 20 MB).
     */
    data class TooLarge(val detail: String, val store: String?, val profile: Int?) : BackupExportResult

    /** A store could not be read or the file could not be written; [detail] is fixed text for logs. */
    data class Failed(val detail: String) : BackupExportResult
}

/**
 * Makes manual backup files (Spec 101 S2): every PROFILE and DEVICE store of every profile on this TV,
 * plus the passwords and keys sealed by the PIN when one is given (D2); and pre-restore copies (S3). One
 * export at a time, so two never hold their memory at once.
 */
@Singleton
class BackupExporter internal constructor(
    private val filesDir: File,
    private val sharedPrefs: (String) -> Map<String, *>,
    private val profiles: suspend () -> List<BackupProfile>,
    private val clock: Clock,
    private val io: CoroutineDispatcher,
    private val deviceName: String = "",
) {
    @Inject
    constructor(@ApplicationContext context: Context, profileDataStore: ProfileDataStore) : this(
        context.filesDir,
        { name -> context.getSharedPreferences(name, Context.MODE_PRIVATE).all },
        { profileDataStore.profilesList.first().map { BackupProfile(it.id, it.name, it.usesPrimaryAddons) } },
        Clock.System,
        Dispatchers.IO,
        Build.MODEL.orEmpty().take(BackupLimits.MAX_DEVICE_NAME_CHARS),
    )

    private val mutex = Mutex()

    /**
     * Writes a backup to [target], replacing it; [target] exists afterwards only on [BackupExportResult.Written].
     * A caller cancelled meanwhile gets the cancellation instead, and owns deleting [target].
     * [phoneBound] refuses a file over [BackupLimits.PHONE_COMPRESSED_MAX_BYTES] (R9). [pin] must be a valid
     * backup PIN ([BackupCrypto.isValidPin]) or null.
     */
    suspend fun export(target: File, pin: String?, phoneBound: Boolean = false): BackupExportResult =
        export(target, BackupKind.MANUAL, pin, phoneBound)

    /**
     * Writes a pre-restore copy (D11, R9) to [target]: everything a restore could replace (so not My
     * photos, which a restore only adds to), the passwords and keys unsealed (the copy stays app-private).
     * Same outcomes as [export].
     */
    internal suspend fun exportCopy(target: File): BackupExportResult =
        export(target, BackupKind.PRE_RESTORE_COPY, pin = null, phoneBound = false)

    /**
     * What a backup made now would hold, for the Back up panel's counts (mockup b2, b4); null when the
     * profile list cannot be read. Reads only the stores it counts.
     */
    suspend fun summary(): BackupSummary? = withContext(io) {
        val profiles = try {
            profiles()
        } catch (_: IOException) {
            return@withContext null
        }
        BackupSummary.of(profiles, BackupSnapshot(filesDir, sharedPrefs))
    }

    private suspend fun export(target: File, kind: BackupKind, pin: String?, phoneBound: Boolean): BackupExportResult =
        mutex.withLock {
            withContext(io) {
                val profiles = try {
                    profiles()
                } catch (e: IOException) {
                    target.delete()
                    return@withContext BackupExportResult.Failed("profiles: ${e.javaClass.simpleName}")
                }
                val header = BackupHeader(
                    format = BACKUP_FORMAT_ID,
                    formatVersion = BACKUP_FORMAT_VERSION,
                    appVersionCode = BuildConfig.VERSION_CODE,
                    appVersionName = BuildConfig.VERSION_NAME,
                    createdAtMs = clock.now().toEpochMilliseconds(),
                    kind = kind,
                    profiles = profiles,
                    sealed = pin != null,
                    deviceName = deviceName,
                )
                writeBackupFile(target, BackupSnapshot(filesDir, sharedPrefs), header, pin, phoneBound)
            }
        }
}

/** The groups a manual backup holds in the clear, and those it seals (D2). */
private val PLAIN_GROUPS = setOf(BackupGroup.PROFILE, BackupGroup.DEVICE)
private val SECRET_GROUPS = setOf(BackupGroup.PROFILE_SECRET, BackupGroup.TV_SECRET)

/**
 * The export itself, blocking (call on an IO thread): reads [snapshot] for [header]'s profiles, seals the
 * secrets when [pin] is set ([header] must say so) or, for a [BackupKind.PRE_RESTORE_COPY], holds them
 * unsealed, writes [target] and syncs it. BackupCodec.write checks everything before its first byte, and
 * [target] is deleted on every outcome but Written.
 */
internal suspend fun writeBackupFile(
    target: File,
    snapshot: BackupSnapshot,
    header: BackupHeader,
    pin: String?,
    phoneBound: Boolean,
    iterations: Int = BackupCrypto.ITERATIONS,
    phoneLimit: Long = BackupLimits.PHONE_COMPRESSED_MAX_BYTES,
): BackupExportResult {
    require(header.sealed == (pin != null)) { "header.sealed must match the PIN" }
    val copy = header.kind == BackupKind.PRE_RESTORE_COPY
    require(!copy || pin == null) { "a pre-restore copy is never sealed" }
    var kept = false
    try {
        val ids = header.profiles.map { it.id }
        // One budget for everything held at once: the plain entries and the secrets read beside them.
        val held = SectionLimits(0)
        // A copy leaves My photos out: a restore only adds photos, never replaces or deletes one (R4), so
        // the copy has nothing of them to undo, and a full gallery would fill most of its budget.
        val entries = snapshot.entries(ids, PLAIN_GROUPS, held, withPhotos = !copy)
        val secrets = if (pin != null || copy) snapshot.entries(ids, SECRET_GROUPS, held) else emptyList()
        val body = if (copy) {
            BackupBody(entries, copySecrets = secrets)
        } else {
            val box = pin?.let { BackupCodec.sealSecrets(header, secrets, it, iterations) }
            // The reader holds the box beside the opened secrets (BackupCodec.openSecrets): charge it too,
            // so every file written opens within the same budget.
            box?.let { held.charge(SectionCost.of(BackupLine.Sealed(it))) }
            BackupBody(entries, box)
        }
        FileOutputStream(target).use { out ->
            BackupCodec.write(out, header, body)
            out.fd.sync()
        }
        val size = target.length()
        // Unreachable today (the 40 MiB budget keeps a file near 15 MB); kept as R9's own rule.
        if (phoneBound && size > phoneLimit) {
            return BackupExportResult.TooLarge("phone size", store = null, profile = null)
        }
        kept = true
        return BackupExportResult.Written(size)
    } catch (e: BackupCheckException) {
        return if (e.tooLarge) BackupExportResult.TooLarge(e.detail, e.store, e.profile) else BackupExportResult.Failed(e.detail)
    } catch (e: BackupTooLargeException) {
        return BackupExportResult.TooLarge(e.detail, store = null, profile = null)
    } catch (e: IOException) {
        return BackupExportResult.Failed("io: ${e.javaClass.simpleName}")
    } finally {
        if (!kept) target.delete()
    }
}
