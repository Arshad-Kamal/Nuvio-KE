package com.nuvio.tv.core.backup

import com.nuvio.tv.core.backup.BackupFixtures.INSTALLED
import com.nuvio.tv.core.backup.BackupFixtures.bytes
import com.nuvio.tv.core.backup.BackupFixtures.gunzip
import com.nuvio.tv.core.backup.BackupFixtures.gzip
import com.nuvio.tv.core.backup.BackupFixtures.header
import com.nuvio.tv.core.backup.BackupFixtures.read
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.random.Random

class BackupCodecTest {

    private fun assertInvalid(expected: BackupReadResult.Reason, result: BackupReadResult, detail: String? = null) {
        assertTrue("expected Invalid($expected), got $result", result is BackupReadResult.Invalid && result.reason == expected)
        if (detail != null) assertEquals(detail, (result as BackupReadResult.Invalid).detail)
    }

    private val text get() = gunzip(bytes())
    private val headerLine get() = text.substringBefore('\n')
    /** The fixture's section lines, each without its '\n'. */
    private val sections get() = text.substringAfter('\n').removeSuffix("\n").split('\n')

    private fun file(vararg lines: String): ByteArray = gzip(lines.joinToString("") { "$it\n" })

    private fun prefsLine(store: String, profile: Int?, valuesJson: String): String =
        """{"type":"entry","entry":{"type":"prefs","store":"$store",""" +
            (profile?.let { "\"profile\":$it," } ?: "") + """"values":{$valuesJson}}}"""

    @Test
    fun `write then read round-trips every value type`() {
        val body = BackupBody(BackupFixtures.entries())
        assertEquals(BackupReadResult.Ok(header(), body), read(bytes(body = body)))
    }

    @Test
    fun `the file is the header line, then one line per section`() {
        assertEquals(BackupFixtures.entries().size, sections.size)
        assertTrue(sections.all { it.startsWith("{\"type\":\"entry\"") })
        assertTrue(text.endsWith("\n"))
    }

    @Test
    fun `a NaN float still backs up and restores`() {
        val body = BackupBody(listOf(BackupEntry.Prefs("player_settings", 1, mapOf("x" to BackupValue.FloatValue(Float.NaN)))))
        val ok = read(bytes(body = body)) as BackupReadResult.Ok
        assertTrue(((ok.body.entries.single() as BackupEntry.Prefs).values.getValue("x").raw as Float).isNaN())
    }

    @Test
    fun `values map to the types Preferences stores hold`() {
        assertEquals(BackupValue.LongValue(5L), BackupValue.of(5L))
        assertEquals(setOf("a"), BackupValue.of(setOf("a"))!!.raw)
        assertEquals(null, BackupValue.of(byteArrayOf(1)))
        assertEquals(null, BackupValue.of(setOf(1, 2)))
    }

    @Test
    fun `write refuses a check or limit failure before writing anything`() {
        val out = ByteArrayOutputStream()
        val plainSecret = BackupBody(BackupFixtures.entries() + BackupFixtures.secretEntries().first())
        assertEquals("secret store outside the sealed part", assertThrows(BackupCheckException::class.java) { BackupCodec.write(out, header(), plainSecret) }.detail)
        val excluded = BackupBody(listOf(BackupEntry.Prefs("update_settings", values = mapOf("known_release" to BackupValue.Str("x")))))
        assertEquals("excluded key", assertThrows(BackupCheckException::class.java) { BackupCodec.write(out, header(), excluded) }.detail)
        // One store over a section's token cap (6 per typed value).
        val many = BackupBody(listOf(BackupEntry.Prefs("watch_count", 1, (0..BackupLimits.MAX_SECTION_TOKENS / 6).associate { "k$it" to BackupValue.IntValue(1) })))
        assertTooLarge("token count", "watch_count", 1, assertThrows(BackupCheckException::class.java) { BackupCodec.write(out, header(), many) })
        // One store over a section's byte cap, named with its profile (D41's b13 line).
        val big = BackupBody(BackupFixtures.entries() + BackupEntry.Prefs("watch_count", 1, mapOf("k" to BackupValue.Str("x".repeat(BackupLimits.MAX_SECTION_BYTES)))))
        assertTooLarge("section size", "watch_count", 1, assertThrows(BackupCheckException::class.java) { BackupCodec.write(out, header(), big) })
        // Stores each within a section, together over the memory budget (2 bytes per char).
        val third = BackupValue.Str("x".repeat(4 * 1024 * 1024 - 1024))
        val stores = listOf("watch_count", "search_history", "collections", "library_preferences", "theme_settings")
        val budget = BackupBody(stores.flatMap { s -> listOf(1, 2).map { BackupEntry.Prefs(s, it, mapOf("k" to third)) } })
        // A whole-file limit names no store.
        assertTooLarge("memory budget", null, null, assertThrows(BackupCheckException::class.java) { BackupCodec.write(out, header(), budget) })
        assertEquals(0, out.size())
    }

