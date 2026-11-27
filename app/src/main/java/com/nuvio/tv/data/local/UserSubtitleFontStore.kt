package com.nuvio.tv.data.local

import android.content.Context
import android.graphics.Typeface
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A subtitle font the viewer sent from a phone (Spec 132). [id] is the file name up to the original
 * name and is never reused; [family] is the font's own family name (name ID 1), the name libass
 * selects it by; [fileName] is the name it was sent with.
 */
data class UserSubtitleFont(
    val id: String,
    val family: String,
    val fileName: String,
    val sizeBytes: Long,
    val addedAt: Instant,
    val file: File
)

sealed interface UserSubtitleFontAddResult {
    data class Added(val font: UserSubtitleFont) : UserSubtitleFontAddResult
    /** The same bytes are already stored (D6 "already there"); nothing new was written. */
    data class AlreadyThere(val font: UserSubtitleFont) : UserSubtitleFontAddResult
    /** At [UserSubtitleFontStore.CAP] (D3): remove one first. */
    data object Full : UserSubtitleFontAddResult
    /** Over [UserSubtitleFontStore.MAX_BYTES]. */
    data object TooBig : UserSubtitleFontAddResult
    /** Not a .ttf/.otf name, not a well-formed font, no family name, or Android cannot load it. */
    data object NotAFont : UserSubtitleFontAddResult
    data object Failed : UserSubtitleFontAddResult
}

/**
 * The fonts on this TV (Spec 132 D1-D4, D6): every profile picks from them, nothing is synced or
 * backed up, sign-out keeps them (they hold no account data). The folder is the index, listed
 * oldest first (a sent font joins the end): `font-<addedAtMillis>-<uuid>-<original name>`. An add
 * writes `<name>.tmp`, checks that Android loads it ([loads]), then renames; a crash leaves only a
 * `.tmp` that the next load deletes. Files are untrusted input: [readSubtitleFontFamily] checks the
 * table directory and reads only the name table.
 */
