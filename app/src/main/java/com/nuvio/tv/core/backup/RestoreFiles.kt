package com.nuvio.tv.core.backup

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/*
 * The files a restore works with (Spec 101 R6, R9), all under `files/backup_restore/` (excluded from
 * backups by BackupCatalog). Staging writes the chosen backup content as ready-to-write files (a
 * Preferences proto per prefs store, raw text, raw JPEGs) plus a marker written last; the applier
 * reads them before any store opens. Nothing here parses JSON.
 */

internal class RestoreDirs(filesDir: File) {
    val root = File(filesDir, DIR_NAME)
    val staged = File(root, "staged")
    /** Present only once every staged file is written; its presence is what the applier looks for. */
    val marker = File(root, "staged.preferences_pb")
    val notice = File(root, "notice.preferences_pb")
    val copies = File(root, "copies")
    val stagedPhotos = File(staged, "photos")
    /** Backups received from the phone page (BackupPhoneServer), deleted when its session ends. */
    val phoneUploads = File(root, "phone")

    /** Exists for each profile the user ticked, even when nothing of it is staged. */
    fun stagedProfile(backupId: Int) = File(staged, "p$backupId")

    fun stagedProfileStore(backupId: Int, store: CatalogStore) = File(stagedProfile(backupId), "${store.id}.preferences_pb")

    /** A TV-wide store: a Preferences proto for prefs stores (SharedPreferences too), else the file itself. */
    fun stagedTvStore(store: CatalogStore) = File(staged, "tv/" + if (store.isPrefs) "${store.id}.preferences_pb" else store.id)

    /** Marker first, so a deletion cut off part-way leaves no staging the applier would run. */
    fun discardStaging() {
        marker.delete()
        staged.deleteRecursively()
    }

    companion object {
        /** Under `filesDir`; the sign-out wipe deletes it whole (D23, AccountLocalDataResetService). */
        const val DIR_NAME = "backup_restore"
    }
}

/** SharedPreferences by file name, for the one catalog store of that kind (`app_locale`) and the account check. */
internal interface SharedPrefsFiles {
    fun read(name: String): Map<String, *>

    /** Replaces the whole file with [values] (legal SharedPreferences types); throws IOException on failure. */
    fun replace(name: String, values: Map<String, Any>)
}

internal class ContextSharedPrefsFiles(private val context: Context) : SharedPrefsFiles {
    override fun read(name: String): Map<String, *> = context.getSharedPreferences(name, Context.MODE_PRIVATE).all

    override fun replace(name: String, values: Map<String, Any>) {
        val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
        for ((key, value) in values) {
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.mapTo(HashSet()) { it as String })
                else -> throw IOException("value type")
            }
        }
        // commit() writes and syncs the file before it returns.
        if (!editor.commit()) throw IOException("commit failed")
    }
}

/** Preferences proto files, read and written whole. */
internal object PrefsFiles {
    /** Null when [file] does not exist; throws CorruptionException (an IOException) when it does not parse. */
    suspend fun read(file: File): Preferences? {
        if (!file.isFile) return null
        return file.source().buffer().use { PreferencesSerializer.readFrom(it) }
    }

    /** Writes [prefs] to `<file>.restore-tmp`, syncs it, then renames it over [file]. */
    suspend fun write(file: File, prefs: Preferences) {
        writeAtomically(file) { out ->
            val sink = out.sink().buffer()
            PreferencesSerializer.writeTo(prefs, sink)
            sink.flush()
        }
    }
}

/** The suffix of [writeAtomically]'s temp files; a kill can leave one, which the applier sweeps. */
internal const val RESTORE_TMP = ".restore-tmp"

/** Writes [file] through a synced temp file and a rename, so it is the old file or the whole new one. */
internal inline fun writeAtomically(file: File, write: (FileOutputStream) -> Unit) {
    val parent = file.parentFile
    if (parent != null && !parent.isDirectory && !parent.mkdirs()) throw IOException("no folder")
    val tmp = File(file.path + RESTORE_TMP)
    try {
        FileOutputStream(tmp).use { out ->
            write(out)
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) throw IOException("rename failed")
    } finally {
        tmp.delete()
    }
}