    private fun assertTooLarge(detail: String, store: String?, profile: Int?, e: BackupCheckException) {
        assertEquals(detail, e.detail)
        assertTrue("tooLarge", e.tooLarge)
        assertEquals(store, e.store)
        assertEquals(profile, e.profile)
    }

    @Test
    fun `a TV-wide text file over its cap is too large and named`() {
        val body = BackupBody(listOf(BackupEntry.TextFile("livetv/hidden.json", "x".repeat(BackupLimits.MAX_TEXT_FILE_CHARS + 1))))
        assertTooLarge("file size", "livetv/hidden.json", null, assertThrows(BackupCheckException::class.java) { BackupCodec.write(ByteArrayOutputStream(), header(), body) })
        val many = BackupBody(List(BackupLimits.MAX_ENTRIES + 1) { BackupEntry.Prefs("theme_settings", 1, emptyMap()) })
        assertTrue(assertThrows(BackupCheckException::class.java) { BackupCodec.write(ByteArrayOutputStream(), header(), many) }.tooLarge)
    }

    @Test
    fun `a secrets payload too big for its section is refused before the key derivation`() {
        val huge = listOf(BackupEntry.Prefs("tmdb_api_key", values = (0..<4).associate { "k$it" to BackupValue.Str("x".repeat(1024 * 1024)) }))
        val started = System.nanoTime()
        val e = assertThrows(BackupCheckException::class.java) { BackupCodec.sealSecrets(header(sealed = true), huge, "123456", iterations = BackupCrypto.MAX_ITERATIONS) }
        assertEquals("section size", e.detail)
        assertTrue(e.tooLarge)
        // 2M PBKDF2 iterations take seconds; the refusal comes first.
        assertTrue(System.nanoTime() - started < 1_000_000_000L)
    }

    @Test
    fun `section limits hold at their exact boundaries`() {
        val spaces = ByteArray(BackupLimits.MAX_SECTION_BYTES) { ' '.code.toByte() }
        SectionLimits(0).admit(spaces, spaces.size)
        val over = assertThrows(BackupTooLargeException::class.java) { SectionLimits(0).admit(spaces + ' '.code.toByte(), spaces.size + 1) }
        assertEquals("section size", over.detail)
        assertFalse(over.wholeFile)
        // The decompressed cap counts every section and its '\n'.
        val atCap = SectionLimits(BackupLimits.DECOMPRESSED_MAX_BYTES - 3)
        atCap.admit("{}".encodeToByteArray(), 2)
        assertTrue(assertThrows(BackupTooLargeException::class.java) { atCap.admit("{}".encodeToByteArray(), 2) }.wholeFile)
        val budget = SectionLimits(0)
        budget.charge(BackupLimits.MEMORY_BUDGET_BYTES)
        assertTrue(assertThrows(BackupTooLargeException::class.java) { budget.charge(1) }.wholeFile)
        val tokens = ",".repeat(5).encodeToByteArray()
        JsonShape(5).feed(tokens, 0, tokens.size)
        assertEquals("token count", assertThrows(BackupShapeException::class.java) { JsonShape(4).feed(tokens, 0, tokens.size) }.detail)
    }

