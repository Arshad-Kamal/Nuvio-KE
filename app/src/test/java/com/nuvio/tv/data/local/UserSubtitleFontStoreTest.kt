package com.nuvio.tv.data.local

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Spec 132 S1: the user font folder is the index (oldest first, cap 5, checks, dedupe, remove). */
class UserSubtitleFontStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var nowMillis = 1_000L
    private val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochSeconds(0, (nowMillis++) * 1_000_000 + 123_456)
    }
    private var androidLoads = true

    private fun dir(): File = File(tmp.root, UserSubtitleFontStore.DIR_NAME)

    // Unconfined: the init load and every call run inline, so state is settled on return.
    private fun store(): UserSubtitleFontStore =
        UserSubtitleFontStore(dir(), clock, Dispatchers.Unconfined) { androidLoads }

    private suspend fun UserSubtitleFontStore.addOk(name: String, bytes: ByteArray): UserSubtitleFont =
        (add(name, bytes) as UserSubtitleFontAddResult.Added).font

    @Test
    fun `adds list oldest first with their family and survive a reload`() = runTest {
        val store = store()
        assertEquals(emptyList<UserSubtitleFont>(), store.fonts.value)
        val first = store.addOk("Montserrat-Medium.otf", font("Montserrat Medium", version = CFF))
        val second = store.addOk("NotoSerif-Regular.ttf", font("Noto Serif"))
        assertEquals(listOf(first, second), store.fonts.value)
        assertEquals("Montserrat Medium", first.family)
        assertEquals("Montserrat-Medium.otf", first.fileName)
        assertTrue(first.id.startsWith("font-1000-"))
        assertEquals("${first.id}-Montserrat-Medium.otf", first.file.name)
        assertEquals(first.file.length(), first.sizeBytes)
        assertEquals(Instant.fromEpochMilliseconds(1_000), first.addedAt)
        assertEquals(listOf(first, second), store().fonts.value)
    }

    @Test
    fun `the list is null until the folder has been read`() = runTest {
        val store = UserSubtitleFontStore(dir(), clock, StandardTestDispatcher(testScheduler)) { true }
        assertNull(store.fonts.value)
        advanceUntilIdle()
        assertEquals(emptyList<UserSubtitleFont>(), store.fonts.value)
    }

    @Test
    fun `files that are not fonts are refused and nothing is written`() = runTest {
        val store = store()
        val good = font("Good")
        assertEquals(UserSubtitleFontAddResult.NotAFont, store.add("subs.srt", good))
        assertEquals(UserSubtitleFontAddResult.NotAFont, store.add(".ttf", good))
        assertEquals(UserSubtitleFontAddResult.NotAFont, store.add("a.ttf", "1\n00:00:01,000 --> x".toByteArray()))
        assertEquals(UserSubtitleFontAddResult.NotAFont, store.add("a.ttf", font("Good", version = 0x74746366))) // ttcf
        assertEquals(UserSubtitleFontAddResult.NotAFont, store.add("a.ttf", font("Good", withCmap = false)))
        assertEquals(UserSubtitleFontAddResult.NotAFont, store.add("a.ttf", font(null)))
        assertEquals(UserSubtitleFontAddResult.NotAFont, store.add("a.ttf", font("   ")))
        assertEquals(UserSubtitleFontAddResult.NotAFont, store.add("a.ttf", good.copyOf(good.size - 3)))
        androidLoads = false
        assertEquals(UserSubtitleFontAddResult.NotAFont, store.add("a.ttf", good))
        assertEquals(emptyList<UserSubtitleFont>(), store.fonts.value)
        assertEquals(emptyList<String>(), dir().list().orEmpty().toList())
    }

    @Test
    fun `a table pointing past the end is refused`() = runTest {
        val bytes = font("Good")
        // First directory record's length field (header 12 + tag 4 + checksum 4 + offset 4).
        bytes[12 + 12] = 0x7F
        assertEquals(UserSubtitleFontAddResult.NotAFont, store().add("a.ttf", bytes))
    }

    @Test
    fun `over the size cap is too big`() = runTest {
        val bytes = font("Big").copyOf(UserSubtitleFontStore.MAX_BYTES + 1)
        assertEquals(UserSubtitleFontAddResult.TooBig, store().add("big.ttf", bytes))
    }

    @Test
    fun `the same bytes again are already there, even when full`() = runTest {
        val store = store()
        val fonts = (1..UserSubtitleFontStore.CAP).map { store.addOk("f$it.ttf", font("Font $it")) }
        assertEquals(UserSubtitleFontAddResult.AlreadyThere(fonts[2]), store.add("copy.ttf", font("Font 3")))
        assertEquals(UserSubtitleFontAddResult.Full, store.add("six.ttf", font("Font 6")))
        assertEquals(fonts, store.fonts.value)
    }

    @Test
    fun `remove deletes the file and frees a place`() = runTest {
        val store = store()
        val fonts = (1..UserSubtitleFontStore.CAP).map { store.addOk("f$it.ttf", font("Font $it")) }
        assertTrue(store.remove(fonts[0].id))
        assertFalse(fonts[0].file.exists())
        assertFalse(store.remove(fonts[0].id))
        assertFalse(store.remove("font-1-../../x"))
        assertTrue(store.add("six.ttf", font("Font 6")) is UserSubtitleFontAddResult.Added)
        assertEquals(UserSubtitleFontStore.CAP, store().fonts.value!!.size)
    }

    @Test
    fun `a listed font whose file was deleted can still be removed, and drops out on reload`() = runTest {
        val store = store()
        val font = store.addOk("Gone.ttf", font("Gone"))
        assertTrue(font.file.delete())
        assertEquals(emptyList<UserSubtitleFont>(), store().fonts.value)
        // Dedupe cannot read the missing file, so the same bytes are added again.
        assertTrue(store.add("Gone.ttf", font("Gone")) is UserSubtitleFontAddResult.Added)
        assertTrue(store.remove(font.id))
        assertEquals(1, store.fonts.value!!.size)
    }

    @Test
    fun `a folder that cannot be made fails the add and leaves nothing`() = runTest {
        dir().writeText("a file where the folder should be")
        val store = store()
        assertEquals(emptyList<UserSubtitleFont>(), store.fonts.value)
        assertEquals(UserSubtitleFontAddResult.Failed, store.add("a.ttf", font("Good")))
        assertEquals(emptyList<UserSubtitleFont>(), store.fonts.value)
    }

    @Test
    fun `hostile headers are refused without a crash`() = runTest {
        val store = store()
        val good = font("Good")
        // numTables 0xFFFF in a small file: the directory cannot fit.
        val manyTables = good.copyOf().also { it[4] = 0xFF.toByte(); it[5] = 0xFF.toByte() }
        assertEquals(UserSubtitleFontAddResult.NotAFont, store.add("a.ttf", manyTables))
        val nameAt = nameTableOffset(good)
        // Name record count 0xFFFF: the records run past the table.
        val manyNames = good.copyOf().also { it[nameAt + 2] = 0xFF.toByte(); it[nameAt + 3] = 0xFF.toByte() }
        assertEquals(UserSubtitleFontAddResult.NotAFont, store.add("a.ttf", manyNames))
        // String storage offset past the table: the family text is out of bounds.
        val farStorage = good.copyOf().also { it[nameAt + 4] = 0x7F; it[nameAt + 5] = 0x00 }
        assertEquals(UserSubtitleFontAddResult.NotAFont, store.add("a.ttf", farStorage))
        assertEquals(emptyList<UserSubtitleFont>(), store.fonts.value)
    }

    @Test
    fun `the family read touches only the header, the directory and the name table`() {
        val bytes = font("Good")
        val nameAt = nameTableOffset(bytes)
        val reads = mutableListOf<LongRange>()
        val family = readSubtitleFontFamily(bytes.size.toLong()) { offset, length ->
            reads += offset..<(offset + length)
            bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
        }
        assertEquals("Good", family)
        val directoryEnd = 12L + 16 * 2
        reads.forEach { range ->
            assertTrue("$range", range.last < directoryEnd || range.first >= nameAt)
        }
    }

    @Test
    fun `separators in a name are dropped, so the font survives a reload`() = runTest {
        val store = store()
        val font = store.addOk("Fo\u2028nt\u2029.ttf", font("Sep"))
        assertEquals("Font.ttf", font.fileName)
        assertEquals(listOf(font), store().fonts.value)
        assertEquals("ab.ttf", cleanFileName("a\uD800b.ttf"))
        assertEquals("ab.ttf", cleanFileName("a\uDC00b.ttf"))
    }

    @Test
    fun `load sweeps leftover tmp files and ignores anything else`() = runTest {
        dir().mkdirs()
        val leftover = File(dir(), "font-5-00000000-0000-0000-0000-000000000000.tmp").apply { writeBytes(font("X")) }
        File(dir(), "notes.txt").writeText("x")
        // A stored name whose file is not a readable font is not listed.
        File(dir(), "font-6-00000000-0000-0000-0000-000000000001-bad.ttf").writeBytes(byteArrayOf(1, 2))
        val kept = File(dir(), "font-7-00000000-0000-0000-0000-000000000002-Kept.TTF").apply { writeBytes(font("Kept")) }
        val listed = store().fonts.value!!
        assertFalse(leftover.exists())
        assertEquals(listOf(kept), listed.map { it.file })
        assertEquals("Kept.TTF", listed.single().fileName)
        assertEquals("font-7-00000000-0000-0000-0000-000000000002", listed.single().id)
    }

    @Test
    fun `family prefers Windows US English, then Windows, then Unicode, then Mac`() {
        fun family(vararg names: NameEntry): String? {
            val bytes = font(names.toList())
            return readSubtitleFontFamily(bytes.size.toLong()) { offset, length ->
                bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
            }
        }
        assertEquals("Win US", family(NameEntry(1, 0, "Mac"), NameEntry(3, 0x40C, "Win FR"), NameEntry(3, 0x409, "Win US")))
        assertEquals("Win FR", family(NameEntry(1, 0, "Mac"), NameEntry(0, 0, "Uni"), NameEntry(3, 0x40C, "Win FR")))
        assertEquals("Uni", family(NameEntry(1, 0, "Mac"), NameEntry(0, 0, "Uni")))
        assertEquals("Mac", family(NameEntry(1, 0, "Mac")))
        assertEquals("Tab", family(NameEntry(3, 0x409, " Ta\u0007b ")))
        assertEquals(128, family(NameEntry(3, 0x409, "x".repeat(300)))!!.length)
    }

    @Test
    fun `a name table claiming more than the file holds gives no family`() {
        val bytes = font("Good")
        // The name table is the second directory record (cmap sorts first): its length field.
        val lengthAt = 12 + 16 + 12
        bytes[lengthAt] = 0x00; bytes[lengthAt + 1] = 0x01; bytes[lengthAt + 2] = 0x00; bytes[lengthAt + 3] = 0x00
        assertNull(readSubtitleFontFamily(bytes.size.toLong()) { offset, length ->
            if (offset + length > bytes.size) throw IndexOutOfBoundsException()
            bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
        })
    }

    @Test
    fun `file names keep the last segment and extension, cut to the byte cap`() {
        assertEquals("Font.ttf", cleanFileName("/sdcard/Download/Font.ttf"))
        assertEquals("Font.ttf", cleanFileName("C:\\fonts\\Font.ttf"))
        assertEquals("Font.otf", cleanFileName(" Fo\u0000nt.OTF "))
        assertNull(cleanFileName("font.woff2"))
        assertNull(cleanFileName("noextension"))
        val long = cleanFileName("${"ặ".repeat(100)}.ttf")!!
        assertTrue(long.endsWith(".ttf"))
        assertTrue(long.toByteArray(Charsets.UTF_8).size <= UserSubtitleFontStore.MAX_NAME_BYTES)
        assertEquals("${"a".repeat(116)}.ttf", cleanFileName("${"a".repeat(300)}.ttf"))
        val emoji = cleanFileName("${"😀".repeat(40)}.ttf")!!
        assertFalse(emoji.dropLast(4).last().isHighSurrogate())
    }

    /** The name table's offset: the second directory record (cmap sorts first), its offset field. */
    private fun nameTableOffset(bytes: ByteArray): Int =
        ((bytes[36].toInt() and 0xFF) shl 24) or ((bytes[37].toInt() and 0xFF) shl 16) or
            ((bytes[38].toInt() and 0xFF) shl 8) or (bytes[39].toInt() and 0xFF)

    private data class NameEntry(val platform: Int, val language: Int, val text: String)

    private fun font(family: String?, version: Int = TRUETYPE, withCmap: Boolean = true): ByteArray =
        font(listOfNotNull(family?.let { NameEntry(3, 0x409, it) }), version, withCmap)

    /** A minimal sfnt: header, directory, a 4-byte `cmap` stand-in and a `name` table with ID 1 entries. */
    private fun font(names: List<NameEntry>, version: Int = TRUETYPE, withCmap: Boolean = true): ByteArray {
        val name = ByteArrayOutputStream().also { out ->
            val data = DataOutputStream(out)
            val strings = names.map {
                if (it.platform == 1) it.text.toByteArray(Charsets.ISO_8859_1) else it.text.toByteArray(Charsets.UTF_16BE)
            }
            data.writeShort(0)
            data.writeShort(names.size)
            data.writeShort(6 + 12 * names.size)
            var offset = 0
            names.forEachIndexed { i, entry ->
                data.writeShort(entry.platform)
                data.writeShort(if (entry.platform == 3) 1 else 0)
                data.writeShort(entry.language)
                data.writeShort(1)
                data.writeShort(strings[i].size)
                data.writeShort(offset)
                offset += strings[i].size
            }
            strings.forEach(data::write)
        }.toByteArray()
        val tables = buildList {
            if (withCmap) add("cmap" to byteArrayOf(0, 0, 0, 0))
            add("name" to name)
        }
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.writeInt(version)
        data.writeShort(tables.size)
        data.writeShort(0); data.writeShort(0); data.writeShort(0)
        var offset = 12 + 16 * tables.size
        tables.forEach { (tag, bytes) ->
            data.writeBytes(tag)
            data.writeInt(0)
            data.writeInt(offset)
            data.writeInt(bytes.size)
            offset += bytes.size
        }
        tables.forEach { (_, bytes) -> data.write(bytes) }
        return out.toByteArray()
    }

    private companion object {
        const val TRUETYPE = 0x00010000
        const val CFF = 0x4F54544F
    }
}
