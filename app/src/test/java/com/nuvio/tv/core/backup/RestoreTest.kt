package com.nuvio.tv.core.backup

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Instant

class RestoreTest {

    /** An Error escapes every catch in the applier, as a crash or a kill would end the run. */
    private class Crash : Error()

    private class FakePrefs : SharedPrefsFiles {
        val files = HashMap<String, Map<String, Any>>()
        var crashOnReplace = false
        override fun read(name: String): Map<String, *> = files[name].orEmpty()
        override fun replace(name: String, values: Map<String, Any>) {
            if (crashOnReplace) throw Crash()
            files[name] = values.toMap()
        }
    }

    private val root: File = Files.createTempDirectory("restore").toFile()
    private val filesDir = File(root, "files").apply { mkdirs() }
    private val dirs = RestoreDirs(filesDir)
    private val prefs = FakePrefs()
    private var nowMs = 1_763_000_000_000L
    private val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(nowMs)
    }
    private val vc = BuildConfig.VERSION_CODE
    private var space = Long.MAX_VALUE
    private val pin = "246810"

    private val tvProfiles = listOf(BackupProfile(1, "Living room", false), BackupProfile(3, "kids", false))
    private val exporter = BackupExporter(filesDir, { prefs.read(it) }, { tvProfiles }, clock, Dispatchers.Unconfined)
    private val copies = PreRestoreCopies(dirs.copies, exporter, vc, clock, Dispatchers.Unconfined)
    private val stager = RestoreStager(filesDir, prefs, ACCOUNT, copies, vc, clock, Dispatchers.Unconfined, enabled = true) { space }

    private fun applier(versionCode: Int = vc) = RestoreApplier(filesDir, prefs, ACCOUNT, versionCode, clock)

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    // --- TV state -------------------------------------------------------------------------------

    private fun dataStore(path: String, prefs: Preferences) = runBlocking { PrefsFiles.write(File(filesDir, path), prefs) }

    private fun values(path: String): Map<String, Any>? =
        runBlocking { PrefsFiles.read(File(filesDir, path)) }?.asMap()?.mapKeys { it.key.name }

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 7)

    private fun photoName(n: Int) = "avatar-17630000000${n.toString().padStart(2, '0')}-0123abcd-4567-89ab-cdef-0123456789ab.jpg"

    private fun seedTv() {
        val profilesJson = """[{"id":1,"name":"Living room","avatarColorHex":"#1E88E5"},{"id":3,"name":"kids","avatarColorHex":"#000000","extra":1}]"""
        dataStore("datastore/profile_settings.preferences_pb", preferencesOf(stringPreferencesKey("profiles_json") to profilesJson))
        dataStore("datastore/theme_settings.preferences_pb", preferencesOf(stringPreferencesKey("accent") to "old"))
        File(filesDir, "datastore/theme_settings.preferences_pb.bak").writeText("old shadow")
        dataStore("datastore/theme_settings_p3.preferences_pb", preferencesOf(stringPreferencesKey("accent") to "oldkids"))
        dataStore(
            "datastore/library_preferences.preferences_pb",
            preferencesOf(stringPreferencesKey("library_items") to "[a]", intPreferencesKey("library_mutation_revision") to 7),
        )
        dataStore("datastore/trailer_settings.preferences_pb", preferencesOf(stringPreferencesKey("t") to "tv"))
        dataStore("datastore/profile_look_p3.preferences_pb", preferencesOf(stringPreferencesKey("look") to "tvlook"))
        dataStore("datastore/device_player_settings.preferences_pb", preferencesOf(stringPreferencesKey("tunneling") to "tv"))
        dataStore("datastore/layout_settings_p3.preferences_pb", preferencesOf(stringPreferencesKey("home_catalog_order_keys") to "tv-order"))
        dataStore("datastore/addon_preferences_p3.preferences_pb", preferencesOf(stringPreferencesKey("urls") to "tv-addons"))
        File(filesDir, "cw_enrichment").mkdirs()
        File(filesDir, "cw_enrichment/nextup_3.json").writeText("[]")
        File(filesDir, "cw_enrichment/nextup_2.json").writeText("[]")
        File(filesDir, "cw_enrichment/inprogress_3.json").writeText("[]")
        dataStore("datastore/watched_series_cache_p3.preferences_pb", preferencesOf(stringPreferencesKey("w") to "x"))
        File(filesDir, "datastore/watched_series_cache_p3.preferences_pb.bak").writeText("shadow")
        // last_ok_check_ms is an excluded key: kept from the TV in any store.
        prefs.files["app_locale"] = mapOf("tag" to "en", "last_ok_check_ms" to 9L)
    }

    private fun signIn() {
        prefs.files[ACCOUNT] = mapOf("sb-abc-def-session" to "{}")
    }

    // --- The backup -----------------------------------------------------------------------------

    private val backupProfiles = listOf(BackupProfile(1, "Arshad", false), BackupProfile(2, "Kids", false), BackupProfile(4, "Sara", false))

    private fun header(sealed: Boolean = false, kidsAddons: Boolean = false) = BackupHeader(
        BACKUP_FORMAT_ID, BACKUP_FORMAT_VERSION, vc, "1.3.0-ke104", nowMs - 1_000, BackupKind.MANUAL,
        backupProfiles.map { if (it.id == 2) it.copy(usesPrimaryAddons = kidsAddons) else it }, sealed,
    )

    private fun str(v: String) = BackupValue.Str(v)

    private val entries = listOf(
        BackupEntry.Prefs("theme_settings", 1, mapOf("accent" to str("amber"))),
        BackupEntry.Prefs("theme_settings", 2, mapOf("accent" to str("teal"))),
        BackupEntry.Prefs("library_preferences", 1, mapOf("library_items" to str("[b]"))),
        BackupEntry.Prefs("profile_look", 2, mapOf("look" to str("backuplook"))),
        BackupEntry.Prefs("profile_look", 4, mapOf("look" to str("sara"))),
        BackupEntry.Prefs("layout_settings", 2, mapOf("home_catalog_order_keys" to str("backup-order"), "rows" to str("3"))),
        BackupEntry.Prefs("addon_preferences", 2, mapOf("urls" to str("backup-addons"))),
        BackupEntry.Prefs("device_player_settings", values = mapOf("tunneling" to str("backup"))),
        BackupEntry.Prefs("app_locale", values = mapOf("tag" to str("de"))),
        BackupEntry.TextFile("livetv/hidden.json", "{\"keys\":[1]}"),
        BackupEntry.Photo(photoName(1), Base64.encode(jpeg)),
    )

    private val secrets = listOf(
        BackupEntry.Prefs("tracking_accounts", 1, mapOf("trakt" to str("{token}"))),
        BackupEntry.Prefs("tmdb_api_key", values = mapOf("user_key" to str("k"))),
    )

    private fun backup(sealed: Boolean = false, kidsAddons: Boolean = false): ByteArray {
        val h = header(sealed, kidsAddons)
        val box = if (sealed) BackupCodec.sealSecrets(h, secrets, pin, iterations = 1_000) else null
        return ByteArrayOutputStream().also { BackupCodec.write(it, h, BackupBody(entries, box)) }.toByteArray()
    }

    private fun source(bytes: ByteArray) = RestoreSource.Stream { ByteArrayInputStream(bytes) }

    private val all = RestoreRequest(profiles = setOf(1, 2, 4), device = true, secrets = false)

    private fun stage(bytes: ByteArray = backup(), request: RestoreRequest = all) = runBlocking { stager.stage(source(bytes), request) }

    private fun notice() = runBlocking { takeRestoreNotice(filesDir) }

    // --- Tests ----------------------------------------------------------------------------------

    @Test
    fun `signed out, matched profiles get their stores, the TV keeps its excluded keys and untouched stores`() {
        seedTv()
        val staged = stage()
        assertTrue("$staged", staged is RestoreStageResult.Staged)
        assertTrue(dirs.marker.isFile)
        applier().applyIfStaged()

        assertEquals(mapOf("accent" to "amber"), values("datastore/theme_settings.preferences_pb"))
        assertFalse(File(filesDir, "datastore/theme_settings.preferences_pb.bak").exists())
        // Backup "Kids" (2) restores into the TV's "kids" (3).
        assertEquals(mapOf("accent" to "teal"), values("datastore/theme_settings_p3.preferences_pb"))
        assertNull(values("datastore/theme_settings_p2.preferences_pb"))
        assertEquals(mapOf("library_items" to "[b]", "library_mutation_revision" to 7), values("datastore/library_preferences.preferences_pb"))
        assertEquals(mapOf("t" to "tv"), values("datastore/trailer_settings.preferences_pb"))
        assertEquals(mapOf("tunneling" to "backup"), values("datastore/device_player_settings.preferences_pb"))
        assertEquals(mapOf("tag" to "de", "last_ok_check_ms" to 9L), prefs.files["app_locale"])
        assertEquals("{\"keys\":[1]}", File(filesDir, "livetv/hidden.json").readText())
        assertTrue(File(filesDir, "my_photos/${photoName(1)}").readBytes().contentEquals(jpeg))
        assertFalse(File(filesDir, "cw_enrichment/nextup_3.json").exists())
        assertFalse(File(filesDir, "cw_enrichment/inprogress_3.json").exists())
        assertFalse(File(filesDir, "datastore/watched_series_cache_p3.preferences_pb").exists())
        assertFalse(File(filesDir, "datastore/watched_series_cache_p3.preferences_pb.bak").exists())
        assertTrue(File(filesDir, "cw_enrichment/nextup_2.json").exists())
        // No PIN, no secrets: the stores stay as they were (absent from the restore).
        assertNull(values("datastore/tracking_accounts.preferences_pb"))

        assertEquals(RestoreNotice.Done(listOf("Sara"), 0), notice())
        assertNull(notice())
        assertFalse(dirs.marker.exists() || dirs.staged.exists())
    }

    @Test
    fun `This TV settings unticked and a profile unticked stay as they are`() {
        seedTv()
        stage(request = RestoreRequest(profiles = setOf(1), device = false, secrets = false))
        applier().applyIfStaged()
        assertEquals(mapOf("accent" to "amber"), values("datastore/theme_settings.preferences_pb"))
        assertEquals(mapOf("accent" to "oldkids"), values("datastore/theme_settings_p3.preferences_pb"))
        assertTrue(File(filesDir, "cw_enrichment/nextup_3.json").exists())
        assertEquals(mapOf("tunneling" to "tv"), values("datastore/device_player_settings.preferences_pb"))
        assertEquals(mapOf("tag" to "en", "last_ok_check_ms" to 9L), prefs.files["app_locale"])
        assertEquals(RestoreNotice.Done(emptyList(), 0), notice())
    }

    @Test
    fun `signed in, only this TV's own items restore into the matched profiles`() {
        seedTv()
        signIn()
        stage()
        applier().applyIfStaged()
        assertEquals(mapOf("accent" to "old"), values("datastore/theme_settings.preferences_pb"))
        assertEquals(mapOf("library_items" to "[a]", "library_mutation_revision" to 7), values("datastore/library_preferences.preferences_pb"))
        assertEquals(mapOf("look" to "backuplook"), values("datastore/profile_look_p3.preferences_pb"))
        assertEquals(mapOf("tunneling" to "backup"), values("datastore/device_player_settings.preferences_pb"))
        assertTrue(notice() is RestoreNotice.Done)
    }

    @Test
    fun `an account change between confirm and restart applies nothing`() {
        seedTv()
        stage()
        signIn()
        applier().applyIfStaged()
        assertEquals(mapOf("accent" to "old"), values("datastore/theme_settings.preferences_pb"))
        assertEquals(RestoreNotice.AccountChanged, notice())
        assertFalse(dirs.marker.exists() || dirs.staged.exists())
    }

    @Test
    fun `signing out between confirm and restart applies nothing either`() {
        seedTv()
        signIn()
        stage()
        prefs.files.remove(ACCOUNT)
        applier().applyIfStaged()
        assertEquals(mapOf("look" to "tvlook"), values("datastore/profile_look_p3.preferences_pb"))
        assertEquals(RestoreNotice.AccountChanged, notice())
    }

    @Test
    fun `an unticked profile's opened secrets are not staged`() {
        seedTv()
        stage(backup(sealed = true), RestoreRequest(profiles = setOf(2), device = false, secrets = true, pin = pin))
        assertFalse(dirs.stagedProfileStore(1, BackupCatalog.store("tracking_accounts")!!).exists())
        applier().applyIfStaged()
        assertNull(values("datastore/tracking_accounts.preferences_pb"))
        // TV-wide keys travel with the ticked passwords and keys.
        assertEquals(mapOf("user_key" to "k"), values("datastore/tmdb_api_key.preferences_pb"))
        assertFalse(RestoreRequest(setOf(1), false, true, pin).toString().contains(pin))
    }

    @Test
    fun `had_nuvio_auth alone counts as signed in`() {
        seedTv()
        dataStore("datastore/auth_session_notice_store.preferences_pb", preferencesOf(androidx.datastore.preferences.core.booleanPreferencesKey("had_nuvio_auth") to true))
        stage()
        applier().applyIfStaged()
        assertEquals(mapOf("accent" to "old"), values("datastore/theme_settings.preferences_pb"))
        assertEquals(mapOf("look" to "backuplook"), values("datastore/profile_look_p3.preferences_pb"))
        assertEquals(RestoreNotice.Done(listOf("Sara"), 0), notice())
    }

    @Test
    fun `signed in with the PIN, the passwords and keys restore and synced stores do not`() {
        seedTv()
        signIn()
        stage(backup(sealed = true), all.copy(secrets = true, pin = pin))
        applier().applyIfStaged()
        assertEquals(mapOf("trakt" to "{token}"), values("datastore/tracking_accounts.preferences_pb"))
        assertEquals(mapOf("user_key" to "k"), values("datastore/tmdb_api_key.preferences_pb"))
        assertEquals(mapOf("accent" to "old"), values("datastore/theme_settings.preferences_pb"))
    }

    @Test
    fun `a run is counted before it applies, so a crash mid-apply reruns once and a second crash gives up`() {
        seedTv()
        stage()
        prefs.crashOnReplace = true
        assertThrows(Crash::class.java) { applier().applyIfStaged() }
        assertEquals(1, runBlocking { PrefsFiles.read(dirs.marker) }!!.let(RestoreMarker::of)!!.attempts)
        assertTrue(dirs.staged.isDirectory)
        // Profile stores come before This TV's: already written by the crashed run.
        assertEquals(mapOf("accent" to "amber"), values("datastore/theme_settings.preferences_pb"))

        assertThrows(Crash::class.java) { applier().applyIfStaged() }
        assertEquals(2, runBlocking { PrefsFiles.read(dirs.marker) }!!.let(RestoreMarker::of)!!.attempts)
        prefs.crashOnReplace = false
        applier().applyIfStaged()
        assertEquals(RestoreNotice.StoppedPartWay, notice())
        assertFalse(dirs.marker.exists() || dirs.staged.exists())
    }

    @Test
    fun `a rerun after a crash ends as a clean run would`() {
        seedTv()
        stage()
        prefs.crashOnReplace = true
        assertThrows(Crash::class.java) { applier().applyIfStaged() }
        prefs.crashOnReplace = false
        applier().applyIfStaged()
        assertEquals(RestoreNotice.Done(listOf("Sara"), 0), notice())
        assertEquals(mapOf("library_items" to "[b]", "library_mutation_revision" to 7), values("datastore/library_preferences.preferences_pb"))
        assertEquals(mapOf("tag" to "de", "last_ok_check_ms" to 9L), prefs.files["app_locale"])
        assertEquals(1, File(filesDir, "my_photos").listFiles()!!.size)
    }

    @Test
    fun `matching runs again at apply - a profile renamed since the confirm is skipped`() {
        seedTv()
        stage()
        val renamed = """[{"id":1,"name":"Living room","avatarColorHex":"#1E88E5"},{"id":3,"name":"Teens","avatarColorHex":"#000000"}]"""
        dataStore("datastore/profile_settings.preferences_pb", preferencesOf(stringPreferencesKey("profiles_json") to renamed))
        applier().applyIfStaged()
        assertEquals(mapOf("accent" to "oldkids"), values("datastore/theme_settings_p3.preferences_pb"))
        assertEquals(RestoreNotice.Done(listOf("Kids", "Sara"), 0), notice())
    }

    @Test
    fun `a missing or garbled profile list matches the primary only`() {
        for (list in listOf(null, "not json")) {
            seedTv()
            stage()
            if (list == null) File(filesDir, "datastore/profile_settings.preferences_pb").delete()
            else dataStore("datastore/profile_settings.preferences_pb", preferencesOf(stringPreferencesKey("profiles_json") to list))
            applier().applyIfStaged()
            assertEquals(mapOf("accent" to "amber"), values("datastore/theme_settings.preferences_pb"))
            assertEquals(mapOf("accent" to "oldkids"), values("datastore/theme_settings_p3.preferences_pb"))
            assertEquals(RestoreNotice.Done(listOf("Kids", "Sara"), 0), notice())
        }
    }

    @Test
    fun `after a cut-off run any refusal reports part-way, and a garbled marker is dropped`() {
        seedTv()
        stage()
        val marker = runBlocking { PrefsFiles.read(dirs.marker) }!!.let(RestoreMarker::of)!!
        runBlocking { PrefsFiles.write(dirs.marker, marker.copy(attempts = 1).toPreferences()) }
        nowMs += RestoreApplier.MAX_AGE_MS + 1
        applier().applyIfStaged()
        assertEquals(RestoreNotice.StoppedPartWay, notice())

        nowMs = 1_763_000_000_000L
        stage()
        dirs.marker.writeText("not a proto")
        applier().applyIfStaged()
        assertEquals(RestoreNotice.StoppedPartWay, notice())
        assertFalse(dirs.marker.exists() || dirs.staged.exists())
        assertEquals(mapOf("accent" to "old"), values("datastore/theme_settings.preferences_pb"))
    }

    @Test
    fun `temp files a killed run left beside its targets are swept`() {
        seedTv()
        val leftover = File(filesDir, "datastore/tracking_accounts.preferences_pb$RESTORE_TMP").apply { writeText("token") }
        stage()
        applier().applyIfStaged()
        assertFalse(leftover.exists())
    }

    @Test
    fun `a staging too old, dated in the future or from another version is discarded unapplied`() {
        for (case in 0..2) {
            seedTv()
            stage()
            val applier = when (case) {
                0 -> applier().also { nowMs += RestoreApplier.MAX_AGE_MS + 1 }
                1 -> applier().also { nowMs -= 1 }
                else -> applier(versionCode = vc + 1)
            }
            applier.applyIfStaged()
            assertEquals("case $case", mapOf("accent" to "old"), values("datastore/theme_settings.preferences_pb"))
            assertEquals("case $case", RestoreNotice.Discarded, notice())
            assertFalse(dirs.marker.exists() || dirs.staged.exists())
            nowMs = 1_763_000_000_000L
        }
    }

    @Test
    fun `a run cut off once runs again, a third start gives up with the part-way notice`() {
        seedTv()
        stage()
        val marker = runBlocking { PrefsFiles.read(dirs.marker) }!!.let(RestoreMarker::of)!!
        // As if the first run was killed after counting itself.
        runBlocking { PrefsFiles.write(dirs.marker, marker.copy(attempts = 1).toPreferences()) }
        applier().applyIfStaged()
        assertEquals(mapOf("accent" to "amber"), values("datastore/theme_settings.preferences_pb"))
        assertTrue(notice() is RestoreNotice.Done)

        seedTv()
        stage()
        runBlocking { PrefsFiles.write(dirs.marker, marker.copy(attempts = 2).toPreferences()) }
        applier().applyIfStaged()
        assertEquals(mapOf("accent" to "old"), values("datastore/theme_settings.preferences_pb"))
        assertEquals(RestoreNotice.StoppedPartWay, notice())
        assertFalse(dirs.marker.exists() || dirs.staged.exists())
    }

    @Test
    fun `an error inside the apply stops it with the part-way notice and drops the staging`() {
        seedTv()
        stage()
        dirs.stagedProfileStore(2, BackupCatalog.store("theme_settings")!!).writeText("not a proto")
        applier().applyIfStaged()
        assertEquals(RestoreNotice.StoppedPartWay, notice())
        assertFalse(dirs.marker.exists() || dirs.staged.exists())
    }

    @Test
    fun `an unreadable target is replaced without its kept keys`() {
        seedTv()
        File(filesDir, "datastore/library_preferences.preferences_pb").writeText("not a proto")
        stage()
        applier().applyIfStaged()
        assertEquals(mapOf("library_items" to "[b]"), values("datastore/library_preferences.preferences_pb"))
    }

    @Test
    fun `a damaged target keeps the kept keys of its shadow copy`() {
        seedTv()
        val library = File(filesDir, "datastore/library_preferences.preferences_pb")
        library.copyTo(File(library.path + ".bak"))
        library.writeText("not a proto")
        stage()
        applier().applyIfStaged()
        assertEquals(mapOf("library_items" to "[b]", "library_mutation_revision" to 7), values("datastore/library_preferences.preferences_pb"))
    }

    @Test
    fun `staging leftovers without a marker are deleted and never applied`() {
        seedTv()
        stage()
        dirs.marker.delete()
        applier().applyIfStaged()
        assertFalse(dirs.staged.exists())
        assertEquals(mapOf("accent" to "old"), values("datastore/theme_settings.preferences_pb"))
        assertNull(notice())
    }

    @Test
    fun `addons differ - the TV keeps its addon store and addon-owned layout keys`() {
        seedTv()
        stage(backup(kidsAddons = true))
        applier().applyIfStaged()
        assertEquals(mapOf("urls" to "tv-addons"), values("datastore/addon_preferences_p3.preferences_pb"))
        assertEquals(mapOf("home_catalog_order_keys" to "tv-order", "rows" to "3"), values("datastore/layout_settings_p3.preferences_pb"))
    }

    @Test
    fun `addons same - the addon store and layout keys come from the backup`() {
        seedTv()
        stage()
        applier().applyIfStaged()
        assertEquals(mapOf("urls" to "backup-addons"), values("datastore/addon_preferences_p3.preferences_pb"))
        assertEquals(mapOf("home_catalog_order_keys" to "backup-order", "rows" to "3"), values("datastore/layout_settings_p3.preferences_pb"))
    }

    @Test
    fun `secrets need the right PIN, a wrong one stages nothing and makes no copy`() {
        seedTv()
        val withSecrets = all.copy(secrets = true, pin = "999999")
        assertEquals(RestoreStageResult.WrongPin, stage(backup(sealed = true), withSecrets))
        assertEquals(RestoreStageResult.WrongPin, stage(backup(sealed = true), withSecrets.copy(pin = null)))
        assertFalse(dirs.marker.exists() || dirs.staged.exists())
        assertEquals(emptyList<File>(), runBlocking { copies.list() })

        assertTrue(stage(backup(sealed = true), withSecrets.copy(pin = pin)) is RestoreStageResult.Staged)
        applier().applyIfStaged()
        assertEquals(mapOf("trakt" to "{token}"), values("datastore/tracking_accounts.preferences_pb"))
        assertEquals(mapOf("user_key" to "k"), values("datastore/tmdb_api_key.preferences_pb"))
    }

    @Test
    fun `secrets unticked leave the sealed part unopened`() {
        seedTv()
        assertTrue(stage(backup(sealed = true), all) is RestoreStageResult.Staged)
        applier().applyIfStaged()
        assertNull(values("datastore/tracking_accounts.preferences_pb"))
    }

    @Test
    fun `the pre-restore copy holds the TV as it was, secrets unsealed, and undoes the restore`() {
        seedTv()
        dataStore("datastore/tracking_accounts.preferences_pb", preferencesOf(stringPreferencesKey("trakt") to "tv-token"))
        val staged = stage(backup(sealed = true), all.copy(secrets = true, pin = pin)) as RestoreStageResult.Staged
        assertEquals(listOf(staged.copy), runBlocking { copies.list() })
        val copy = staged.copy.inputStream().use { BackupCodec.read(it, vc, trustedCopy = true) } as BackupReadResult.Ok
        assertEquals(BackupKind.PRE_RESTORE_COPY, copy.header.kind)
        assertEquals(tvProfiles, copy.header.profiles)
        // A restore only adds photos, so the copy holds none.
        assertTrue(copy.body.entries.none { it is BackupEntry.Photo })
        assertTrue(copy.body.copySecrets.any { it is BackupEntry.Prefs && it.store == "tracking_accounts" && it.values["trakt"] == str("tv-token") })
        applier().applyIfStaged()
        assertEquals(mapOf("trakt" to "{token}"), values("datastore/tracking_accounts.preferences_pb"))

        nowMs += 60_000
        val undo = runBlocking { stager.stage(RestoreSource.Copy(staged.copy), RestoreRequest(setOf(1, 3), device = true, secrets = true)) }
        assertTrue("$undo", undo is RestoreStageResult.Staged)
        applier().applyIfStaged()
        assertEquals(mapOf("accent" to "old"), values("datastore/theme_settings.preferences_pb"))
        assertEquals(mapOf("accent" to "oldkids"), values("datastore/theme_settings_p3.preferences_pb"))
        assertEquals(mapOf("trakt" to "tv-token"), values("datastore/tracking_accounts.preferences_pb"))
        assertEquals(mapOf("tag" to "en", "last_ok_check_ms" to 9L), prefs.files["app_locale"])
    }

    @Test
    fun `a backup made on System default language resets a language chosen since`() {
        // System default: never chosen (no tag), or picked again in the language picker (an empty tag).
        // last_ok_check_ms is an excluded key, kept from the TV.
        for (made in listOf(mapOf("last_ok_check_ms" to 9L), mapOf("locale_tag" to "", "last_ok_check_ms" to 9L))) {
            seedTv()
            prefs.files["app_locale"] = made
            val file = File(root, "made-on-default.json.gz")
            assertTrue(runBlocking { exporter.export(file, pin = null) } is BackupExportResult.Written)
            prefs.files["app_locale"] = mapOf("locale_tag" to "de", "last_ok_check_ms" to 9L)
            nowMs += 60_000
            val staged = runBlocking { stager.stage(source(file.readBytes()), RestoreRequest(setOf(1, 3), device = true, secrets = false)) }
            assertTrue("$staged", staged is RestoreStageResult.Staged)
            applier().applyIfStaged()
            assertEquals(made, prefs.files["app_locale"])
        }
    }

    @Test
    fun `a copy is trusted only from the copies folder`() {
        seedTv()
        val staged = stage() as RestoreStageResult.Staged
        val outside = File(root, staged.copy.name).also { staged.copy.copyTo(it) }
        val result = runBlocking { stager.stage(RestoreSource.Copy(outside), all) }
        assertTrue("$result", result is RestoreStageResult.Refused && (result.read as BackupReadResult.Invalid).reason == BackupReadResult.Reason.DAMAGED)
    }

    @Test
    fun `copies keep the newest two, never the one being restored`() {
        seedTv()
        val made = (1..3).map { nowMs += 1_000; (stage() as RestoreStageResult.Staged).copy }
        assertEquals(made.takeLast(2).reversed(), runBlocking { copies.list() })
        // Restoring the oldest kept copy keeps it, beside the new one.
        nowMs += 1_000
        val restoring = made[1]
        val again = runBlocking { stager.stage(RestoreSource.Copy(restoring), RestoreRequest(setOf(1), device = false, secrets = false)) } as RestoreStageResult.Staged
        assertEquals(listOf(again.copy, made[2], restoring), runBlocking { copies.list() })
    }

    @Test
    fun `a clock set back never prunes the copy just made`() {
        seedTv()
        repeat(2) { nowMs += 1_000; stage() }
        nowMs -= 60_000
        val made = (stage() as RestoreStageResult.Staged).copy
        assertTrue(made.exists())
        assertEquals(3, runBlocking { copies.list() }.size)
    }

    @Test
    fun `a copy that cannot be made stops the restore before anything is staged`() {
        seedTv()
        dirs.copies.parentFile!!.mkdirs()
        dirs.copies.writeText("a file where the folder goes")
        val result = stage()
        assertTrue("$result", result is RestoreStageResult.CopyFailed)
        assertFalse(dirs.marker.exists() || dirs.staged.exists())
    }

    @Test
    fun `refused files, too little space and the switch stage nothing`() {
        seedTv()
        val newer = ByteArrayOutputStream().also { BackupCodec.write(it, header().copy(appVersionCode = vc + 1), BackupBody(entries)) }.toByteArray()
        assertTrue(stage(newer) is RestoreStageResult.Refused)
        space = 0
        assertEquals(RestoreStageResult.NoSpace, stage())
        val off = RestoreStager(filesDir, prefs, ACCOUNT, copies, vc, clock, Dispatchers.Unconfined, enabled = false)
        assertEquals(RestoreStageResult.Disabled, runBlocking { off.stage(source(backup()), all) })
        assertFalse(dirs.marker.exists() || dirs.staged.exists())
        assertEquals(emptyList<File>(), runBlocking { copies.list() })
    }

    @Test
    fun `photos are added by name, never deleted, and none past the cap`() {
        seedTv()
        val gallery = File(filesDir, "my_photos").apply { mkdirs() }
        // 11 of the TV's own photos leave one free slot for the backup's three.
        for (n in 1..11) File(gallery, photoName(n + 10)).writeBytes(jpeg)
        val photos = (1..3).map { BackupEntry.Photo(photoName(it), Base64.encode(jpeg)) }
        val bytes = ByteArrayOutputStream().also { BackupCodec.write(it, header(), BackupBody(entries.filter { it !is BackupEntry.Photo } + photos)) }.toByteArray()
        stage(bytes)
        applier().applyIfStaged()
        assertEquals(12, gallery.listFiles()!!.size)
        assertTrue(File(gallery, photoName(1)).exists())
        assertEquals(RestoreNotice.Done(listOf("Sara"), 2), notice())
    }

    @Test
    fun `every backup work file lives in the one folder the sign-out wipe deletes`() {
        val root = File(filesDir, RestoreDirs.DIR_NAME)
        assertEquals(root, dirs.root)
        for (f in listOf(dirs.staged, dirs.marker, dirs.notice, dirs.copies, dirs.stagedPhotos)) {
            assertTrue(f.path, f.toPath().startsWith(root.toPath()))
        }
        seedTv()
        val copy = (stage() as RestoreStageResult.Staged).copy
        assertTrue(copy.toPath().startsWith(root.toPath()))
        val auth = File("src/main/java/com/nuvio/tv/core/auth/AccountLocalDataResetService.kt").readText()
        assertTrue("the sign-out wipe no longer deletes the backup work folder", "RestoreDirs.DIR_NAME" in auth)
    }

    @Test
    fun `marker and notice round-trip, and a foreign marker is refused`() {
        val marker = RestoreMarker(vc, 5, 1, true, listOf(BackupProfile(1, "A", false), BackupProfile(3, "B", true)))
        assertEquals(marker, RestoreMarker.of(marker.toPreferences()))
        assertNull(RestoreMarker.of(marker.copy(profiles = listOf(BackupProfile(2, "B", true))).toPreferences()))
        assertNull(RestoreMarker.of(preferencesOf(intPreferencesKey("version_code") to 1)))
        for (n in listOf(RestoreNotice.Done(listOf("B", "A", "B"), 2), RestoreNotice.StoppedPartWay, RestoreNotice.Discarded, RestoreNotice.AccountChanged)) {
            assertEquals(n, restoreNoticeOf(n.toPreferences()))
        }
    }

    @Test
    fun `kept keys come from the target, never from the backup`() {
        val incoming = preferencesOf(stringPreferencesKey("a") to "new", intPreferencesKey("library_mutation_revision") to 99)
        val current = preferencesOf(stringPreferencesKey("a") to "old", intPreferencesKey("watch_progress_delta_cursor") to 3)
        val merged = withKeptKeys(incoming, current, BackupCatalog.excludedKeys)
        assertEquals(mapOf("a" to "new", "watch_progress_delta_cursor" to 3), merged.asMap().mapKeys { it.key.name })
    }

    @Test
    fun `secrets opened from a read share its memory budget`() {
        val h = header(sealed = true)
        val box = BackupCodec.sealSecrets(h, secrets, pin, iterations = 1_000)
        BackupCodec.openSecrets(h, box, pin, retained = 0)
        val e = runCatching { BackupCodec.openSecrets(h, box, pin, retained = BackupLimits.MEMORY_BUDGET_BYTES - 10) }.exceptionOrNull()
        assertTrue("$e", e is BackupCheckException && e.detail.contains("memory budget"))
    }

    private companion object {
        const val ACCOUNT = "com.nuvio.tv.ke_preferences"
    }
}