/** A Preferences holding [values] as their own types. */
internal fun backupPreferences(values: Map<String, BackupValue>): Preferences =
    mutablePreferencesOf().apply {
        for ((key, value) in values) {
            when (value) {
                is BackupValue.Str -> this[stringPreferencesKey(key)] = value.value
                is BackupValue.Bool -> this[booleanPreferencesKey(key)] = value.value
                is BackupValue.IntValue -> this[intPreferencesKey(key)] = value.value
                is BackupValue.LongValue -> this[longPreferencesKey(key)] = value.value
                is BackupValue.FloatValue -> this[floatPreferencesKey(key)] = value.value
                is BackupValue.DoubleValue -> this[doublePreferencesKey(key)] = value.value
                is BackupValue.StrSet -> this[stringSetPreferencesKey(key)] = value.value
            }
        }
    }

/**
 * [incoming] with the keys named in [keep] taken from [current] instead (R4: the target's excluded keys
 * survive the replace; a [keep] key [current] lacks is dropped, never taken from the backup).
 */
internal fun withKeptKeys(incoming: Preferences, current: Preferences?, keep: Set<String>): Preferences {
    val out: MutablePreferences = incoming.toMutablePreferences()
    for (key in incoming.asMap().keys) if (key.name in keep) out.remove(key)
    current?.asMap()?.forEach { (key, value) ->
        @Suppress("UNCHECKED_CAST")
        if (key.name in keep) out.putAll((key as Preferences.Key<Any>) to value)
    }
    return out
}

/** What the applier needs from staging time (R6); stored as a Preferences proto, so no JSON before onCreate. */
internal data class RestoreMarker(
    val versionCode: Int,
    val createdAtMs: Long,
    /** Apply runs started; counted before each run (R6: two at most). */
    val attempts: Int,
    /** R5: the account state at staging; the applier refuses a different one. */
    val signedOut: Boolean,
    /** Every profile in the backup, so the applier matches exactly as the restore screen did (R1). */
    val profiles: List<BackupProfile>,
) {
    fun toPreferences(): Preferences = mutablePreferencesOf().apply {
        this[VERSION_CODE] = versionCode
        this[CREATED_AT] = createdAtMs
        this[ATTEMPTS] = attempts
        this[SIGNED_OUT] = signedOut
        this[PROFILE_IDS] = profiles.mapTo(LinkedHashSet()) { it.id.toString() }
        for (profile in profiles) {
            this[stringPreferencesKey("profile_name_${profile.id}")] = profile.name
            this[booleanPreferencesKey("profile_addons_${profile.id}")] = profile.usesPrimaryAddons
        }
    }

    companion object {
        private val VERSION_CODE = intPreferencesKey("version_code")
        private val CREATED_AT = longPreferencesKey("created_at_ms")
        private val ATTEMPTS = intPreferencesKey("attempts")
        private val SIGNED_OUT = booleanPreferencesKey("signed_out")
        private val PROFILE_IDS = stringSetPreferencesKey("profile_ids")

        /** Null when a field is missing or out of range (a marker this build did not write). */
        fun of(prefs: Preferences): RestoreMarker? {
            val ids = prefs[PROFILE_IDS]?.map { it.toIntOrNull() ?: return null }?.sorted() ?: return null
            if (1 !in ids || ids.toSet().size != ids.size || ids.any { it !in 1..BackupLimits.MAX_PROFILES }) return null
            val profiles = ids.map { id ->
                BackupProfile(
                    id,
                    prefs[stringPreferencesKey("profile_name_$id")] ?: return null,
                    prefs[booleanPreferencesKey("profile_addons_$id")] ?: return null,
                )
            }
            return RestoreMarker(
                prefs[VERSION_CODE] ?: return null,
                prefs[CREATED_AT] ?: return null,
                prefs[ATTEMPTS]?.takeIf { it >= 0 } ?: return null,
                prefs[SIGNED_OUT] ?: return null,
                profiles,
            )
        }
    }
}

/** What the last restore did, for the screen after the restart (one-shot). */
sealed interface RestoreNotice {
    /** Applied. [skippedProfiles]: ticked backup profiles with no match by then; [photosSkipped]: over My photos' cap. */
    data class Done(val skippedProfiles: List<String>, val photosSkipped: Int) : RestoreNotice

