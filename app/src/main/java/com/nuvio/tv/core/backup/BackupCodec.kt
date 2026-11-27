package com.nuvio.tv.core.backup

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** What reading a backup file found (Spec 101 R2, R7). */
sealed interface BackupReadResult {
    data class Ok(val header: BackupHeader, val body: BackupBody) : BackupReadResult

    /** Newer format or newer app versionCode: "Update Nuvio KE on this TV first" (D32, D34). */
    data class NeedsNewerApp(val backupVersionName: String, val backupVersionCode: Int) : BackupReadResult

    /** Below [MIN_BACKUP_VERSION_CODE]: b12's older-file line (D35, D37, D38). */
    data class TooOld(val backupVersionName: String, val backupVersionCode: Int) : BackupReadResult

    data class Invalid(val reason: Reason, val detail: String) : BackupReadResult

    enum class Reason {
        /** Not gzip, not our header, or not our format id. */
        NOT_A_BACKUP,
        /** Over a size cap or the memory budget. */
        TOO_LARGE,
        /** Our header, but a section is cut off, malformed or fails a check. */
        DAMAGED,
    }
}

/**
 * A size cap or the memory budget was crossed; an IOException so it unwinds through the gzip reader.
 * [wholeFile] marks a limit on the file as a whole (size cap, memory budget) rather than on one section.
 */
class BackupTooLargeException(val detail: String, val wholeFile: Boolean = false) : IOException(detail)

/** A section's JSON is empty, cut off, or nested or tokenised past [BackupLimits]. */
class BackupShapeException(val detail: String) : IOException(detail)

/**
 * Writes and reads the backup file layout described in BackupFormat.kt. Reading loads one section at a
 * time under [SectionLimits] (D40); writing runs the same limits first, so every file written reads back.
 */
object BackupCodec {

    /**
     * Writes [header] and [body] gzipped to [out], which stays open. Everything is checked first (the
     * read-side checks with the writer rules, then every section through [SectionLimits]), so a failure
     * throws [BackupCheckException] before a byte is written; a limit crossed is marked
     * [BackupCheckException.tooLarge], naming the section's store when that one section is over its own
     * limit (D41). An IOException from [out] propagates.
     */
    fun write(out: OutputStream, header: BackupHeader, body: BackupBody) {
        BackupChecks.checkHeader(header, trustedCopy = true)
        BackupChecks.checkBody(header, body, writing = true)
        val headerBytes = encode(BackupHeader.serializer(), header)
        val lines = sections(body)
        limitsChecked {
            if (headerBytes.size >= BackupLimits.HEADER_MAX_BYTES) throw BackupTooLargeException("header size")
            JsonShape(BackupLimits.MAX_SECTION_TOKENS).feed(headerBytes, 0, headerBytes.size)
            val limits = SectionLimits(headerBytes.size + 1L)
            // This pass checks; the next one writes. Encoding twice keeps a refusal from leaving a partial file.
            for (line in lines) {
                val bytes = encode(BackupLine.serializer(), line)
                val entry = when (line) {
                    is BackupLine.Entry -> line.entry
                    is BackupLine.CopySecret -> line.entry
                    is BackupLine.Sealed -> null
                }
                admitSection(limits, bytes, SectionCost.of(line), entry)
            }
        }
        GZIPOutputStream(KeepOpenOutputStream(out), BUFFER_BYTES).use { gzip ->
            gzip.write(headerBytes)
            gzip.write('\n'.code)
            for (line in lines) {
                gzip.write(encode(BackupLine.serializer(), line))
                gzip.write('\n'.code)
            }
        }
    }

