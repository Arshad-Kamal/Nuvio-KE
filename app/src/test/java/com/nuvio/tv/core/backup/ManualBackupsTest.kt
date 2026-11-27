package com.nuvio.tv.core.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.TimeZone
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class ManualBackupsTest {

    private val root: File = Files.createTempDirectory("manual-backups").toFile()
    private val filesDir = File(root, "files").apply { mkdirs() }
    private val workDir = File(filesDir, "backup_restore")
    private val folderDir = File(root, "external/Nuvio KE backups")
    private var now = Instant.fromEpochMilliseconds(1_763_300_000_000)
    private val clock = object : Clock { override fun now() = now }
    private val profiles = listOf(BackupProfile(1, "Arshad", false))

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun exporter(profiles: List<BackupProfile> = this.profiles) =
        BackupExporter(filesDir, { emptyMap<String, Any>() }, { profiles }, clock, Dispatchers.Unconfined)

    private fun backups(folder: BackupFolder = FileBackupFolder(folderDir), exporter: BackupExporter = exporter()) =
        ManualBackups(exporter, workDir, { folder }, clock, Dispatchers.Unconfined)

    @Test
    fun `back up now saves a readable file and keeps the newest three`() {
        val manual = backups()
        val saved = (1..4).map {
            now += 1.minutes
            (runBlocking { manual.backUpNow(pin = null) } as BackupExportResult.Saved).backup
        }
        val kept = runBlocking { manual.list() }
        assertEquals(saved.takeLast(3).reversed().map { it.name }, kept.map { it.name })
        assertFalse(File(saved.first().id).exists())
        val read = FileBackupFolder(folderDir).open(kept.first()).use { BackupCodec.read(it, Int.MAX_VALUE) }
        assertTrue("$read", read is BackupReadResult.Ok)
        // The temp export is gone.
        assertEquals(emptyList<String>(), workDir.list().orEmpty().toList())
    }

    @Test
    fun `a refused export saves nothing and deletes no backup`() {
        val manual = backups()
        runBlocking { manual.backUpNow(pin = null) }
        // A profile name over the header cap fails the checks.
        val refused = runBlocking { backups(exporter = exporter(listOf(BackupProfile(1, "x".repeat(201), false)))).backUpNow(null) }
        assertEquals(BackupExportResult.Failed("profile name length"), refused)
        assertEquals(1, runBlocking { manual.list() }.size)
        assertEquals(emptyList<String>(), workDir.list().orEmpty().toList())
    }

    @Test
    fun `a too-large export is passed through and keeps the old backups`() {
        val manual = backups()
        runBlocking { manual.backUpNow(pin = null) }
        File(filesDir, "datastore").mkdirs()
        File(filesDir, "datastore/watch_count.preferences_pb").writeBytes(ByteArray(2 * BackupLimits.MAX_SECTION_BYTES + 1))
        now += 1.minutes
        assertEquals(BackupExportResult.TooLarge("section size", "watch_count", 1), runBlocking { manual.backUpNow(pin = null) })
        assertEquals(1, runBlocking { manual.list() }.size)
        assertEquals(emptyList<String>(), workDir.list().orEmpty().toList())
    }

    @Test
    fun `a save that fails reports it and keeps the old backups`() {
        val real = FileBackupFolder(folderDir)
        runBlocking { backups(real).backUpNow(null) }
        val failing = object : BackupFolder by real {
            override fun save(source: File, name: String): SavedBackup = throw IOException("disk full")
        }
        assertEquals(BackupExportResult.Failed("save failed"), runBlocking { backups(failing).backUpNow(null) })
        assertEquals(1, real.list().size)
    }

    @Test
    fun `no backups folder is a failure, not a crash`() {
        val manual = ManualBackups(exporter(), workDir, { null }, clock, Dispatchers.Unconfined)
        assertEquals(BackupExportResult.Failed("no backups folder"), runBlocking { manual.backUpNow(null) })
        assertEquals(emptyList<SavedBackup>(), runBlocking { manual.list() })
    }

    @Test
    fun `the file name follows the mockup, in local time`() {
        val zone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Toronto"))
            // 2026-11-17 02:42 UTC = 2026-11-16 21:42 in Toronto.
            assertEquals("Nuvio KE backup 2026-11-16 2142.json.gz", ManualBackups.fileName(1_794_883_320_000))
        } finally {
            TimeZone.setDefault(zone)
        }
    }

    @Test
    fun `two saves with one name both keep, the second numbered`() {
        val folder = FileBackupFolder(folderDir)
        val source = File(root, "src").apply { writeText("x") }
        val first = folder.save(source, "Nuvio KE backup 2026-11-16 2142.json.gz")
        val second = folder.save(source, "Nuvio KE backup 2026-11-16 2142.json.gz")
        assertEquals("Nuvio KE backup 2026-11-16 2142 (2).json.gz", second.name)
        assertEquals(setOf(first.name, second.name), folder.list().map { it.name }.toSet())
        assertEquals("x", folder.open(second).use(InputStream::readBytes).decodeToString())
    }

    @Test
    fun `a cut-off save leaves no listed file and is cleaned next time`() {
        folderDir.mkdirs()
        File(folderDir, "Nuvio KE backup 2026-11-16 2142.json.gz.tmp").writeText("half")
        val folder = FileBackupFolder(folderDir)
        assertEquals(emptyList<SavedBackup>(), folder.list())
        folder.save(File(root, "src").apply { writeText("x") }, "Nuvio KE backup 2026-11-16 2143.json.gz")
        assertEquals(listOf("Nuvio KE backup 2026-11-16 2143.json.gz"), folderDir.list()!!.toList())
    }

    @Test
    fun `prune keeps the newest by order, then by name`() {
        fun b(name: String, order: Long) = SavedBackup(name, name, 1, order)
        val list = listOf(b("a", 1), b("d", 4), b("b", 2), b("c", 3), b("c2", 3))
        assertEquals(listOf("b", "a"), backupsToPrune(list, 3).map { it.name })
        assertEquals(emptyList<SavedBackup>(), backupsToPrune(list.take(2), 3))
    }
}