    /** Stopped part-way (an error, or a second cut-off run): some parts may be restored; the safety copy undoes it. */
    data object StoppedPartWay : RestoreNotice

    /** Not applied, nothing changed: too old, dated in the future, or another app version (R6). */
    data object Discarded : RestoreNotice

    /** Not applied, nothing changed: signed in or out since the restore was confirmed (R5). */
    data object AccountChanged : RestoreNotice
}

private val NOTICE_OUTCOME = stringPreferencesKey("outcome")
private val NOTICE_SKIPPED_COUNT = intPreferencesKey("skipped_count")
private fun noticeSkipped(i: Int) = stringPreferencesKey("skipped_$i")
private val NOTICE_PHOTOS_SKIPPED = intPreferencesKey("photos_skipped")

internal fun RestoreNotice.toPreferences(): Preferences = mutablePreferencesOf().apply {
    this[NOTICE_OUTCOME] = when (this@toPreferences) {
        is RestoreNotice.Done -> "done"
        RestoreNotice.StoppedPartWay -> "stopped"
        RestoreNotice.Discarded -> "discarded"
        RestoreNotice.AccountChanged -> "account"
    }
    if (this@toPreferences is RestoreNotice.Done) {
        // Indexed, not a set: two skipped profiles may share a name (R1 lists both).
        this[NOTICE_SKIPPED_COUNT] = skippedProfiles.size
        skippedProfiles.forEachIndexed { i, name -> this[noticeSkipped(i)] = name }
        this[NOTICE_PHOTOS_SKIPPED] = photosSkipped
    }
}

/**
 * The last restore's outcome, once (the notice file is deleted): null when there is none or it does not
 * read. Blocking; one file check when there is none.
 */
internal suspend fun takeRestoreNotice(filesDir: File): RestoreNotice? {
    val file = RestoreDirs(filesDir).notice
    if (!file.exists()) return null
    return try {
        PrefsFiles.read(file)?.let(::restoreNoticeOf)
    } catch (_: IOException) {
        null
    } finally {
        // Shown at most once, whatever reading it did.
        file.delete()
    }
}

/** Null for a notice this build did not write. */
internal fun restoreNoticeOf(prefs: Preferences): RestoreNotice? = when (prefs[NOTICE_OUTCOME]) {
    "done" -> RestoreNotice.Done(
        (0..<(prefs[NOTICE_SKIPPED_COUNT] ?: 0).coerceIn(0, BackupLimits.MAX_PROFILES)).mapNotNull { prefs[noticeSkipped(it)] },
        prefs[NOTICE_PHOTOS_SKIPPED] ?: 0,
    )
    "stopped" -> RestoreNotice.StoppedPartWay
    "discarded" -> RestoreNotice.Discarded
    "account" -> RestoreNotice.AccountChanged
    else -> null
}

/**
 * R5: signed out means no supabase-kt session in the default SharedPreferences (any `sb-*-session` key:
 * docs/stack-notes.md, supabase-kt 3.2.1) and `had_nuvio_auth` not set in AuthSessionNoticeDataStore.
 * Read from the files, so staging (app running) and the applier (before onCreate) read the same thing.
 * A notice store that cannot be read counts as signed in: the narrower restore.
 */
internal object BackupAccountCheck {
    private val SESSION_KEY = Regex("sb-.*-session")
    private val HAD_NUVIO_AUTH = booleanPreferencesKey("had_nuvio_auth")

    /** The default SharedPreferences' file name ([Context.getPackageName] + "_preferences"). */
    fun accountPrefsName(context: Context): String = context.packageName + "_preferences"

    suspend fun isSignedOut(filesDir: File, accountPrefs: Map<String, *>): Boolean {
        if (accountPrefs.keys.any { SESSION_KEY.matches(it) }) return false
        val notice = try {
            PrefsFiles.read(File(File(filesDir, "datastore"), "auth_session_notice_store.preferences_pb"))
        } catch (_: IOException) {
            return false
        }
        return notice?.get(HAD_NUVIO_AUTH) != true
    }
}
