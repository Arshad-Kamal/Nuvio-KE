package com.nuvio.tv.data.local

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.nuvio.tv.core.tmdb.TMDB_API_KEY_STORE_NAME
import com.nuvio.tv.domain.model.DiscoverLocation
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import java.io.File
import java.io.IOException

private class ScopedDataStore(
    val store: DataStore<Preferences>,
    val scope: CoroutineScope,
    val job: Job
)

internal val discoverLocationMigration = object : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        legacySearchDiscoverEnabledKey in currentData

    override suspend fun migrate(currentData: Preferences): Preferences {
        val mutable = currentData.toMutablePreferences()
        val legacy = mutable[legacySearchDiscoverEnabledKey]
        if (legacy != null && mutable[discoverLocationKey] == null) {
            val rememberedLocation = mutable[lastNonOffDiscoverLocationKey]?.let {
                runCatching { DiscoverLocation.valueOf(it) }.getOrNull()
            }?.takeIf { it != DiscoverLocation.OFF }
            val resolved = if (legacy && rememberedLocation != null) {
                rememberedLocation
            } else {
                DiscoverLocation.fromLegacySearchDiscoverEnabled(legacy)
            }
            mutable[discoverLocationKey] = resolved.name
        }
        mutable.remove(legacySearchDiscoverEnabledKey)
        return mutable.toPreferences()
    }

    override suspend fun cleanUp() = Unit
}