    @Test
    fun `section cost charges strings at 2 bytes a char and each map entry and set element its node`() {
        val empty = SectionCost.of(BackupEntry.Prefs("watch_count", 1, emptyMap()))
        val oneInt = SectionCost.of(BackupEntry.Prefs("watch_count", 1, mapOf("k" to BackupValue.IntValue(1))))
        // Node with table slots (64) + key string (40 + 2) + value object (32).
        assertEquals(64L + 42 + 32, oneInt - empty)
        val set0 = SectionCost.of(BackupEntry.Prefs("watch_count", 1, mapOf("k" to BackupValue.StrSet(emptySet()))))
        val set2 = SectionCost.of(BackupEntry.Prefs("watch_count", 1, mapOf("k" to BackupValue.StrSet(setOf("ab", "cd")))))
        assertEquals(2 * (64L + 44), set2 - set0)
        val text = SectionCost.of(BackupEntry.TextFile("livetv/hidden.json", "x".repeat(100)))
        assertEquals(200L, text - SectionCost.of(BackupEntry.TextFile("livetv/hidden.json", "")))
        val photo = SectionCost.of(BackupEntry.Photo(BackupFixtures.PHOTO_NAME, "AAAA"))
        assertEquals(8L, photo - SectionCost.of(BackupEntry.Photo(BackupFixtures.PHOTO_NAME, "")))
    }

