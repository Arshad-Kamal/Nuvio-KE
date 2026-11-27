package com.nuvio.tv.core.backup

import com.nuvio.tv.data.local.MyPhotoAddResult
import com.nuvio.tv.data.local.MyPhotoKind
import com.nuvio.tv.data.local.MyPhotosStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.time.Clock

/**
 * Spec 101 R3 catalog guard: every store or file name the app's sources create must be assigned in
 * [BackupCatalog.stores] or listed in [BackupCatalog.excluded]. A failure here means a new store:
 * give it a group (or an exclusion with its reason) in BackupCatalog.kt.
 */
class BackupCatalogGuardTest {

    /** Matches of the scan that are not stores, with why. */
    private val notStores = mapOf(
        "Nuvio TV" to "DeviceSessionRegistration.CLIENT_NAME: a device label",
        "dovi_bridge" to "DoviBridge.LIB_NAME: a native library",
        "audio_name" to "TrackPreferenceDataStore key name",
        "sub_name" to "TrackPreferenceDataStore key name",
        "sub_addon_name" to "TrackPreferenceDataStore key name",
        "Broadcasters" to "LiveSourceManager.PRESET_NAME: a source label",
        "datastore" to "the DataStore folder; its files are the stores named above",
        "content_filter_packs.json" to "BuiltInPacks.ASSET_NAME: an APK asset (Spec 117), read-only",
    )

    /** `files/` folders whose own files are each catalogued or excluded by path, with why. */
    private val ownedDirs = mapOf(
        "livetv" to "Live TV files, each listed (allow-list, R3)",
        "network_privacy" to "private_dns store and access_headers.bin, each listed",
        "addon_tls" to "the certificate-pin store, listed",
        "report" to "crash reports and the playback error log (diagnostics)",
    )

    /** Storage APIs this scan does not read names from: using one needs the scan extended first. */
    private val unscannedApis = Regex("""noBackupFilesDir|\.getDir\(|getExternalFilesDir|openFileOutput|\bdataStore\(\s*fileName|deviceProtectedStorage""")

    /**
     * Computed store names allowed in a storage call: file to the one identifier allowed (null: any, the
     * factory's per-profile names built from the FEATURE constants harvested here).
     */
    private val computedNamesAllowed = mapOf(
        "ProfileDataStoreFactory.kt" to null,
        // Reads catalog stores by their BackupCatalog id: the catalog is what this test guards.
        "BackupExporter.kt" to "name",
        // Replaces a catalog SharedPreferences store by its id, and reads the account check's file (R5).
        "RestoreFiles.kt" to "name",
    )

    /** Unscanned APIs allowed in one file each, with why. */
    private val unscannedAllowed = mapOf(
        // The manual-backups folder below API 29 (R9): backup output, not a store.
        "ManualBackups.kt" to "getExternalFilesDir",
        // Spec 135: a copy of a bundled raw font (rewritten from the APK when it changes), not user data.
        "SubtitleTypefaces.kt" to "noBackupFilesDir",
    )

    private enum class Origin { FEATURE, DATASTORE, SHARED_PREFS, OTHER }

    private class Found(val name: String, val origin: Origin, val file: String)