@Singleton
class ProfileDataStoreFactory @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val cache = ConcurrentHashMap<String, ScopedDataStore>()
    private val lock = Any()
    private val retainedStandaloneDataStoreNames = setOf(
        "app_onboarding",
        "auth_session_notice_store",
        // Legacy store of the removed debug member-tier preview (Spec 31 S4): kept so
        // sign-out never classifies the leftover file as profile data.
        "debug_settings",
        "device_local_player_prefs",
        // Device-wide player settings (Spec 33 D1): survive sign-out by decision Q1.
        DEVICE_PLAYER_SETTINGS_STORE_NAME,
        "profile_lock_state",
        "profile_settings",
        // Device-scoped by their own store docs, not factory-cached — without these
        // they are classified as profile data and wiped on sign-out.
        "seek_thumbnails",
        // The user's TMDB key (Spec 52): device-wide, kept on sign-out.
        TMDB_API_KEY_STORE_NAME,
        "torrent_settings",
        // The update check's store (Spec 52 P7, the removed legacy updater's file reused under
        // new keys): device-wide, kept on sign-out. Listed by name: UPDATE_STORE_NAME is internal
        // to core/update.
        "update_settings",
        "ui_scale_prefs",
        "episode_options_prefs"
    )

    /** Set of DataStore file names that were reset due to corruption during this session. */
    val corruptedFileNames: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun get(profileId: Int, featureName: String): DataStore<Preferences> {
        val fileName = if (profileId == 1) featureName else "${featureName}_p$profileId"
        synchronized(lock) {
            cache[fileName]?.let { return it.store }
            return createAndCache(fileName).store
        }
    }

    suspend fun clearProfile(profileId: Int) {
        if (profileId == 1) return
        val suffix = "_p$profileId"
        val keysToRemove = synchronized(lock) {
            cache.keys.filter { it.endsWith(suffix) }
        }
        for (key in keysToRemove) {
            val scoped = cache[key] ?: continue
            clearBestEffort(key, scoped.store)
            // Cancel the scope so DataStore releases the file from its active-files
            // registry. The entry stays cached until join() returns, so a concurrent
            // get() meanwhile receives this closing instance and never creates a second
            // DataStore on the same file (DataStore forbids two active per file).
            withContext(NonCancellable) {
                scoped.job.cancel()
                scoped.job.join()
            }
            synchronized(lock) { cache.remove(key, scoped) }
        }
    }

    suspend fun clearProfileScopedData() = withContext(Dispatchers.IO) {
        val cachedStores = synchronized(lock) { cache.toMap() }
        cachedStores.forEach { (fileName, scoped) -> clearBestEffort(fileName, scoped.store) }
        corruptedFileNames.clear()

        val cachedFileNames = cachedStores.keys.mapTo(mutableSetOf()) { "$it.preferences_pb" }
        val dataStoreDir = File(context.filesDir, "datastore")
        if (!dataStoreDir.exists()) return@withContext
        dataStoreDir.listFiles()?.forEach { file ->
            val name = file.name
            if (name.endsWith(".preferences_pb.bak") || name.endsWith(".preferences_pb.bak.tmp")) {
                // Shadow copies (and a temp left by a kill mid-write) follow writeShadowCopy's
                // "$fileName.preferences_pb.bak[.tmp]" scheme; sweep them too or
                // recoverFromShadowCopy can resurrect wiped signed-out data on a later
                // corruption event.
                val baseName = name.removeSuffix(".tmp").removeSuffix(".preferences_pb.bak").removeSuffix(".preferences_pb")
                if (baseName !in retainedStandaloneDataStoreNames) {
                    file.delete()
                }
                return@forEach
            }
            if (name !in cachedFileNames && isProfileScopedDataStoreFile(name)) {
                file.delete()
            }
        }
    }

    /** Best-effort wipe: a real failure is logged and skipped, cancellation propagates (KT-CONC-03). */
    private suspend fun clearBestEffort(fileName: String, store: DataStore<Preferences>) {
        try {
            store.edit { it.clear() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("ProfileDataStoreFactory", "Failed to clear $fileName: ${e.message}")
        }
    }

    private fun isProfileScopedDataStoreFile(fileName: String): Boolean {
        if (!fileName.endsWith(".preferences_pb")) return false
        val dataStoreName = fileName.removeSuffix(".preferences_pb")
        return dataStoreName !in retainedStandaloneDataStoreNames
    }

    private fun createAndCache(fileName: String): ScopedDataStore {
        val job = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.IO + job)
        val migrations = if (fileName == "layout_settings" || fileName.startsWith("layout_settings_p")) {
            listOf(discoverLocationMigration)
        } else {
            emptyList()
        }
        // Ensure the datastore directory exists — prevents ENOENT on first read
        // when a profile-scoped file hasn't been created yet.
        val dataStoreDir = File(context.filesDir, "datastore")
        if (!dataStoreDir.exists()) {
            dataStoreDir.mkdirs()
        }
        // DataStore 1.1.x (okio-based) can throw FileNotFoundException if the file
        // doesn't exist yet and a race condition occurs. Pre-create an empty file
        // to avoid this edge case (DataStore will overwrite on first write).
        val targetFile = File(dataStoreDir, "$fileName.preferences_pb")
        if (!targetFile.exists()) {
            try { targetFile.createNewFile() } catch (_: Exception) { }
        }
        val store = PreferenceDataStoreFactory.create(
            corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { ex ->
                Log.e("ProfileDataStoreFactory", "DataStore corrupted ($fileName): ${ex.message} — attempting shadow copy recovery")
                val recovered = recoverFromShadowCopy(fileName)
                if (recovered != null) {
                    Log.i("ProfileDataStoreFactory", "DataStore recovered from shadow copy ($fileName)")
                    recovered
                } else {
                    Log.e("ProfileDataStoreFactory", "DataStore shadow copy unavailable ($fileName) — resetting to empty preferences")
                    corruptedFileNames.add(fileName)
                    emptyPreferences()
                }
            },
            scope = scope,
            migrations = migrations,
            produceFile = { context.preferencesDataStoreFile(fileName) }
        )

        // Wrap store to persist a shadow copy after the first non-empty read (app start)
        // and after each edit, ensuring a consistent backup exists before any
        // corruption can occur.
        val wrappedStore = ShadowCopyDataStore(store, fileName, scope, this)

        val scoped = ScopedDataStore(wrappedStore, scope, job)
        cache[fileName] = scoped
        return scoped
    }

    internal fun writeShadowCopy(fileName: String) {
        val sourceFile = File(File(context.filesDir, "datastore"), "$fileName.preferences_pb")
        val backupFile = File(File(context.filesDir, "datastore"), "$fileName.preferences_pb.bak")
        try {
            writeShadowCopyAtomically(sourceFile, backupFile)
        } catch (e: Exception) {
            Log.w("ProfileDataStoreFactory", "Failed to write shadow copy for $fileName: ${e.message}")
        }
    }

    private fun recoverFromShadowCopy(fileName: String): Preferences? {
        val backupFile = File(File(context.filesDir, "datastore"), "$fileName.preferences_pb.bak")
        if (!backupFile.exists() || backupFile.length() == 0L) return null
        return try {
            val source = backupFile.inputStream().use { input ->
                okio.Buffer().apply { readFrom(input) }
            }
            val preferences = kotlinx.coroutines.runBlocking {
                androidx.datastore.preferences.core.PreferencesSerializer.readFrom(source)
            }
            Log.i("ProfileDataStoreFactory", "Parsed shadow copy for $fileName (${preferences.asMap().size} keys)")
            preferences
        } catch (e: Exception) {
            Log.w("ProfileDataStoreFactory", "Shadow copy recovery failed for $fileName: ${e.message}")
            null
        }
    }
}