    /**
     * Reads a backup from [input] (not closed here). [compressedLimit] caps the raw bytes read (the
     * phone upload passes [BackupLimits.PHONE_COMPRESSED_MAX_BYTES]); the decompressed size, every section
     * and the memory budget are always capped. [trustedCopy] is true only for a file read from the app's
     * own pre-restore copies folder (R7). Never throws for a bad file: every failure is a [BackupReadResult].
     */
    fun read(
        input: InputStream,
        installedVersionCode: Int,
        compressedLimit: Long? = null,
        trustedCopy: Boolean = false,
    ): BackupReadResult {
        val raw = KeepOpenInputStream(if (compressedLimit != null) CappedInputStream(input, compressedLimit) else input)
        var headerRead = false
        return try {
            GZIPInputStream(raw, BUFFER_BYTES).use { gzip ->
                val lines = LineReader(CappedInputStream(gzip, BackupLimits.DECOMPRESSED_MAX_BYTES))
                val (headerBuffer, headerSize) = lines.next(BackupLimits.HEADER_MAX_BYTES - 1)
                    ?: return invalid(BackupReadResult.Reason.NOT_A_BACKUP, "no header line")
                JsonShape(BackupLimits.MAX_SECTION_TOKENS).feed(headerBuffer, 0, headerSize)
                val headerText = headerBuffer.decodeToString(0, headerSize)
                val versions = backupJson.decodeFromString(HeaderVersions.serializer(), headerText)
                if (versions.format != BACKUP_FORMAT_ID) return invalid(BackupReadResult.Reason.NOT_A_BACKUP, "format id")
                versionVerdict(versions, installedVersionCode)?.let { return it }
                headerRead = true
                val header = backupJson.decodeFromString(BackupHeader.serializer(), headerText)
                BackupChecks.checkHeader(header, trustedCopy)
                val body = readBody(lines, SectionLimits(headerSize + 1L))
                BackupChecks.checkBody(header, body)
                BackupReadResult.Ok(header, body)
            }
        } catch (e: BackupTooLargeException) {
            // An overlong first line is no header; every other cap (the upload's included) is a size refusal.
            invalid(if (e.detail == "header size") BackupReadResult.Reason.NOT_A_BACKUP else BackupReadResult.Reason.TOO_LARGE, e.detail)
        } catch (e: BackupShapeException) {
            invalid(failureReason(headerRead), e.detail)
        } catch (e: BackupCheckException) {
            invalid(BackupReadResult.Reason.DAMAGED, e.detail)
        } catch (e: IOException) {
            invalid(failureReason(headerRead), "read failed: ${e.javaClass.simpleName}")
        } catch (e: SerializationException) {
            invalid(failureReason(headerRead), "shape: ${e.javaClass.simpleName}")
        } catch (e: IllegalArgumentException) {
            invalid(failureReason(headerRead), "validation: ${e.javaClass.simpleName}")
        }
    }

    /**
     * The header of a backup this app wrote, read from the first line only (the phone page's list, the
     * restore list); null for anything that is not one. [trustedCopy] as for [read]. Not a check of the
     * body or the versions: a restore reads the whole file ([read]).
     */
    fun readHeader(input: InputStream, trustedCopy: Boolean = false): BackupHeader? =
        try {
            GZIPInputStream(KeepOpenInputStream(input), BUFFER_BYTES).use { gzip ->
                val (bytes, n) = LineReader(gzip).next(BackupLimits.HEADER_MAX_BYTES - 1) ?: return null
                JsonShape(BackupLimits.MAX_SECTION_TOKENS).feed(bytes, 0, n)
                val header = backupJson.decodeFromString(BackupHeader.serializer(), bytes.decodeToString(0, n))
                BackupChecks.checkHeader(header, trustedCopy)
                header
            }
        } catch (_: IOException) {
            null
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: BackupCheckException) {
            null
        }

    /**
     * Seals secret [entries] for a backup with [header] by the backup [pin] (D2, R8). The entries pass the
     * same checks as when opened (catalog secret stores only, writer rules) and the section limits; those
     * failures throw [BackupCheckException]. A PIN that is not 6-12 digits or iterations out of range
     * throw IllegalArgumentException (a caller bug: the PIN pad allows neither).
     */
    fun sealSecrets(
        header: BackupHeader,
        entries: List<BackupEntry>,
        pin: String,
        iterations: Int = BackupCrypto.ITERATIONS,
    ): SealedBox {
        BackupChecks.checkSecrets(header, entries, writing = true)
        val plain = ByteArrayOutputStream()
        val limits = SectionLimits(0)
        for (entry in entries) {
            val bytes = encode(BackupEntry.serializer(), entry)
            admitSection(limits, bytes, SectionCost.of(entry), entry)
            plain.write(bytes)
            plain.write('\n'.code)
        }
        // The box travels in one section: refuse a payload too big for it before the slow key derivation.
        val sealedChars = (plain.size() + BackupCrypto.TAG_BYTES + 2L) / 3 * 4
        if (sealedChars > BackupLimits.MAX_SECTION_BYTES - SEALED_LINE_ROOM) throw BackupCheckException("section size", tooLarge = true)
        val box = BackupCrypto.seal(plain.toByteArray(), pin, iterations = iterations)
        // And the box itself passes the reader's box check.
        BackupChecks.checkSealedBox(box)
        return box
    }

