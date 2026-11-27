package com.nuvio.tv.core.backup

import androidx.datastore.core.CorruptionException
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import okio.buffer
import okio.source
import java.io.File
import kotlin.io.encoding.Base64

/**
 * Reads the catalog's stores from disk for an export (Spec 101 R3, R9). Files are read as they lie: a
 * DataStore replaces its file by rename, so a read sees one whole write, and no second DataStore is ever
 * opened on a live file (R6). Stores of profiles not in the list (a deleted profile's leftovers) are never
 * read. Each entry is charged to [limits] as it is read, so a TV with too much data stops at the memory
 * budget instead of loading all of it; the writer then checks the exact limits (BackupCodec.write).
 *
 * A per-profile DataStore file that does not parse is read from its `.bak` shadow copy, as
 * ProfileDataStoreFactory's corruption handler would recover it. A damaged store with no readable shadow
 * copy is left out of the backup, so a restore leaves the target as it is (R4) and the store's own
 * corruption handler decides its reset (DevicePlayerSettingsStore resets to a seeded flag, not empty).
 * So one never-opened damaged file cannot make every export, and with it every restore's pre-restore
 * copy, fail (Spec 101 S3).
 *
 * [filesDir] is the app's `files/`; [sharedPrefs] returns a SharedPreferences file's values by name.
 */
internal class BackupSnapshot(
    private val filesDir: File,
    private val sharedPrefs: (String) -> Map<String, *>,
) {
    /**
     * One entry per catalog store in [groups]: a prefs store for every profile in [profileIds] (per-profile
     * stores) or once (TV-wide), empty when its file does not exist yet, so a restore sets it as this TV
     * had it; a text file only when it exists; one entry per My photos file unless [withPhotos] is false. Throws [BackupCheckException]
     * (a store too large or unreadable as a backup value) or [BackupTooLargeException] (the memory
     * budget); a file that cannot be read at all throws IOException.
     */
    suspend fun entries(profileIds: List<Int>, groups: Set<BackupGroup>, limits: SectionLimits, withPhotos: Boolean = true): List<BackupEntry> {
        val entries = ArrayList<BackupEntry>()
        fun add(entry: BackupEntry) {
            // As the reader charges it: one line per entry (a copy secret's line costs the same).
            limits.charge(SectionCost.of(BackupLine.Entry(entry)))
            entries += entry
        }
        for (store in BackupCatalog.stores) {
            if (store.group !in groups) continue
            when (store.kind) {
                StoreKind.PROFILE_DATASTORE -> for (id in profileIds) {
                    readDataStore(store, id)?.let { add(BackupEntry.Prefs(store.id, id, it)) }
                }
                StoreKind.DATASTORE -> readDataStore(store, null)?.let { add(BackupEntry.Prefs(store.id, values = it)) }
                StoreKind.SHARED_PREFS -> add(BackupEntry.Prefs(store.id, values = values(sharedPrefs(store.id), store.id)))
                StoreKind.TEXT_FILE -> readText(File(filesDir, store.id), store.id)?.let { add(BackupEntry.TextFile(store.id, it)) }
                StoreKind.PHOTOS -> if (withPhotos) photos(File(filesDir, store.id)).forEach(::add)
            }
        }
        return entries
    }

    /** [storeId]'s values for [profile] (null for a TV-wide store), as [entries] reads them. */
    suspend fun values(storeId: String, profile: Int?): Map<String, BackupValue>? =
        readDataStore(checkNotNull(BackupCatalog.store(storeId)) { storeId }, profile)

    /** The store's values (empty when its file does not exist yet); null when damaged beyond its shadow copy. */
    private suspend fun readDataStore(store: CatalogStore, profile: Int?): Map<String, BackupValue>? {
        val file = File(filesDir, store.dataStorePath(profile))
        val prefs = try {
            readPrefsFile(file, store.id, profile) ?: return emptyMap()
        } catch (_: CorruptionException) {
            val shadow = File(file.path + ".bak")
            if (store.perProfile) {
                try {
                    readPrefsFile(shadow, store.id, profile)
                } catch (_: CorruptionException) {
                    null
                }
            } else {
                null
            }
        }
        return prefs?.let { values(it.asMap().mapKeys { (key, _) -> key.name }, store.id) }
    }

    /** Null when [file] does not exist; throws [CorruptionException] when it does not parse. */
    private suspend fun readPrefsFile(file: File, store: String, profile: Int?): Preferences? {
        if (!file.isFile) return null
        // A guard on memory only: the proto is never larger than twice its JSON, so a file over this
        // could never fit one section. The writer gives the exact verdict.
        if (file.length() > 2L * BackupLimits.MAX_SECTION_BYTES) {
            throw BackupCheckException("section size", tooLarge = true, store = store, profile = profile)
        }
        return file.source().buffer().use { PreferencesSerializer.readFrom(it) }
    }

    /** Typed values minus the keys never exported ([BackupCatalog.excludedKeys]). */
    private fun values(raw: Map<String, *>, store: String): Map<String, BackupValue> {
        val values = LinkedHashMap<String, BackupValue>()
        for ((key, value) in raw) {
            if (key in BackupCatalog.excludedKeys) continue
            values[key] = BackupValue.of(value) ?: throw BackupCheckException("value type", store = store)
        }
        return values
    }

    private fun readText(file: File, store: String): String? {
        if (!file.isFile) return null
        // UTF-8 is at most 3 bytes per char, so a file over this is over the char cap (memory guard only).
        if (file.length() > 3L * BackupLimits.MAX_TEXT_FILE_CHARS) {
            throw BackupCheckException("file size", tooLarge = true, store = store)
        }
        return file.readText()
    }

    /** The gallery's photos (names it lists) in name order, read one at a time as the caller charges each. */
    private fun photos(dir: File): Sequence<BackupEntry.Photo> =
        dir.listFiles().orEmpty().asSequence()
            .filter { it.isFile && BackupCatalog.isPhotoName(it.name) }
            .sortedBy { it.name }
            .map { file ->
                if (file.length() > BackupLimits.MAX_PHOTO_BYTES) throw BackupCheckException("photo size", tooLarge = true, store = BackupCatalog.MY_PHOTOS)
                BackupEntry.Photo(file.name, Base64.encode(file.readBytes()))
            }
}