/**
 * Copies [source] to [backup] via "${backup.name}.tmp" + rename, so a concurrent reader or a
 * process kill never sees a torn backup: the backup is either the previous complete copy or
 * the new one. An empty or missing source (a cleared store) deletes the backup, so a later
 * corruption recovery cannot resurrect cleared data. A failed rename keeps the old backup.
 */
internal fun writeShadowCopyAtomically(source: File, backup: File) {
    if (!source.exists() || source.length() == 0L) {
        backup.delete()
        return
    }
    val tmp = File(backup.parentFile, "${backup.name}.tmp")
    source.copyTo(tmp, overwrite = true)
    if (!tmp.renameTo(backup)) {
        tmp.delete()
        throw IOException("rename ${tmp.name} -> ${backup.name} failed")
    }
}

/**
 * Runs [block] one at a time. A request arriving while another run is already waiting
 * to start is dropped: that waiting run starts later, so it observes at least the
 * state the dropped request wanted captured. Blocking; call from an IO thread.
 */
internal class CoalescingSerialRunner(private val block: () -> Unit) {
    private val queued = AtomicBoolean(false)
    private val lock = Any()

    fun run() {
        if (!queued.compareAndSet(false, true)) return
        synchronized(lock) {
            queued.set(false)
            block()
        }
    }
}

/**
 * Thin wrapper around a DataStore that writes a shadow copy of the underlying
 * preferences file after the first successful read and after each edit.
 * This ensures a known-good backup exists for corruption recovery.
 */
private class ShadowCopyDataStore(
    private val delegate: DataStore<Preferences>,
    private val fileName: String,
    private val scope: CoroutineScope,
    private val factory: ProfileDataStoreFactory
) : DataStore<Preferences> {

    // Copies launched per edit/read must not interleave on the one .bak; coalescing keeps
    // an edit burst to at most one running plus one waiting copy.
    private val shadowCopy = CoalescingSerialRunner { factory.writeShadowCopy(fileName) }
    private val initialShadowRequested = AtomicBoolean(false)

    override val data: kotlinx.coroutines.flow.Flow<Preferences>
        get() = delegate.data.also {
            if (initialShadowRequested.compareAndSet(false, true)) {
                scope.launch(Dispatchers.IO) {
                    try {
                        val prefs = delegate.data.first()
                        if (prefs != emptyPreferences()) {
                            shadowCopy.run()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) { }
                }
            }
        }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val result = delegate.updateData(transform)
        scope.launch(Dispatchers.IO) { shadowCopy.run() }
        return result
    }
}