    /**
     * The sealed secrets of [read] (empty when it has none), read like the file's sections (one
     * [BackupEntry] per line) and charged to the budget [read]'s body already holds, so a restore holding
     * both stays within one [BackupLimits.MEMORY_BUDGET_BYTES] (the writer charges both to one budget
     * too). Throws [WrongBackupPinException] for a wrong PIN, [BackupCheckException] when the box or its
     * opened payload is malformed, over a limit or fails a check.
     */
    fun openSecrets(read: BackupReadResult.Ok, pin: String): List<BackupEntry> {
        val box = read.body.sealedSecrets ?: return emptyList()
        return openSecrets(read.header, box, pin, retained = SectionCost.of(read.body))
    }

    /** [openSecrets] for a box and header in hand; [retained] is what the caller's budget already holds. */
    internal fun openSecrets(header: BackupHeader, box: SealedBox, pin: String, retained: Long): List<BackupEntry> {
        val plain = BackupCrypto.open(box, pin)
        val entries = ArrayList<BackupEntry>()
        try {
            val lines = LineReader(ByteArrayInputStream(plain))
            val limits = SectionLimits(0, retained)
            while (true) {
                val entry = readSection(lines, limits, BackupEntry.serializer()) ?: break
                limits.charge(SectionCost.of(entry))
                entries += entry
            }
        } catch (e: BackupTooLargeException) {
            throw BackupCheckException("sealed payload ${e.detail}", tooLarge = true)
        } catch (e: BackupShapeException) {
            throw BackupCheckException("sealed payload ${e.detail}")
        } catch (e: SerializationException) {
            throw BackupCheckException("sealed payload shape: ${e.javaClass.simpleName}")
        } catch (e: IllegalArgumentException) {
            throw BackupCheckException("sealed payload: ${e.javaClass.simpleName}")
        }
        BackupChecks.checkSecrets(header, entries)
        return entries
    }

    private fun readBody(lines: LineReader, limits: SectionLimits): BackupBody {
        val entries = ArrayList<BackupEntry>()
        val copySecrets = ArrayList<BackupEntry>()
        var sealed: SealedBox? = null
        while (true) {
            val line = readSection(lines, limits, BackupLine.serializer()) ?: break
            limits.charge(SectionCost.of(line))
            when (line) {
                is BackupLine.Entry -> entries += line.entry
                is BackupLine.CopySecret -> copySecrets += line.entry
                is BackupLine.Sealed -> {
                    if (sealed != null) throw BackupCheckException("two sealed sections")
                    sealed = line.box
                }
            }
        }
        return BackupBody(entries, sealed, copySecrets)
    }

    /** The next section decoded, or null at the end; its caps are checked before it is decoded. */
    private fun <T> readSection(lines: LineReader, limits: SectionLimits, serializer: KSerializer<T>): T? {
        val (bytes, n) = lines.next(BackupLimits.MAX_SECTION_BYTES) ?: return null
        limits.admit(bytes, n)
        return backupJson.decodeFromString(serializer, bytes.decodeToString(0, n))
    }

    /** The body as sections, in file order. */
    internal fun sections(body: BackupBody): List<BackupLine> =
        body.entries.map(BackupLine::Entry) +
            body.copySecrets.map(BackupLine::CopySecret) +
            listOfNotNull(body.sealedSecrets?.let(BackupLine::Sealed))

    /**
     * An unpaired surrogate would become '?' here, but none reaches the writer: every exported string was
     * read from a DataStore proto, SharedPreferences XML or a UTF-8 file, and each stores strings as UTF-8.
     */
    private fun <T> encode(serializer: KSerializer<T>, value: T): ByteArray =
        backupJson.encodeToString(serializer, value).encodeToByteArray()

    /**
     * Runs one section through [limits] on the writer side. A crossed limit throws [BackupCheckException]
     * marked tooLarge, naming [entry] when that one section is over its own limit (not a whole-file limit).
     */
    private fun admitSection(limits: SectionLimits, bytes: ByteArray, cost: Long, entry: BackupEntry?) {
        try {
            limits.admit(bytes, bytes.size)
            limits.charge(cost)
        } catch (e: BackupTooLargeException) {
            throw tooLarge(e.detail, entry.takeUnless { e.wholeFile })
        } catch (e: BackupShapeException) {
            throw tooLarge(e.detail, entry)
        }
    }

    private fun tooLarge(detail: String, entry: BackupEntry?) =
        BackupCheckException(detail, tooLarge = true, store = entry?.store, profile = (entry as? BackupEntry.Prefs)?.profile)