    private val sources: List<File> by lazy {
        // Unit tests run with the module directory as working directory. Every non-test source set.
        val src = File("src")
        assertTrue("sources not found at ${src.absolutePath}", src.isDirectory)
        src.listFiles().orEmpty()
            .filter { it.isDirectory && it.name != "test" && it.name != "androidTest" }
            .flatMap { set -> set.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
    }

    private val fileNameChars = Regex("""[A-Za-z0-9._ -]+""")

    /** [storageCall]: the identifier names a store in a storage API call, where a computed name is refused. */
    private fun resolveConstant(ident: String, text: String, file: File, storageCall: Boolean = false): String? {
        val name = ident.substringAfterLast('.')
        // *_NAME and *FEATURE constants are harvested on their own.
        if (name.endsWith("_NAME") || name.endsWith("FEATURE")) return null
        // A lowercase identifier is a computed name: allowed only where the names come from a list already guarded.
        if (!name.first().isUpperCase()) {
            if (!storageCall) return null
            if (file.name in computedNamesAllowed && computedNamesAllowed[file.name].let { it == null || it == ident }) return null
            error("${file.name}: store name $ident is computed; give it a literal or a *_NAME constant the guard can read")
        }
        return Regex("""\b${Regex.escape(name)}\s*=\s*"([^"]+)"""").find(text)?.groupValues?.get(1)
            ?: error("${file.name}: cannot resolve the store name $ident; give it a literal or a *_NAME constant")
    }

    private fun templateToGlob(path: String) = path.replace(Regex("""\$\{[^}]*\}|\$\w+"""), "*")

    /** Every store or file name the sources create, with how it was found and where. */
    private fun harvest(): List<Found> {
        val found = mutableListOf<Found>()
        val arg = """\s*(?:name\s*=\s*)?(?:"([^"]+)"|([\w.]+))"""
        val calls = Regex("""(preferencesDataStore|preferencesDataStoreFile|getSharedPreferences)\($arg""")
        val constants = Regex("""\b\w*?(_NAME|FEATURE)\s*=\s*"([^"]+)"""")
        val factoryLiteral = Regex("""factory\.get\(\s*\w+\s*,\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
        val filesDirChild = Regex("""filesDir\s*,\s*(?:"([^"]+)"|([\w.]+))""")
        val filesDirPath = Regex("""filesDir\.path\}/([\w.-]+)""")
        val liveTvChild = Regex("""File\(\s*dir\s*,\s*"([^"]+)"""")
        for (file in sources) {
            val text = file.readText()
            val before = found.size
            fun add(name: String?, origin: Origin = Origin.OTHER) { if (name != null) found += Found(name, origin, file.name) }
            calls.findAll(text).forEach { m ->
                val origin = if (m.groupValues[1] == "getSharedPreferences") Origin.SHARED_PREFS else Origin.DATASTORE
                add(m.groupValues[2].ifEmpty { null } ?: resolveConstant(m.groupValues[3], text, file, storageCall = true), origin)
            }
            // A constant holding more than file-name characters (a regex, a template) names no store.
            constants.findAll(text).filter { fileNameChars.matches(it.groupValues[2]) }.forEach { m ->
                add(m.groupValues[2], if (m.groupValues[1] == "FEATURE") Origin.FEATURE else Origin.OTHER)
            }
            factoryLiteral.findAll(text).forEach { add(it.groupValues[1], Origin.FEATURE) }
            filesDirChild.findAll(text).forEach { m ->
                val literal = m.groupValues[1]
                if (literal.isEmpty()) {
                    add(resolveConstant(m.groupValues[2], text, file))
                } else {
                    // A templated path counts by its folder; the templated part is a harvested constant.
                    add(if ('$' in literal) literal.substringBefore('$').trimEnd('/').substringBeforeLast('/') else literal)
                }
            }
            filesDirPath.findAll(text).forEach { add(it.groupValues[1]) }
            if (Regex("""filesDir\s*,\s*"livetv"""").containsMatchIn(text)) {
                liveTvChild.findAll(text).forEach { add("livetv/" + templateToGlob(it.groupValues[1])) }
            }
            // A DataStore built here must name its file here (the factory builds from FEATURE constants).
            if ("PreferenceDataStoreFactory.create" in text && file.name != "ProfileDataStoreFactory.kt") {
                assertTrue("${file.name} builds a DataStore whose name the scan cannot see", found.size > before)
            }
            assertTrue(
                "${file.name} uses a storage API the guard does not scan",
                !unscannedApis.containsMatchIn(unscannedAllowed[file.name]?.let { text.replace(it, "") } ?: text),
            )
        }
        return found
    }

    private val known: List<String> by lazy { BackupCatalog.stores.map { it.id } + BackupCatalog.excluded.keys }

    /** Covered: an id equal to the name, an id ending in `/name`, or an owned folder. */
    private fun covered(name: String): Boolean =
        name in notStores || name in ownedDirs || known.any { id -> id == name || id.endsWith("/$name") }

    @Test
    fun `every store the sources create is assigned or excluded`() {
        val found = harvest()
        val names = found.mapTo(HashSet()) { it.name }
        // The scan itself must see the stores it is guarding, or it guards nothing.
        for (expected in listOf("theme_settings", "app_locale", "tmdb_api_key", "nuvio_sync_client_identity", "addon_manifest_cache",
            "private_dns", "my_photos", "livetv/sources.json", "livetv/epg-*.json", "network_privacy/access_headers.bin")) {
            assertTrue("scan missed $expected", expected in names)
        }
        val missing = found.filter { !covered(it.name) }
        assertTrue(
            "Stores missing from BackupCatalog (assign a group or exclude with a reason): " +
                missing.joinToString { "${it.name} (${it.file})" },
            missing.isEmpty(),
        )
    }

    @Test
    fun `catalogued kinds match how the sources open each store`() {
        for (f in harvest()) {
            val store = BackupCatalog.store(f.name) ?: continue
            val expected = when (f.origin) {
                Origin.FEATURE -> StoreKind.PROFILE_DATASTORE
                Origin.DATASTORE -> StoreKind.DATASTORE
                Origin.SHARED_PREFS -> StoreKind.SHARED_PREFS
                Origin.OTHER -> continue
            }
            assertEquals("${f.name} (${f.file})", expected, store.kind)
        }
    }

    @Test
    fun `excluded and addon-owned keys are real keys`() {
        val all = sources.joinToString("\n") { it.readText() }
        for (key in BackupCatalog.excludedKeys) assertTrue("no source names the excluded key $key", "\"$key\"" in all)
        val layout = sources.single { it.name == "LayoutPreferenceDataStore.kt" }.readText()
        for (key in BackupCatalog.addonOwnedLayoutKeys) assertTrue("LayoutPreferenceDataStore has no key $key", "\"$key\"" in layout)
        // Names the restore reads from files before the stores open (RestoreApplier, BackupAccountCheck).
        for ((file, names) in mapOf(
            "AuthSessionNoticeDataStore.kt" to listOf("auth_session_notice_store", "had_nuvio_auth"),
            "ProfileDataStore.kt" to listOf("profile_settings", "profiles_json"),
            "WatchedSeriesStateHolder.kt" to listOf("watched_series_cache"),
        )) {
            val text = sources.single { it.name == file }.readText()
            for (name in names) assertTrue("$file no longer names $name", "\"$name\"" in text)
        }
    }

    @Test
    fun `catalog ids are unique and never also excluded`() {
        val ids = BackupCatalog.stores.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.none { it in BackupCatalog.excluded })
    }

    @Test
    fun `groups and kinds agree`() {
        for (store in BackupCatalog.stores) {
            when (store.group) {
                BackupGroup.PROFILE_SECRET -> assertTrue(store.id, store.perProfile && !store.tvOnly)
                BackupGroup.DEVICE, BackupGroup.TV_SECRET -> assertTrue(store.id, !store.perProfile && !store.tvOnly)
                BackupGroup.PROFILE -> assertTrue(store.id, store.perProfile || store.kind == StoreKind.PHOTOS)
            }
            assertTrue(store.id, (store.kind == StoreKind.TEXT_FILE) == store.id.endsWith(".json"))
        }
    }

    @Test
    fun `the photo name check accepts what MyPhotosStore writes`() {
        val dir = Files.createTempDirectory("photos").toFile()
        try {
            val store = MyPhotosStore(dir, Clock.System, Dispatchers.Unconfined)
            for (kind in MyPhotoKind.entries) {
                val added = runBlocking { store.add(kind, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())) }
                val name = (added as MyPhotoAddResult.Added).photo.file.name
                assertTrue(name, BackupCatalog.isPhotoName(name))
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
