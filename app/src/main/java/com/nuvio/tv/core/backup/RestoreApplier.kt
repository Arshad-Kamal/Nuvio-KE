package com.nuvio.tv.core.backup

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
import com.nuvio.tv.data.local.MyPhotosStore
import com.nuvio.tv.data.local.ProfileJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.io.IOException
import kotlin.time.Clock

/**
 * Spec 101 R6: writes a staged restore as files before `super.onCreate()` in NuvioApplication, where Hilt
 * injects and the first store opens, so no DataStore has a file open and no store class needs an import
 * method. Matching (R1) runs again here from the profile list then on disk. A run is counted in the marker
 * before it starts; a run cut off by a kill repeats (it writes the same files from the same staging), and
 * after [MAX_ATTEMPTS] runs, or on any exception, the staging is dropped with a notice pointing to the
 * pre-restore copy. Never throws an Exception: a restore must not stop the app from starting.
 */
internal class RestoreApplier(
    private val filesDir: File,
    private val sharedPrefs: SharedPrefsFiles,
    private val accountPrefsName: String,
    private val versionCode: Int,
    private val clock: Clock,
) {
    private val dirs = RestoreDirs(filesDir)

    /** Blocking. Without a staged restore this is one file check. */
    fun applyIfStaged() {
        if (!dirs.marker.exists()) {
            // A staging cut off before its marker: never complete, never applied.
            if (dirs.staged.exists()) dirs.discardStaging()
            return
        }
        try {
            runBlocking { run() }
        } catch (e: Exception) {
            // Something outside apply() failed (it catches its own): drop the staging rather than retry it.
            Log.w(TAG, "restore not run: ${e.javaClass.simpleName}")
            dirs.discardStaging()
        }
    }

    private suspend fun run() {
        val marker = try {
            PrefsFiles.read(dirs.marker)?.let(RestoreMarker::of)
        } catch (_: IOException) {
            null
        } ?: return finish(RestoreNotice.StoppedPartWay)
        val now = clock.now().toEpochMilliseconds()
        // After a cut-off run, any refusal leaves a part-way restore behind.
        val refused = if (marker.attempts > 0) RestoreNotice.StoppedPartWay else null
        when {
            marker.attempts >= MAX_ATTEMPTS -> return finish(RestoreNotice.StoppedPartWay)
            marker.versionCode != versionCode || marker.createdAtMs > now || now - marker.createdAtMs > MAX_AGE_MS ->
                return finish(refused ?: RestoreNotice.Discarded)
            BackupAccountCheck.isSignedOut(filesDir, sharedPrefs.read(accountPrefsName)) != marker.signedOut ->
                return finish(refused ?: RestoreNotice.AccountChanged)
        }
        try {
            PrefsFiles.write(dirs.marker, marker.copy(attempts = marker.attempts + 1).toPreferences())
        } catch (_: IOException) {
            // Uncounted, a run could repeat forever: do not start one.
            return finish(refused ?: RestoreNotice.Discarded)
        }
        val notice = try {
            apply(marker)
        } catch (e: Exception) {
            Log.w(TAG, "restore stopped: ${e.javaClass.simpleName}")
            RestoreNotice.StoppedPartWay
        }
        finish(notice)
    }

    /**
     * The notice first, then the staging (marker first): a kill in between reruns an idempotent apply.
     * Then the temp files a killed run may have left beside its targets (all within two folder levels).
     */
    private suspend fun finish(notice: RestoreNotice) {
        try {
            PrefsFiles.write(dirs.notice, notice.toPreferences())
        } catch (e: IOException) {
            Log.w(TAG, "restore notice not saved: ${e.javaClass.simpleName}")
        }
        dirs.discardStaging()
        filesDir.walkTopDown().maxDepth(2).filter { it.isFile && it.name.endsWith(RESTORE_TMP) }.forEach { it.delete() }
    }

    private suspend fun apply(marker: RestoreMarker): RestoreNotice {
        val matching = ProfileMatching.of(marker.profiles, tvProfiles())
        for (match in matching.matches) {
            if (!dirs.stagedProfile(match.backupId).isDirectory) continue
            for (store in BackupCatalog.stores) {
                if (!store.perProfile || !store.restores(marker.signedOut)) continue
                if (match.addonsDiffer && store.id == BackupCatalog.ADDON_STORE) continue
                val staged = dirs.stagedProfileStore(match.backupId, store)
                if (!staged.isFile) continue
                val keep = if (match.addonsDiffer && store.id == BackupCatalog.LAYOUT_STORE) {
                    BackupCatalog.excludedKeys + BackupCatalog.addonOwnedLayoutKeys
                } else {
                    BackupCatalog.excludedKeys
                }
                replaceDataStore(staged, File(filesDir, store.dataStorePath(match.tvId)), keep)
            }
            deleteDerivedCaches(match.tvId)
        }
        for (store in BackupCatalog.stores) {
            if (store.perProfile || store.kind == StoreKind.PHOTOS) continue
            val staged = dirs.stagedTvStore(store)
            if (!staged.isFile) continue
            when (store.kind) {
                StoreKind.DATASTORE -> replaceDataStore(staged, File(filesDir, store.dataStorePath()), BackupCatalog.excludedKeys)
                StoreKind.SHARED_PREFS -> replaceSharedPrefs(staged, store.id)
                StoreKind.TEXT_FILE -> copyAtomically(staged, File(filesDir, store.id))
                StoreKind.PROFILE_DATASTORE, StoreKind.PHOTOS -> Unit
            }
        }
        val skipped = matching.skipped.filter { dirs.stagedProfile(it.backupId).isDirectory }.map { it.name }
        return RestoreNotice.Done(skipped, addPhotos())
    }

    /** R4: the backup's copy, keeping the target's [keep] keys; then the shadow copies that could resurrect the old one. */
    private suspend fun replaceDataStore(staged: File, target: File, keep: Set<String>) {
        val incoming = PrefsFiles.read(staged) ?: throw IOException("staged file missing")
        // A damaged target keeps the keys of its shadow copy, as the app would have recovered it; with
        // neither readable it is replaced without them ([8:m]).
        val current = readOrNull(target) ?: readOrNull(File(target.path + ".bak"))
        PrefsFiles.write(target, withKeptKeys(incoming, current, keep))
        // ProfileDataStoreFactory's shadow copy and its temp (R6, [7:m]).
        File(target.path + ".bak").delete()
        File(target.path + ".bak.tmp").delete()
    }

    private suspend fun readOrNull(file: File): Preferences? =
        try {
            PrefsFiles.read(file)
        } catch (_: IOException) {
            null
        }

    /** SharedPreferences through their own API: nothing has the file loaded yet, and commit() syncs it. */
    private suspend fun replaceSharedPrefs(staged: File, name: String) {
        val incoming = PrefsFiles.read(staged) ?: throw IOException("staged file missing")
        val current = sharedPrefs.read(name)
        val values = LinkedHashMap<String, Any>()
        for ((key, value) in incoming.asMap()) if (key.name !in BackupCatalog.excludedKeys) values[key.name] = value
        for ((key, value) in current) if (key in BackupCatalog.excludedKeys && value != null) values[key] = value
        sharedPrefs.replace(name, values)
    }

    /** The derived caches of a restored profile (R4 [7:m]); the app rebuilds them. */
    private fun deleteDerivedCaches(tvId: Int) {
        ContinueWatchingEnrichmentCache.nextUpCacheFile(filesDir, tvId).delete()
        ContinueWatchingEnrichmentCache.inProgressCacheFile(filesDir, tvId).delete()
        val watched = File(filesDir, profileDataStorePath(WATCHED_SERIES, tvId))
        watched.delete()
        File(watched.path + ".bak").delete()
    }

    /** R4 [7:m]: added by name, never deleted, none past My photos' cap; returns how many did not fit. */
    private fun addPhotos(): Int {
        val staged = dirs.stagedPhotos.listFiles().orEmpty().filter { it.isFile && BackupCatalog.isPhotoName(it.name) }
        if (staged.isEmpty()) return 0
        // A literal, not BackupCatalog.MY_PHOTOS: the catalog guard cannot resolve another file's constant.
        val gallery = File(filesDir, "my_photos")
        val present = gallery.listFiles().orEmpty().filter { it.isFile && BackupCatalog.isPhotoName(it.name) }.mapTo(HashSet()) { it.name }
        var skipped = 0
        for (photo in staged.sortedBy { it.name }) {
            if (photo.name in present) continue
            if (present.size >= MyPhotosStore.CAP) {
                skipped++
                continue
            }
            copyAtomically(photo, File(gallery, photo.name))
            present += photo.name
        }
        return skipped
    }

    private fun copyAtomically(source: File, target: File) {
        writeAtomically(target) { out -> source.inputStream().use { it.copyTo(out) } }
    }

    /**
     * The TV's profiles as ProfileDataStore reads them: its list, with the primary added when missing, or
     * the primary alone when the list is absent or unreadable.
     */
    private suspend fun tvProfiles(): List<BackupProfile> {
        val prefs = readOrNull(File(File(filesDir, "datastore"), "profile_settings.preferences_pb"))
        val json = prefs?.get(PROFILES_JSON)
        val listed = try {
            json?.takeIf { it.isNotBlank() }?.let { backupJson.decodeFromString(ListSerializer(ProfileJson.serializer()), it) }.orEmpty()
        } catch (_: SerializationException) {
            emptyList()
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
        val profiles = listed.filter { it.id in 1..BackupLimits.MAX_PROFILES }.distinctBy { it.id }
            .map { BackupProfile(it.id, it.name, it.usesPrimaryAddons) }
        return if (profiles.any { it.id == 1 }) profiles else listOf(BackupProfile(1, "", usesPrimaryAddons = false)) + profiles
    }

    companion object {
        private const val TAG = "RestoreApplier"
        const val MAX_ATTEMPTS = 2
        /** R6: a staging older than this is discarded (it was confirmed, then the TV sat). */
        const val MAX_AGE_MS = 10L * 60 * 1000
        private const val WATCHED_SERIES = "watched_series_cache"
        private val PROFILES_JSON = stringPreferencesKey("profiles_json")

        /** The one call in NuvioApplication.onCreate, before super.onCreate(). */
        fun applyIfStaged(context: Context) {
            RestoreApplier(
                context.filesDir,
                // Named: the catalog guard reads a capitalised name after `filesDir,` as a store constant.
                sharedPrefs = ContextSharedPrefsFiles(context),
                BackupAccountCheck.accountPrefsName(context),
                BuildConfig.VERSION_CODE,
                Clock.System,
            ).applyIfStaged()
        }
    }
}