    @Test
    fun `a 0-byte read is not the end of the lines`() {
        var zeroNext = true
        val stuttering = object : InputStream() {
            val inner = ByteArrayInputStream("ab\ncd\n".encodeToByteArray())
            override fun read(): Int = inner.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                zeroNext = !zeroNext
                return if (zeroNext) 0 else inner.read(b, off, minOf(len, 1))
            }
        }
        val lines = LineReader(stuttering)
        val read = generateSequence { lines.next(16)?.let { (bytes, n) -> bytes.decodeToString(0, n) } }.toList()
        assertEquals(listOf("ab", "cd"), read)
    }

    @Test
    fun `a section just under every cap writes and reads back`() {
        val photo = "/9j/" + "A".repeat((BackupLimits.MAX_PHOTO_BYTES / 3) * 4 - 4)
        val body = BackupBody(listOf(BackupEntry.Photo(BackupFixtures.PHOTO_NAME, photo)))
        assertTrue(read(bytes(body = body)) is BackupReadResult.Ok)
        // 6 tokens per typed value plus 12 for the section around them.
        val values = (0..<BackupLimits.MAX_SECTION_TOKENS / 6 - 10).associate { "k$it" to BackupValue.IntValue(1) }
        assertTrue(read(bytes(body = BackupBody(listOf(BackupEntry.Prefs("watch_count", 1, values))))) is BackupReadResult.Ok)
    }

    @Test
    fun `read and write leave the caller's stream open`() {
        var closedIn = false
        var closedOut = false
        val out = object : OutputStream() {
            val buffer = ByteArrayOutputStream()
            override fun write(b: Int) = buffer.write(b)
            override fun close() { closedOut = true }
        }
        BackupCodec.write(out, header(), BackupBody(BackupFixtures.entries()))
        val input = object : InputStream() {
            val source = ByteArrayInputStream(out.buffer.toByteArray())
            override fun read(): Int = source.read()
            override fun close() { closedIn = true }
        }
        assertTrue(BackupCodec.read(input, INSTALLED) is BackupReadResult.Ok)
        assertFalse(closedIn || closedOut)
    }

    @Test
    fun `a newer app versionCode is refused with its version`() {
        val result = read(file(headerLine.replace("\"appVersionCode\":$INSTALLED", "\"appVersionCode\":${INSTALLED + 1}"), "{}"))
        assertEquals(BackupReadResult.NeedsNewerApp("1.3.0-ke103", INSTALLED + 1), result)
    }

    @Test
    fun `a newer format is refused from the header even when the rest is unreadable`() {
        val line = """{"format":"$BACKUP_FORMAT_ID","formatVersion":${BACKUP_FORMAT_VERSION + 1},""" +
            """"appVersionCode":$INSTALLED,"appVersionName":"9.9","profiles":"changed shape"}"""
        assertEquals(BackupReadResult.NeedsNewerApp("9.9", INSTALLED), read(file(line, "<<not json>>")))
    }

    @Test
    fun `a versionCode below the minimum is refused as too old`() {
        val result = read(bytes(header = header(appVersionCode = MIN_BACKUP_VERSION_CODE - 1)))
        assertEquals(BackupReadResult.TooOld("1.3.0-ke103", MIN_BACKUP_VERSION_CODE - 1), result)
    }

    @Test
    fun `same and older versions down to the minimum read`() {
        assertTrue(read(bytes(header = header(appVersionCode = MIN_BACKUP_VERSION_CODE))) is BackupReadResult.Ok)
        assertTrue(read(bytes(header = header(appVersionCode = INSTALLED))) is BackupReadResult.Ok)
    }

    @Test
    fun `not gzip, another format id, or no header line is not a backup`() {
        assertInvalid(BackupReadResult.Reason.NOT_A_BACKUP, read("{\"format\":\"x\"}".encodeToByteArray()))
        assertInvalid(BackupReadResult.Reason.NOT_A_BACKUP, read(file("""{"format":"arc-backup","formatVersion":1,"appVersionCode":$INSTALLED}""")))
        assertInvalid(BackupReadResult.Reason.NOT_A_BACKUP, read(gzip("no newline at all")))
        assertInvalid(BackupReadResult.Reason.NOT_A_BACKUP, read(ByteArray(0)))
    }

    @Test
    fun `a header line over the header cap is not a backup`() {
        val pad = "x".repeat(BackupLimits.HEADER_MAX_BYTES)
        val line = """{"format":"$BACKUP_FORMAT_ID","formatVersion":1,"appVersionCode":$INSTALLED,"pad":"$pad"}"""
        assertInvalid(BackupReadResult.Reason.NOT_A_BACKUP, read(file(line)), "header size")
    }

    @Test
    fun `a pre-restore copy reads only when the caller vouches for the copies folder`() {
        val copy = header(kind = BackupKind.PRE_RESTORE_COPY)
        val file = bytes(header = copy, body = BackupBody(BackupFixtures.entries(), copySecrets = BackupFixtures.secretEntries()))
        assertInvalid(BackupReadResult.Reason.DAMAGED, read(file), "pre-restore copy from outside the copies folder")
        assertEquals(BackupFixtures.secretEntries(), (read(file, trustedCopy = true) as BackupReadResult.Ok).body.copySecrets)
    }

    @Test
    fun `deep nesting is refused before decoding`() {
        val deepUnknown = """{"junk":${"[".repeat(200_000)}${"]".repeat(200_000)},""" + sections.first().removePrefix("{")
        assertInvalid(BackupReadResult.Reason.DAMAGED, read(file(headerLine, deepUnknown)), "nesting depth")
        // A value whose "type" is not first makes kotlinx buffer it as a JsonElement tree; the guard stops that too.
        val deepValue = sections.first().replaceFirst(
            "{\"type\":\"string\",\"value\":\"amber\"}",
            "{\"value\":${"[".repeat(50)}${"]".repeat(50)},\"type\":\"string\"}",
        )
        assertInvalid(BackupReadResult.Reason.DAMAGED, read(file(headerLine, deepValue)), "nesting depth")
        val deepHeader = headerLine.removeSuffix("}") + ",\"x\":" + "[".repeat(20) + "]".repeat(20) + "}"
        assertInvalid(BackupReadResult.Reason.NOT_A_BACKUP, read(file(deepHeader)), "nesting depth")
    }

    @Test
    fun `brackets and quotes inside strings do not count as structure`() {
        val value = "[[[[[[[[[[ ]]]] {{{{ \\\" ,:,:,: \\\\ \n newline"
        val body = BackupBody(listOf(BackupEntry.TextFile("livetv/hidden.json", value)))
        val ok = read(bytes(body = body)) as BackupReadResult.Ok
        assertEquals(value, (ok.body.entries.single() as BackupEntry.TextFile).text)
    }

    @Test
    fun `section caps hold on read`() {
        val flood = prefsLine("ui_scale_prefs", null, (0..BackupLimits.MAX_SECTION_TOKENS / 6).joinToString(",") { "\"k$it\":{\"type\":\"int\",\"value\":1}" })
        assertInvalid(BackupReadResult.Reason.DAMAGED, read(file(headerLine, flood)), "token count")
        val long = prefsLine("ui_scale_prefs", null, "\"k\":{\"type\":\"string\",\"value\":\"${"x".repeat(BackupLimits.MAX_SECTION_BYTES)}\"}")
        assertInvalid(BackupReadResult.Reason.TOO_LARGE, read(file(headerLine, long)), "section size")
        val hugeNumber = sections.first().replaceFirst("\"value\":3", "\"value\":" + "1".repeat(BackupLimits.MAX_LITERAL_BYTES + 1))
        assertInvalid(BackupReadResult.Reason.DAMAGED, read(file(headerLine, hugeNumber)), "literal length")
        assertInvalid(BackupReadResult.Reason.DAMAGED, read(file(headerLine, "")), "empty section")
    }

    @Test
    fun `the memory budget holds on read, whatever the section shapes`() {
        // Ten sections of ~2.5 MiB strings: each within its section, 2 bytes per char over 40 MiB together.
        val value = "x".repeat(2_500_000)
        val lines = (1..10).map { prefsLine("ui_scale_prefs", null, "\"k$it\":{\"type\":\"string\",\"value\":\"$value\"}") }
        assertInvalid(BackupReadResult.Reason.TOO_LARGE, read(file(headerLine, *lines.toTypedArray())), "memory budget")
        // Many small set elements cost their per-element overhead, not only their chars.
        val set = (0..<20_000).joinToString(",") { "\"$it\"" }
        val sets = (1..40).map { prefsLine("ui_scale_prefs", null, "\"s$it\":{\"type\":\"string_set\",\"value\":[$set]}") }
        assertInvalid(BackupReadResult.Reason.TOO_LARGE, read(file(headerLine, *sets.toTypedArray())), "memory budget")
    }

    @Test
    fun `a sealed payload nested past the cap fails the checks`() {
        val deep = "{\"type\":\"file\",\"store\":" + "[".repeat(50) + "]".repeat(50) + "}\n"
        val box = BackupCrypto.seal(deep.encodeToByteArray(), "246810", iterations = 10)
        val e = assertThrows(BackupCheckException::class.java) { BackupCodec.openSecrets(header(sealed = true), box, "246810", retained = 0) }
        assertEquals("sealed payload nesting depth", e.detail)
    }

    @Test
    fun `a file cut inside the data, at a section or inside the gzip trailer is damaged`() {
        val good = bytes()
        assertInvalid(BackupReadResult.Reason.DAMAGED, read(good.copyOf(good.size - 12)))
        // The last 8 bytes are the trailer (CRC-32, size): every body byte is there, only the check is missing.
        assertInvalid(BackupReadResult.Reason.DAMAGED, read(good.copyOf(good.size - 4)))
        val badCrc = good.copyOf().also { it[it.size - 8] = (it[it.size - 8].toInt() xor 1).toByte() }
        assertInvalid(BackupReadResult.Reason.DAMAGED, read(badCrc))
        assertInvalid(BackupReadResult.Reason.DAMAGED, read(gzip(text.removeSuffix("\n"))), "unterminated section")
    }

    @Test
    fun `an extra or second sealed section is damaged`() {
        assertInvalid(BackupReadResult.Reason.DAMAGED, read(gzip("$text{}\n")))
        val box = BackupCodec.sealSecrets(header(sealed = true), BackupFixtures.secretEntries(), "246810", iterations = 10)
        val sealedText = gunzip(bytes(header(sealed = true), BackupBody(BackupFixtures.entries(), sealedSecrets = box)))
        val sealedLine = sealedText.removeSuffix("\n").substringAfterLast('\n')
        assertInvalid(BackupReadResult.Reason.DAMAGED, read(gzip("$sealedText$sealedLine\n")), "two sealed sections")
    }

    @Test
    fun `the compressed cap applies to the raw bytes`() {
        val good = bytes()
        assertTrue(read(good, compressedLimit = good.size.toLong()) is BackupReadResult.Ok)
        assertInvalid(BackupReadResult.Reason.TOO_LARGE, read(good, compressedLimit = good.size - 1L))
    }

    @Test
    fun `a gzip bomb stops at the decompressed cap`() {
        // Valid sections padded with 1 MiB of spaces each: tiny decoded, so only the 32 MiB cap (D39) stops them.
        val padded = " ".repeat(1 shl 20) + sections.first()
        assertInvalid(BackupReadResult.Reason.TOO_LARGE, read(file(headerLine, *Array(33) { padded })), "size cap")
    }

    @Test
    fun `an unknown value type or entry kind fails the whole file`() {
        assertInvalid(BackupReadResult.Reason.DAMAGED, read(gzip(text.replaceFirst("\"type\":\"int\"", "\"type\":\"bytes\""))))
        assertInvalid(BackupReadResult.Reason.DAMAGED, read(gzip(text.replaceFirst("\"type\":\"prefs\"", "\"type\":\"folder\""))))
    }

    @Test
    fun `a body failing a check is damaged`() {
        assertInvalid(BackupReadResult.Reason.DAMAGED, read(file(headerLine, prefsLine("profile_settings", null, ""))), "store not in the catalog")
    }

    @Test
    fun `read never throws, wherever a file is damaged`() {
        val good = bytes(header(sealed = true), BackupBody(BackupFixtures.entries(), sealedSecrets = BackupCodec.sealSecrets(header(sealed = true), BackupFixtures.secretEntries(), "246810", iterations = 10)))
        val plain = gunzip(good).encodeToByteArray()
        val random = Random(101)
        repeat(2_000) {
            val damaged = plain.copyOf()
            repeat(1 + random.nextInt(4)) { damaged[random.nextInt(damaged.size)] = random.nextInt(256).toByte() }
            read(gzip(damaged))
            read(good.copyOf(random.nextInt(good.size)))
        }
    }

    @Test
    fun `sealed secrets round-trip through the file and open only with the PIN`() {
        val box = BackupCodec.sealSecrets(header(sealed = true), BackupFixtures.secretEntries(), "246810", iterations = 100)
        val ok = read(bytes(header = header(sealed = true), body = BackupBody(BackupFixtures.entries(), sealedSecrets = box)))
            as BackupReadResult.Ok
        assertEquals(BackupFixtures.secretEntries(), BackupCodec.openSecrets(ok, "246810"))
        assertThrows(WrongBackupPinException::class.java) { BackupCodec.openSecrets(ok, "246811") }
    }

    @Test
    fun `sealSecrets uses the 600k default`() {
        assertEquals(600_000, BackupCodec.sealSecrets(header(sealed = true), BackupFixtures.secretEntries(), "246810").iterations)
    }

    @Test
    fun `sealing checks entries against the header, and opened secrets pass the checks`() {
        val e1 = assertThrows(BackupCheckException::class.java) { BackupCodec.sealSecrets(header(sealed = true), BackupFixtures.entries(), "246810", 10) }
        assertEquals("plain store among secrets", e1.detail)
        val withExcluded = listOf(BackupEntry.Prefs("tmdb_api_key", values = mapOf("known_release" to BackupValue.Str("x"))))
        val e2 = assertThrows(BackupCheckException::class.java) { BackupCodec.sealSecrets(header(sealed = true), withExcluded, "246810", 10) }
        assertEquals("excluded key", e2.detail)
        // tracking_accounts for profile 1 only: a header without that profile refuses it.
        val noPrimary = header(sealed = true, profiles = listOf(BackupProfile(2, "Kids", false)))
        val e3 = assertThrows(BackupCheckException::class.java) { BackupCodec.sealSecrets(noPrimary, BackupFixtures.secretEntries(), "246810", 10) }
        assertEquals("per-profile store without a backup profile", e3.detail)
        // A crafted box holding a plain store (sealed with the right PIN) still fails the checks.
        val plain = BackupFixtures.entries().joinToString("") { backupJson.encodeToString(BackupEntry.serializer(), it) + "\n" }
        val box = BackupCrypto.seal(plain.encodeToByteArray(), "246810", iterations = 10)
        val e = assertThrows(BackupCheckException::class.java) { BackupCodec.openSecrets(header(sealed = true), box, "246810", retained = 0) }
        assertEquals("plain store among secrets", e.detail)
        val junk = BackupCrypto.seal("not json\n".encodeToByteArray(), "246810", iterations = 10)
        assertThrows(BackupCheckException::class.java) { BackupCodec.openSecrets(header(sealed = true), junk, "246810", retained = 0) }
    }
}