@Singleton
class UserSubtitleFontStore internal constructor(
    private val dir: File,
    private val clock: Clock,
    ioDispatcher: CoroutineDispatcher,
    private val loads: (File) -> Boolean
) {
    @Inject
    constructor(@ApplicationContext context: Context) :
        this(File(context.filesDir, DIR_NAME), Clock.System, Dispatchers.IO, ::androidLoadsFont)

    private val io = ioDispatcher
    private val mutex = Mutex()
    private val _fonts = MutableStateFlow<List<UserSubtitleFont>?>(null)

    /** Oldest first; null until the folder has been read. */
    val fonts: StateFlow<List<UserSubtitleFont>?> = _fonts.asStateFlow()

    init {
        CoroutineScope(SupervisorJob() + io).launch { load() }
    }

    /** Reads the folder (sweeping leftover `.tmp` files) and publishes the list; unreadable = none. */
    internal suspend fun load() = mutex.withLock {
        withContext(io) {
            _fonts.value = try {
                dir.listFiles { file -> file.name.endsWith(TMP_SUFFIX) }?.forEach { it.delete() }
                scan()
            } catch (e: SecurityException) {
                Log.w(TAG, "Subtitle fonts not readable: ${e.javaClass.simpleName}")
                emptyList()
            }
        }
    }

    /** Checks and stores [bytes] sent as [fileName] (a bare name; any path is dropped). */
    suspend fun add(fileName: String, bytes: ByteArray): UserSubtitleFontAddResult = mutex.withLock {
        withContext(io) {
            val name = cleanFileName(fileName) ?: return@withContext UserSubtitleFontAddResult.NotAFont
            if (bytes.size > MAX_BYTES) return@withContext UserSubtitleFontAddResult.TooBig
            if (!isWellFormedFont(bytes)) return@withContext UserSubtitleFontAddResult.NotAFont
            readSubtitleFontFamily(bytes.size.toLong(), bytes::readAt)
                ?: return@withContext UserSubtitleFontAddResult.NotAFont
            val current = _fonts.value ?: scan()
            current.firstOrNull { it.sizeBytes == bytes.size.toLong() && sameBytes(it.file, bytes) }
                ?.let { return@withContext UserSubtitleFontAddResult.AlreadyThere(it) }
            if (current.size >= CAP) return@withContext UserSubtitleFontAddResult.Full
            val addedAt = Instant.fromEpochMilliseconds(clock.now().toEpochMilliseconds())
            val id = "$PREFIX-${addedAt.toEpochMilliseconds()}-${UUID.randomUUID()}"
            val file = File(dir, "$id-$name")
            val tmp = File(dir, "$id$TMP_SUFFIX")
            try {
                if (!dir.isDirectory && !dir.mkdirs()) throw IOException("no folder")
                // Synced before the rename, so a power cut cannot leave a renamed but empty font.
                FileOutputStream(tmp).use { out ->
                    out.write(bytes)
                    out.fd.sync()
                }
                if (!loads(tmp)) {
                    tmp.delete()
                    return@withContext UserSubtitleFontAddResult.NotAFont
                }
                if (!tmp.renameTo(file)) throw IOException("rename failed")
            } catch (e: IOException) {
                tmp.delete()
                Log.w(TAG, "Subtitle font not added: ${e.javaClass.simpleName}")
                return@withContext UserSubtitleFontAddResult.Failed
            }
            val font = parse(file) ?: run {
                file.delete()
                return@withContext UserSubtitleFontAddResult.Failed
            }
            _fonts.value = (current + font).sortedWith(LIST_ORDER)
            UserSubtitleFontAddResult.Added(font)
        }
    }

    /** Deletes font [id] (only an id the store listed); false when it is unknown or the file stays. */
    suspend fun remove(id: String): Boolean = mutex.withLock {
        withContext(io) {
            val current = _fonts.value ?: scan()
            val font = current.firstOrNull { it.id == id } ?: return@withContext false
            if (font.file.exists() && !font.file.delete()) return@withContext false
            _fonts.value = current - font
            true
        }
    }

    private fun scan(): List<UserSubtitleFont> =
        dir.listFiles().orEmpty()
            .mapNotNull(::parse)
            .sortedWith(LIST_ORDER)

    /** A stored font, or null for anything else in the folder (other names, unreadable fonts). */
    private fun parse(file: File): UserSubtitleFont? {
        val match = NAME.matchEntire(file.name) ?: return null
        val (millis, originalName) = match.destructured
        val addedAt = millis.toLongOrNull() ?: return null
        if (!file.isFile) return null
        val family = try {
            RandomAccessFile(file, "r").use { raf ->
                readSubtitleFontFamily(raf.length()) { offset, length ->
                    ByteArray(length).also { raf.seek(offset); raf.readFully(it) }
                }
            }
        } catch (e: IOException) {
            null
        } ?: return null
        val id = file.name.removeSuffix("-$originalName")
        return UserSubtitleFont(id, family, originalName, file.length(), Instant.fromEpochMilliseconds(addedAt), file)
    }

    private fun sameBytes(file: File, bytes: ByteArray): Boolean = try {
        file.readBytes().contentEquals(bytes)
    } catch (e: IOException) {
        false
    }

    companion object {
        private const val TAG = "UserSubtitleFontStore"
        /** Under `filesDir`; not `subtitle-fonts/`, Spec 135's cache of the bundled face. */
        const val DIR_NAME = "user-subtitle-fonts"
        /** D3. */
        const val CAP = 5
        /** P0: Latin and Vietnamese faces run 0.1-1 MB; full CJK fonts (16 MB+) are refused. */
        const val MAX_BYTES = 10 * 1024 * 1024
        /** The original name is kept to this many UTF-8 bytes, so the stored name stays under 255. */
        internal const val MAX_NAME_BYTES = 120
        /** Every stored file name starts with "$PREFIX-"; the mpv copies keep the name (Spec 132 S5). */
        internal const val PREFIX = "font"
        private const val TMP_SUFFIX = ".tmp"
        // `font-<millis>-<uuid>-<original name ending .ttf/.otf>`; anything else in the folder is ignored.
        private val ID = Regex(
            """font-\d{1,19}-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""",
            RegexOption.IGNORE_CASE
        )

        /**
         * The stored file of font [id] under [filesDir], for the players (Spec 132 S5), without the
         * store's list: null when the id is not a store id or its file is gone. A cheap folder listing.
         */
        fun fileOf(filesDir: File, id: String): File? {
            if (!ID.matches(id)) return null
            return File(filesDir, DIR_NAME).listFiles { file ->
                file.isFile && file.name.startsWith("$id-") && NAME.matches(file.name)
            }?.firstOrNull()
        }

        private val NAME = Regex(
            """font-(\d{1,19})-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-(.+\.(?:ttf|otf))""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        /** Oldest first; the id breaks a same-millisecond tie the same way in memory and after a reload. */
        private val LIST_ORDER = compareBy<UserSubtitleFont>({ it.addedAt }, { it.id })
    }
}

/**
 * The name to keep: the last path segment of [fileName] without control characters, line or
 * paragraph separators and unpaired surrogates, cut by whole code points to
 * [UserSubtitleFontStore.MAX_NAME_BYTES] UTF-8 bytes with its extension kept; null unless it ends
 * `.ttf` or `.otf`.
 */
internal fun cleanFileName(fileName: String): String? {
    val bare = keptCodePoints(fileName.substringAfterLast('/').substringAfterLast('\\')).trim()
    val extension = bare.substringAfterLast('.', "").lowercase()
    if (extension != "ttf" && extension != "otf") return null
    val stem = bare.dropLast(extension.length + 1).trim()
    // One pass by code point (never splitting a pair), keeping what fits beside the extension.
    val budget = UserSubtitleFontStore.MAX_NAME_BYTES - extension.length - 1
    var used = 0
    val kept = StringBuilder()
    for (codePoint in stem.codePoints()) {
        val bytes = utf8Length(codePoint)
        if (used + bytes > budget) break
        used += bytes
        kept.appendCodePoint(codePoint)
    }
    return if (kept.isBlank()) null else "$kept.$extension"
}

/** Drops control characters, line and paragraph separators, and unpaired surrogates. */
private fun keptCodePoints(text: String): String = buildString {
    for (codePoint in text.codePoints()) {
        val type = Character.getType(codePoint)
        if (type == Character.CONTROL.toInt() || type == Character.LINE_SEPARATOR.toInt() ||
            type == Character.PARAGRAPH_SEPARATOR.toInt() || type == Character.SURROGATE.toInt()
        ) continue
        appendCodePoint(codePoint)
    }
}

private fun utf8Length(codePoint: Int): Int = when {
    codePoint < 0x80 -> 1
    codePoint < 0x800 -> 2
    codePoint < 0x10000 -> 3
    else -> 4
}

/**
 * An sfnt the app can hand to Android and libass: TrueType (`00010000`) or CFF (`OTTO`) outlines,
 * a table directory inside the file, every table inside the file, and `cmap` + `name` present.
 * Collections (`ttcf`) and old Mac `true` fonts are refused.
 */
internal fun isWellFormedFont(bytes: ByteArray): Boolean {
    if (bytes.size < SFNT_HEADER) return false
    val version = bytes.uint32(0)
    if (version != TRUETYPE_VERSION && version != CFF_VERSION) return false
    val tables = bytes.uint16(4)
    if (tables == 0 || SFNT_HEADER + tables.toLong() * TABLE_RECORD > bytes.size) return false
    val tags = HashSet<Int>()
    for (index in 0..<tables) {
        val record = SFNT_HEADER + index * TABLE_RECORD
        val offset = bytes.uint32(record + 8)
        val length = bytes.uint32(record + 12)
        if (offset > bytes.size || length > bytes.size - offset) return false
        tags += bytes.uint32(record).toInt()
    }
    return CMAP in tags && NAME_TAG in tags
}

/**
 * The family name (name ID 1) as libass reads it: Windows platform first (US English, then any),
 * then Unicode, then Macintosh Roman; control characters dropped, trimmed, at most
 * [MAX_FAMILY_CHARS]. [readAt] returns exactly the bytes asked for; only the header, the table
 * directory and the name table (at most [MAX_NAME_TABLE] bytes) are read. Null when the font has
 * no usable family name or its structure is out of bounds.
 */
internal fun readSubtitleFontFamily(size: Long, readAt: (offset: Long, length: Int) -> ByteArray): String? = try {
    familyOrNull(size, readAt)
} catch (e: IndexOutOfBoundsException) {
    null
} catch (e: IOException) {
    null
}

private fun familyOrNull(size: Long, readAt: (offset: Long, length: Int) -> ByteArray): String? {
    if (size < SFNT_HEADER) return null
    val tables = readAt(0, SFNT_HEADER).uint16(4)
    if (tables == 0 || SFNT_HEADER + tables.toLong() * TABLE_RECORD > size) return null
    val directory = readAt(SFNT_HEADER.toLong(), tables * TABLE_RECORD)
    val record = (0..<tables).map { it * TABLE_RECORD }
        .firstOrNull { directory.uint32(it).toInt() == NAME_TAG } ?: return null
    val tableOffset = directory.uint32(record + 8)
    val tableLength = directory.uint32(record + 12)
    if (tableLength < NAME_HEADER || tableLength > MAX_NAME_TABLE || tableOffset > size - tableLength) return null
    val table = readAt(tableOffset, tableLength.toInt())
    val count = table.uint16(2)
    val storage = table.uint16(4)
    if (NAME_HEADER + count.toLong() * NAME_RECORD > table.size) return null
    data class Entry(val platform: Int, val language: Int, val text: String)
    val entries = (0..<count).mapNotNull { index ->
        val at = NAME_HEADER + index * NAME_RECORD
        if (table.uint16(at + 6) != FAMILY_NAME_ID) return@mapNotNull null
        val start = storage + table.uint16(at + 10)
        val length = table.uint16(at + 8)
        if (start + length > table.size) return@mapNotNull null
        val raw = table.copyOfRange(start, start + length)
        val platform = table.uint16(at)
        val text = when (platform) {
            PLATFORM_UNICODE, PLATFORM_WINDOWS -> String(raw, Charsets.UTF_16BE)
            PLATFORM_MAC -> String(raw, Charsets.ISO_8859_1)
            else -> return@mapNotNull null
        }.filterNot { it.isISOControl() }.trim().take(MAX_FAMILY_CHARS).trim()
        text.takeIf { it.isNotEmpty() }?.let { Entry(platform, table.uint16(at + 4), it) }
    }
    return (entries.firstOrNull { it.platform == PLATFORM_WINDOWS && it.language == LANGUAGE_US_ENGLISH }
        ?: entries.firstOrNull { it.platform == PLATFORM_WINDOWS }
        ?: entries.firstOrNull { it.platform == PLATFORM_UNICODE }
        ?: entries.firstOrNull())?.text
}

/**
 * The font in [file] as Android draws it, or null when it can't: createFromFile returns
 * Typeface.DEFAULT, not an error, for a file it cannot read (and throws for a missing one).
 */
internal fun loadUserSubtitleTypeface(file: File): Typeface? =
    try {
        Typeface.createFromFile(file).takeUnless { it == Typeface.DEFAULT }
    } catch (e: RuntimeException) {
        null
    }

private fun androidLoadsFont(file: File): Boolean = loadUserSubtitleTypeface(file) != null

private fun ByteArray.readAt(offset: Long, length: Int): ByteArray {
    if (offset < 0 || length < 0 || offset > size - length) throw IndexOutOfBoundsException()
    return copyOfRange(offset.toInt(), offset.toInt() + length)
}

private fun ByteArray.uint16(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)

private fun ByteArray.uint32(offset: Int): Long =
    (0..3).fold(0L) { value, i -> (value shl 8) or (this[offset + i].toLong() and 0xFF) }

private const val SFNT_HEADER = 12
private const val TABLE_RECORD = 16
private const val NAME_HEADER = 6
private const val NAME_RECORD = 12
private const val MAX_NAME_TABLE = 64L * 1024
private const val MAX_FAMILY_CHARS = 128
private const val TRUETYPE_VERSION = 0x00010000L
private const val CFF_VERSION = 0x4F54544FL // "OTTO"
private const val CMAP = 0x636D6170 // "cmap"
private const val NAME_TAG = 0x6E616D65 // "name"
private const val FAMILY_NAME_ID = 1
private const val PLATFORM_UNICODE = 0
private const val PLATFORM_MAC = 1
private const val PLATFORM_WINDOWS = 3
private const val LANGUAGE_US_ENGLISH = 0x409