    /** Runs a writer-side limits pass, reporting a crossed limit as the writer's one exception type. */
    private inline fun limitsChecked(block: () -> Unit) {
        try {
            block()
        } catch (e: BackupTooLargeException) {
            throw BackupCheckException(e.detail, tooLarge = true)
        } catch (e: BackupShapeException) {
            throw BackupCheckException(e.detail, tooLarge = true)
        }
    }

    private fun versionVerdict(versions: HeaderVersions, installedVersionCode: Int): BackupReadResult? =
        versionVerdict(versions.formatVersion, versions.appVersionCode, versions.appVersionName, installedVersionCode)

    /**
     * R2: newer format or app refused; older than the minimum refused; null when the versions are fine.
     * Also the restore list's verdict from a header alone (b7 -> b12).
     */
    fun versionVerdict(formatVersion: Int, appVersionCode: Int, appVersionName: String, installedVersionCode: Int): BackupReadResult? {
        val name = appVersionName.take(BackupLimits.MAX_VERSION_NAME_CHARS)
        return when {
            formatVersion > BACKUP_FORMAT_VERSION || appVersionCode > installedVersionCode ->
                BackupReadResult.NeedsNewerApp(name, appVersionCode)
            appVersionCode < MIN_BACKUP_VERSION_CODE -> BackupReadResult.TooOld(name, appVersionCode)
            else -> null
        }
    }

    private fun failureReason(headerRead: Boolean) =
        if (headerRead) BackupReadResult.Reason.DAMAGED else BackupReadResult.Reason.NOT_A_BACKUP

    private fun invalid(reason: BackupReadResult.Reason, detail: String) = BackupReadResult.Invalid(reason, detail)

    /**
     * The header fields every format version keeps, read before the full header so a newer file is
     * refused with its version even when the rest of its header changed shape (R2).
     */
    @Serializable
    private data class HeaderVersions(
        val format: String,
        val formatVersion: Int,
        val appVersionCode: Int,
        val appVersionName: String = "",
    )

    private const val BUFFER_BYTES = 64 * 1024

    /** The sealed section's JSON around the box data: type, KDF id, iterations, salt and nonce. */
    private const val SEALED_LINE_ROOM = 1024
}

/**
 * The per-section limits and the memory budget (D40), shared by the reader and the writer. [admit] runs on
 * a section's bytes before it is decoded; [charge] adds a decoded section's [SectionCost]. [total] starts
 * at the bytes already read (the header line).
 */
internal class SectionLimits(private var total: Long, private var retained: Long = 0L) {

    fun admit(bytes: ByteArray, n: Int) {
        if (n > BackupLimits.MAX_SECTION_BYTES) throw BackupTooLargeException("section size")
        total += n + 1L
        if (total > BackupLimits.DECOMPRESSED_MAX_BYTES) throw BackupTooLargeException("size cap", wholeFile = true)
        if (n == 0) throw BackupShapeException("empty section")
        JsonShape(BackupLimits.MAX_SECTION_TOKENS).feed(bytes, 0, n)
    }

    fun charge(cost: Long) {
        retained += cost
        if (retained > BackupLimits.MEMORY_BUDGET_BYTES) throw BackupTooLargeException("memory budget", wholeFile = true)
    }
}

/**
 * A decoded section's estimated heap (D40): 2 bytes per char (no string compression assumed, minSdk 24)
 * plus a fixed cost per object, string, map entry and set element (its node and its hash-table slots).
 * Conservative, so the budget holds on any ART.
 */
internal object SectionCost {
    private const val OBJECT = 32L
    /** A hash map or set node (48) plus its table slots: up to two 8-byte slots per element after a resize. */
    private const val NODE = 48L + 16L
    private const val COLLECTION = 64L

    /** What reading [body] charged: each of its sections. */
    fun of(body: BackupBody): Long = BackupCodec.sections(body).sumOf { of(it) }

    fun of(line: BackupLine): Long = OBJECT + when (line) {
        is BackupLine.Entry -> of(line.entry)
        is BackupLine.CopySecret -> of(line.entry)
        is BackupLine.Sealed -> with(line.box) { OBJECT + str(kdf) + str(salt) + str(nonce) + str(data) }
    }

