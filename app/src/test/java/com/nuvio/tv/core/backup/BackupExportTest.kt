package com.nuvio.tv.core.backup

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.core.backup.BackupFixtures.INSTALLED
import com.nuvio.tv.core.backup.BackupFixtures.header
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okio.buffer
import okio.sink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Instant

class BackupExportTest {

    private val root: File = Files.createTempDirectory("backup-export").toFile()
    private val filesDir = File(root, "files").apply { mkdirs() }
    private val target = File(root, "out.tmp")
    private val sharedPrefs = mapOf("app_locale" to mapOf("tag" to "en"))
    private val snapshot = BackupSnapshot(filesDir) { sharedPrefs[it].orEmpty() }
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 9)

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun dataStore(path: String, prefs: Preferences) {
        val file = File(filesDir, path).apply { parentFile!!.mkdirs() }
        runBlocking { file.sink().buffer().use { PreferencesSerializer.writeTo(prefs, it) } }
    }

    private fun text(path: String, text: String) = File(filesDir, path).apply { parentFile!!.mkdirs() }.writeText(text)

    /** A TV with two profiles, a leftover profile-3 store, an excluded key and a store of each kind. */
    private fun seedTv() {
        dataStore("datastore/theme_settings.preferences_pb", preferencesOf(stringPreferencesKey("accent") to "amber", booleanPreferencesKey("migration_theme_done") to true))
        dataStore("datastore/theme_settings_p2.preferences_pb", preferencesOf(stringPreferencesKey("accent") to "teal"))
        dataStore("datastore/theme_settings_p3.preferences_pb", preferencesOf(stringPreferencesKey("accent") to "leftover"))
        dataStore("datastore/library_preferences.preferences_pb", preferencesOf(stringPreferencesKey("library_items") to "[]", intPreferencesKey("library_mutation_revision") to 7))
        dataStore("datastore/device_player_settings.preferences_pb", preferencesOf(stringSetPreferencesKey("codecs") to setOf("dts", "truehd")))
        dataStore("network_privacy/private_dns.preferences_pb", preferencesOf(stringPreferencesKey("provider") to "quad9"))
        dataStore("datastore/tracking_accounts.preferences_pb", preferencesOf(stringPreferencesKey("trakt") to "{token}"))
        dataStore("datastore/tmdb_api_key.preferences_pb", preferencesOf(stringPreferencesKey("user_key") to "k"))
        text("livetv/hidden.json", "{\"keys\":[]}")
        text("livetv/sources.json", "[{\"url\":\"http://user:pass@iptv\"}]")
        File(filesDir, "my_photos").mkdirs()
        File(filesDir, "my_photos/${BackupFixtures.PHOTO_NAME}").writeBytes(jpeg)
        File(filesDir, "my_photos/${BackupFixtures.PHOTO_NAME}.tmp").writeBytes(jpeg)
    }

    private fun export(pin: String? = null, phoneBound: Boolean = false) = runBlocking {
        writeBackupFile(target, snapshot, header(sealed = pin != null), pin, phoneBound, iterations = 1_000)
    }

    private fun readBack(): BackupReadResult.Ok =
        target.inputStream().use { BackupCodec.read(it, INSTALLED) } as BackupReadResult.Ok

    private fun BackupReadResult.Ok.prefs(store: String, profile: Int? = null): Map<String, BackupValue>? =
        body.entries.filterIsInstance<BackupEntry.Prefs>().singleOrNull { it.store == store && it.profile == profile }?.values

    @Test
    fun `a damaged store exports from its shadow copy, else is left out`() {
        seedTv()
        val theme = File(filesDir, "datastore/theme_settings.preferences_pb")
        theme.copyTo(File(theme.path + ".bak"))
        theme.writeText("not a proto")
        File(filesDir, "datastore/theme_settings_p2.preferences_pb").writeText("not a proto")
        File(filesDir, "datastore/theme_settings_p2.preferences_pb.bak").writeText("not a proto either")
        File(filesDir, "datastore/device_player_settings.preferences_pb").writeText("not a proto")
        assertTrue(export() is BackupExportResult.Written)
        val ok = readBack()
        assertEquals(BackupValue.Str("amber"), ok.prefs("theme_settings", 1)!!["accent"])
        assertNull(ok.prefs("theme_settings", 2))
        assertNull(ok.prefs("device_player_settings"))
    }

    @Test
    fun `an export reads every store kind and reads back`() {
        seedTv()
        val result = export()
        assertTrue("$result", result is BackupExportResult.Written && result.sizeBytes == target.length())
        val ok = readBack()
        assertEquals(mapOf("accent" to BackupValue.Str("amber"), "migration_theme_done" to BackupValue.Bool(true)), ok.prefs("theme_settings", 1))
        assertEquals(mapOf("accent" to BackupValue.Str("teal")), ok.prefs("theme_settings", 2))
        // Excluded keys stay out; migration flags travel with their values (R2).
        assertEquals(mapOf("library_items" to BackupValue.Str("[]")), ok.prefs("library_preferences", 1))
        assertEquals(mapOf("codecs" to BackupValue.StrSet(setOf("dts", "truehd"))), ok.prefs("device_player_settings"))
        assertEquals(mapOf("provider" to BackupValue.Str("quad9")), ok.prefs("network_privacy/private_dns"))
        assertEquals(mapOf("tag" to BackupValue.Str("en")), ok.prefs("app_locale"))
        // A store with no file yet is backed up empty, so a restore sets it as this TV had it.
        assertEquals(emptyMap<String, BackupValue>(), ok.prefs("watch_count", 2))
        val files = ok.body.entries.filterIsInstance<BackupEntry.TextFile>()
        assertEquals(listOf("livetv/hidden.json"), files.map { it.store })
        val photos = ok.body.entries.filterIsInstance<BackupEntry.Photo>()
        assertEquals(listOf(BackupFixtures.PHOTO_NAME), photos.map { it.name })
        assertTrue(jpeg.contentEquals(Base64.decode(photos.single().data)))
    }

    @Test
    fun `only the header's profiles are read`() {
        seedTv()
        export()
        val ok = readBack()
        assertTrue(ok.body.entries.none { it is BackupEntry.Prefs && it.profile == 3 })
        val perProfile = BackupCatalog.stores.count { it.perProfile && !it.group.secret }
        assertEquals(2 * perProfile, ok.body.entries.count { it is BackupEntry.Prefs && it.profile != null })
    }

    @Test
    fun `without a PIN the passwords and keys stay out`() {
        seedTv()
        export()
        val ok = readBack()
        assertFalse(ok.header.sealed)
        assertNull(ok.body.sealedSecrets)
        assertTrue(ok.body.entries.none { BackupCatalog.store(it.store)!!.group.secret })
        assertFalse(gunzipped().contains("pass@iptv") || gunzipped().contains("{token}"))
    }

    @Test
    fun `with a PIN the passwords and keys are sealed and open with it`() {
        seedTv()
        export(pin = "246810")
        val ok = readBack()
        assertTrue(ok.header.sealed)
        assertFalse(gunzipped().contains("pass@iptv"))
        val secrets = BackupCodec.openSecrets(ok, "246810")
        assertEquals(mapOf("trakt" to BackupValue.Str("{token}")), secrets.filterIsInstance<BackupEntry.Prefs>().single { it.store == "tracking_accounts" && it.profile == 1 }.values)
        assertEquals("[{\"url\":\"http://user:pass@iptv\"}]", secrets.filterIsInstance<BackupEntry.TextFile>().single().text)
    }

    @Test
    fun `one store over a section is too large, named, and leaves no file`() {
        seedTv()
        dataStore("datastore/watched_items_preferences_p2.preferences_pb", preferencesOf(stringPreferencesKey("watched_items") to "x".repeat(BackupLimits.MAX_SECTION_BYTES)))
        val result = export()
        assertEquals(BackupExportResult.TooLarge("section size", "watched_items_preferences", 2), result)
        assertFalse(target.exists())
    }

    @Test
    fun `a store file far over a section is refused without being read`() {
        // Not a DataStore file at all: refused by size, never parsed.
        File(filesDir, "datastore").mkdirs()
        File(filesDir, "datastore/watch_count.preferences_pb").writeBytes(ByteArray(2 * BackupLimits.MAX_SECTION_BYTES + 1))
        assertEquals(BackupExportResult.TooLarge("section size", "watch_count", 1), export())
        assertFalse(target.exists())
    }

    @Test
    fun `a phone-bound file over the phone cap is too large and deleted`() {
        seedTv()
        val result = runBlocking { writeBackupFile(target, snapshot, header(), null, phoneBound = true, phoneLimit = 10) }
        assertEquals(BackupExportResult.TooLarge("phone size", null, null), result)
        assertFalse(target.exists())
        // The same file is fine when not phone-bound.
        assertTrue(runBlocking { writeBackupFile(target, snapshot, header(), null, phoneBound = false, phoneLimit = 10) } is BackupExportResult.Written)
    }

    @Test
    fun `an oversized text file or photo is too large and named`() {
        text("livetv/hidden.json", "x".repeat(3 * BackupLimits.MAX_TEXT_FILE_CHARS + 1))
        assertEquals(BackupExportResult.TooLarge("file size", "livetv/hidden.json", null), export())
        File(filesDir, "livetv/hidden.json").delete()
        File(filesDir, "my_photos").mkdirs()
        File(filesDir, "my_photos/${BackupFixtures.PHOTO_NAME}").writeBytes(ByteArray(BackupLimits.MAX_PHOTO_BYTES + 1))
        assertEquals(BackupExportResult.TooLarge("photo size", BackupCatalog.MY_PHOTOS, null), export())
        assertFalse(target.exists())
    }

    @Test
    fun `a secret store over a section is too large and named`() {
        dataStore("datastore/tracking_accounts_p2.preferences_pb", preferencesOf(stringPreferencesKey("trakt") to "x".repeat(BackupLimits.MAX_SECTION_BYTES)))
        assertEquals(BackupExportResult.TooLarge("section size", "tracking_accounts", 2), export(pin = "246810"))
        assertFalse(target.exists())
    }

    @Test
    fun `stores together over the memory budget are too large as a whole`() {
        val big = "x".repeat(3 * 1024 * 1024)
        for (store in listOf("watch_count", "search_history", "collections", "library_preferences", "theme_settings", "trailer_settings", "tmdb_settings")) {
            dataStore("datastore/$store.preferences_pb", preferencesOf(stringPreferencesKey("k") to big))
        }
        assertEquals(BackupExportResult.TooLarge("memory budget", null, null), export())
        assertFalse(target.exists())
    }

    @Test
    fun `a store that cannot be read at all fails the export and leaves no file`() {
        File(filesDir, "datastore").mkdirs()
        val store = File(filesDir, "datastore/theme_settings.preferences_pb")
        store.writeBytes(byteArrayOf(0x0A, 0x7F, 1, 2))
        // Not damaged (that exports as the app would recover it, test above) but unreadable.
        assertTrue(store.setReadable(false))
        target.writeText("an older export")
        val result = export()
        assertTrue("$result", result is BackupExportResult.Failed)
        assertFalse(target.exists())
    }

    @Test
    fun `the exporter fills the header from this build and the profile list`() {
        seedTv()
        val now = Instant.fromEpochMilliseconds(1_763_300_000_000)
        val exporter = BackupExporter(
            filesDir,
            { sharedPrefs[it].orEmpty() },
            { listOf(BackupProfile(1, "Arshad", false), BackupProfile(2, "Kids", true)) },
            object : Clock { override fun now() = now },
            Dispatchers.Unconfined,
            deviceName = "SHIELD Android TV",
        )
        assertTrue(runBlocking { exporter.export(target, pin = null) } is BackupExportResult.Written)
        val header = target.inputStream().use { BackupCodec.read(it, Int.MAX_VALUE) as BackupReadResult.Ok }.header
        assertEquals(com.nuvio.tv.BuildConfig.VERSION_CODE, header.appVersionCode)
        assertEquals(com.nuvio.tv.BuildConfig.VERSION_NAME, header.appVersionName)
        assertEquals(now.toEpochMilliseconds(), header.createdAtMs)
        assertEquals(BackupKind.MANUAL, header.kind)
        assertEquals(listOf("Arshad", "Kids"), header.profiles.map { it.name })
        assertEquals("SHIELD Android TV", header.deviceName)
    }

    @Test
    fun `the summary counts addons of profiles with their own, and library and history of every profile`() {
        val ordered = stringPreferencesKey("installed_addon_urls_ordered")
        dataStore("datastore/addon_preferences.preferences_pb", preferencesOf(ordered to "[\"https://a\",\"https://b\"]"))
        // Profile 2 uses the primary's addons: its own leftover list is not counted.
        dataStore("datastore/addon_preferences_p2.preferences_pb", preferencesOf(ordered to "[\"https://z\"]"))
        dataStore("datastore/addon_preferences_p3.preferences_pb", preferencesOf(stringSetPreferencesKey("installed_addon_urls") to setOf("https://b", "https://c")))
        dataStore("datastore/library_preferences.preferences_pb", preferencesOf(stringSetPreferencesKey("library_items") to setOf("x", "y")))
        dataStore("datastore/library_preferences_p3.preferences_pb", preferencesOf(stringSetPreferencesKey("library_items") to setOf("x")))
        dataStore("datastore/watched_items_preferences_p2.preferences_pb", preferencesOf(stringSetPreferencesKey("watched_items") to setOf("1", "2", "3")))
        val profiles = listOf(BackupProfile(1, "Arshad", false), BackupProfile(2, "Kids", true), BackupProfile(3, "Sara", false))
        val exporter = BackupExporter(filesDir, { emptyMap<String, Any>() }, { profiles }, Clock.System, Dispatchers.Unconfined)
        val summary = runBlocking { exporter.summary() }!!
        assertEquals(profiles, summary.profiles)
        assertEquals(3, summary.addons)
        assertEquals(3, summary.library)
        assertEquals(3, summary.history)
    }

    @Test
    fun `a summary count whose store cannot be read is unknown, the others still count`() {
        dataStore("datastore/library_preferences.preferences_pb", preferencesOf(stringSetPreferencesKey("library_items") to setOf("x")))
        val watched = File(filesDir, "datastore/watched_items_preferences.preferences_pb")
        watched.writeBytes(byteArrayOf(0x0A, 0x7F, 1, 2))
        assertTrue(watched.setReadable(false))
        val exporter = BackupExporter(filesDir, { emptyMap<String, Any>() }, { listOf(BackupProfile(1, "Arshad", false)) }, Clock.System, Dispatchers.Unconfined)
        val summary = runBlocking { exporter.summary() }!!
        assertEquals(1, summary.library)
        assertNull(summary.history)
        // No stored addon list: the app shows its defaults, which the count does not know.
        assertNull(summary.addons)
    }

    @Test
    fun `a summary count whose store is damaged beyond its shadow copy is unknown`() {
        File(filesDir, "datastore").mkdirs()
        File(filesDir, "datastore/library_preferences_p2.preferences_pb").writeBytes(byteArrayOf(0x0A, 0x7F, 1, 2))
        dataStore("datastore/library_preferences.preferences_pb", preferencesOf(stringSetPreferencesKey("library_items") to setOf("x")))
        val profiles = listOf(BackupProfile(1, "Arshad", false), BackupProfile(2, "Kids", false))
        val exporter = BackupExporter(filesDir, { emptyMap<String, Any>() }, { profiles }, Clock.System, Dispatchers.Unconfined)
        assertNull(runBlocking { exporter.summary() }!!.library)
    }

    private fun gunzipped(): String = BackupFixtures.gunzip(target.readBytes())
}