    fun of(entry: BackupEntry): Long = OBJECT + str(entry.store) + when (entry) {
        is BackupEntry.Prefs -> COLLECTION + entry.values.entries.sumOf { (key, value) -> NODE + str(key) + of(value) }
        is BackupEntry.TextFile -> str(entry.text)
        is BackupEntry.Photo -> str(entry.name) + str(entry.data)
    }

    private fun of(value: BackupValue): Long = OBJECT + when (value) {
        is BackupValue.Str -> str(value.value)
        is BackupValue.StrSet -> COLLECTION + value.value.sumOf { NODE + str(it) }
        else -> 0L
    }

    private fun str(s: String): Long = 40L + 2L * s.length
}

/**
 * Counts one section's JSON structure before kotlinx builds anything (KT-JSON-03): nesting past
 * [BackupLimits.MAX_JSON_DEPTH], more than [maxTokens] structural tokens or a bare literal over
 * [BackupLimits.MAX_LITERAL_BYTES] throws [BackupShapeException]. Byte-wise is safe: UTF-8 continuation
 * bytes never equal an ASCII quote, backslash or bracket.
 */
internal class JsonShape(private val maxTokens: Int) {
    private var inString = false
    private var escaped = false
    private var depth = 0
    private var tokens = 0
    private var literalBytes = 0

    fun feed(bytes: ByteArray, off: Int, len: Int) {
        for (i in off..<off + len) feed(bytes[i].toInt())
    }

    private fun feed(b: Int) {
        if (inString) {
            when {
                escaped -> escaped = false
                b == '\\'.code -> escaped = true
                b == '"'.code -> inString = false
            }
            return
        }
        when (b) {
            '"'.code -> inString = true
            '{'.code, '['.code -> {
                if (++depth > BackupLimits.MAX_JSON_DEPTH) throw BackupShapeException("nesting depth")
                token()
            }
            '}'.code, ']'.code -> depth--
            ','.code, ':'.code -> token()
            ' '.code, '\r'.code, '\t'.code -> Unit
            else -> {
                // A bare literal's byte; any structural byte or whitespace above ends the run.
                if (++literalBytes > BackupLimits.MAX_LITERAL_BYTES) throw BackupShapeException("literal length")
                return
            }
        }
        literalBytes = 0
    }

    private fun token() {
        if (++tokens > maxTokens) throw BackupShapeException("token count")
    }
}

/**
 * Splits a stream into '\n'-terminated lines in one buffer, allocated at the first line's cap and reused
 * (D40: a section costs its cap, never a growing copy). A line over its cap throws
 * [BackupTooLargeException]; a last line without its '\n' throws [BackupShapeException] (a cut-off file).
 */
internal class LineReader(private val input: InputStream) {
    private val chunk = ByteArray(64 * 1024)
    private var chunkPos = 0
    private var chunkEnd = 0
    private var line = ByteArray(0)

    /** The next line's bytes (valid up to the returned length; the array is reused), or null at the end. */
    fun next(max: Int): Pair<ByteArray, Int>? {
        if (line.size < max) line = ByteArray(max)
        var n = 0
        while (true) {
            if (chunkPos == chunkEnd) {
                val read = input.read(chunk)
                if (read < 0) {
                    if (n == 0) return null
                    throw BackupShapeException("unterminated section")
                }
                // A 0-byte read is not the end of the stream (only -1 is): read again.
                chunkPos = 0
                chunkEnd = read
                continue
            }
            val b = chunk[chunkPos++]
            if (b == NEWLINE) return line to n
            if (n == max) throw BackupTooLargeException(if (max < BackupLimits.MAX_SECTION_BYTES) "header size" else "section size")
            line[n++] = b
        }
    }

    private companion object {
        const val NEWLINE = '\n'.code.toByte()
    }
}

/** Throws [BackupTooLargeException] once more than [limit] bytes have been read. */
private class CappedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
    private var count = 0L

    override fun read(): Int {
        val b = super.read()
        if (b != -1) count(1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) count(n.toLong())
        return n
    }

    override fun skip(n: Long): Long {
        val skipped = super.skip(n)
        if (skipped > 0) count(skipped)
        return skipped
    }

    private fun count(n: Long) {
        count += n
        if (count > limit) throw BackupTooLargeException("size cap", wholeFile = true)
    }
}

/** Lets the gzip streams be closed (freeing their native Inflater/Deflater) without closing the caller's stream. */
private class KeepOpenInputStream(input: InputStream) : FilterInputStream(input) {
    override fun close() = Unit
}

private class KeepOpenOutputStream(out: OutputStream) : FilterOutputStream(out) {
    override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
    override fun close() = flush()
}
