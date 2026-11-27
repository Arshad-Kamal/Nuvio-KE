package com.nuvio.tv.core.backup

import com.nuvio.tv.core.server.PhotoUploadServer
import com.nuvio.tv.data.local.MyPhotosStore
import kotlin.io.encoding.Base64

/**
 * A backup file failed a check. [detail] is fixed text for logs: it never quotes the file (KT-JSON-04).
 * [tooLarge] marks a size limit, which the exporter reports as a backup too large to make (D41); [store]
 * and [profile] name the one section over its own limit, and stay null for a limit on the whole file.
 */
class BackupCheckException(
    val detail: String,
    val tooLarge: Boolean = false,
    val store: String? = null,
    val profile: Int? = null,
) : Exception(detail)

/**
 * Caps that apply to a backup from any source (Spec 101 R7, D39, D40). The reader loads one section at a
 * time: each is capped by [MAX_SECTION_BYTES], [MAX_SECTION_TOKENS], [MAX_JSON_DEPTH] and
 * [MAX_LITERAL_BYTES] before it is decoded, and each decoded section's estimated heap is charged to
 * [MEMORY_BUDGET_BYTES]. Peak while reading: the budget plus one section's transient, about
 * 40 + 11 x 4 + 10.5 = 95 MiB (D40). [DECOMPRESSED_MAX_BYTES] bounds the read's work (gzip bombs). The
 * writer obeys the same limits, so every file it makes reads back. The per-entry caps run on the gathered
 * body. Every value here is to be checked against a real household export before Close (R9).
 */
object BackupLimits {
    /** Phone upload and phone-bound export (R7, R9; D8's 20 MB). */
    const val PHONE_COMPRESSED_MAX_BYTES = 20L * 1024 * 1024
    /** D39: raise only with a measured household export (R9). */
    const val DECOMPRESSED_MAX_BYTES = 32L * 1024 * 1024
    const val HEADER_MAX_BYTES = 64 * 1024
    const val MAX_PROFILES = 6
    const val MAX_PROFILE_NAME_CHARS = 200
    const val MAX_VERSION_NAME_CHARS = 100
    const val MAX_DEVICE_NAME_CHARS = 100
    const val MAX_ENTRIES = 2_000
    /** One section (line) of the file; 4 MiB plus room for one 3 MiB photo as base64 and its JSON. */
    const val MAX_SECTION_BYTES = 4 * 1024 * 1024 + 64 * 1024
    /** Structural tokens (`{ [ , :`) in one section; a typed value costs 6. Bounds its decoded object count. */
    const val MAX_SECTION_TOKENS = 150_000
    /** The estimated heap of everything decoded so far (D40). */
    const val MEMORY_BUDGET_BYTES = 40L * 1024 * 1024
    /** A section nests at most 6 deep (line, entry, values, value, string set). */
    const val MAX_JSON_DEPTH = 8
    /** One bare literal (number, true, false, null); the longest double is about 25 bytes. */
    const val MAX_LITERAL_BYTES = 64
    const val MAX_KEY_CHARS = 4_096
    const val MAX_TEXT_FILE_CHARS = 1024 * 1024
    const val MAX_PHOTO_BYTES = PhotoUploadServer.MAX_PHOTO_BYTES
    const val MAX_PHOTOS = MyPhotosStore.CAP
}

/** The checks a backup passes before anything of it is used (Spec 101 R7). */
object BackupChecks {

    fun checkHeader(header: BackupHeader, trustedCopy: Boolean) {
        ensure(header.format == BACKUP_FORMAT_ID) { "format id" }
        ensure(header.formatVersion in 1..BACKUP_FORMAT_VERSION) { "format version" }
        ensure(header.appVersionName.length <= BackupLimits.MAX_VERSION_NAME_CHARS) { "version name length" }
        ensure(header.deviceName.length <= BackupLimits.MAX_DEVICE_NAME_CHARS) { "device name length" }
        ensure(header.createdAtMs >= 0) { "created time" }
        ensure(header.kind != BackupKind.PRE_RESTORE_COPY || trustedCopy) { "pre-restore copy from outside the copies folder" }
        val ids = header.profiles.map { it.id }
        ensure(ids.size in 1..BackupLimits.MAX_PROFILES) { "profile count" }
        ensure(ids.all { it in 1..BackupLimits.MAX_PROFILES }) { "profile id range" }
        ensure(ids.toSet().size == ids.size) { "duplicate profile id" }
        ensure(1 in ids) { "no primary profile" }
        ensure(header.profiles.all { it.name.length <= BackupLimits.MAX_PROFILE_NAME_CHARS }) { "profile name length" }
    }

    /**
     * [header] has passed [checkHeader]. Sealed secrets are checked again once opened ([checkSecrets]).
     * [writing] adds the exporter-only rule: no [BackupCatalog.excludedKeys]. A file read back may carry
     * one, from a build before the key joined the list (R2: older files restore); apply keeps the
     * target's own value for it (R4).
     */
    fun checkBody(header: BackupHeader, body: BackupBody, writing: Boolean = false) {
        if (body.entries.size > BackupLimits.MAX_ENTRIES) throw BackupCheckException("entry count", tooLarge = true)
        checkEntries(header, body.entries, secret = false, writing)
        ensure((body.sealedSecrets != null) == header.sealed) { "sealed flag" }
        body.sealedSecrets?.let {
            ensure(header.kind == BackupKind.MANUAL) { "sealed secrets in a copy" }
            checkSealedBox(it)
        }
        if (body.copySecrets.isNotEmpty()) {
            ensure(header.kind == BackupKind.PRE_RESTORE_COPY) { "unsealed secrets outside a copy" }
            checkSecrets(header, body.copySecrets, writing)
        }
    }

    /** Secret entries: opened from [BackupBody.sealedSecrets], or a copy's [BackupBody.copySecrets]. */
    fun checkSecrets(header: BackupHeader, entries: List<BackupEntry>, writing: Boolean = false) {
        if (entries.size > BackupLimits.MAX_ENTRIES) throw BackupCheckException("secret entry count", tooLarge = true)
        checkEntries(header, entries, secret = true, writing)
    }

    fun checkSealedBox(box: SealedBox) {
        ensure(box.kdf == BackupCrypto.KDF_ID) { "KDF id" }
        ensure(box.iterations in 1..BackupCrypto.MAX_ITERATIONS) { "KDF iterations" }
        ensure(box.salt.length <= 64 && decodedSize(box.salt) == BackupCrypto.SALT_BYTES) { "salt" }
        ensure(box.nonce.length <= 64 && decodedSize(box.nonce) == BackupCrypto.NONCE_BYTES) { "nonce" }
        // Sized from the text, never decoded here: open() decodes it once (and refuses bad base64).
        // Its upper bound is the section it travels in.
        ensure(box.data.length in MIN_SEALED_CHARS..BackupLimits.MAX_SECTION_BYTES) { "sealed data" }
    }

    /** Base64 of the 16-byte GCM tag alone, the smallest box. */
    private const val MIN_SEALED_CHARS = (BackupCrypto.TAG_BYTES + 2) / 3 * 4

    private fun checkEntries(header: BackupHeader, entries: List<BackupEntry>, secret: Boolean, writing: Boolean) {
        val profileIds = header.profiles.mapTo(HashSet()) { it.id }
        val seen = HashSet<String>()
        var photos = 0
        for (entry in entries) {
            val store = BackupCatalog.store(entry.store) ?: fail("store not in the catalog")
            ensure(store.group.secret == secret) { if (secret) "plain store among secrets" else "secret store outside the sealed part" }
            when (entry) {
                is BackupEntry.Prefs -> {
                    ensure(store.isPrefs) { "prefs entry for a non-prefs store" }
                    if (store.perProfile) {
                        ensure(entry.profile != null && entry.profile in profileIds) { "per-profile store without a backup profile" }
                    } else {
                        ensure(entry.profile == null) { "profile on a TV-wide store" }
                    }
                    ensure(seen.add("${entry.store}#${entry.profile}")) { "duplicate store" }
                    checkPrefs(store, entry.values, writing)
                }
                is BackupEntry.TextFile -> {
                    ensure(store.kind == StoreKind.TEXT_FILE) { "file entry for a non-file store" }
                    ensure(seen.add(entry.store)) { "duplicate file" }
                    if (entry.text.length > BackupLimits.MAX_TEXT_FILE_CHARS) {
                        throw BackupCheckException("file size", tooLarge = true, store = entry.store)
                    }
                }
                is BackupEntry.Photo -> {
                    ensure(BackupCatalog.isPhotoName(entry.name)) { "photo name" }
                    ensure(seen.add("${entry.store}/${entry.name}")) { "duplicate photo" }
                    ensure(++photos <= BackupLimits.MAX_PHOTOS) { "photo count" }
                    checkJpeg(entry.data)
                }
            }
        }
    }

    private fun checkPrefs(store: CatalogStore, values: Map<String, BackupValue>, writing: Boolean) {
        for ((key, value) in values) {
            ensure(key.isNotEmpty() && key.length <= BackupLimits.MAX_KEY_CHARS) { "key length" }
            ensure(!writing || key !in BackupCatalog.excludedKeys) { "excluded key" }
            // SharedPreferences has no double (R7: legal types for the target store).
            ensure(store.kind != StoreKind.SHARED_PREFS || value !is BackupValue.DoubleValue) { "double in shared preferences" }
        }
    }

    private fun checkJpeg(data: String) {
        // Base64 length bound before decoding, so an oversized photo is never materialised.
        ensure(data.length <= (BackupLimits.MAX_PHOTO_BYTES + 2) / 3 * 4) { "photo size" }
        val bytes = decode(data) ?: fail("photo encoding")
        ensure(bytes.size in 3..BackupLimits.MAX_PHOTO_BYTES) { "photo size" }
        ensure(bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) { "photo is not a JPEG" }
    }

    private fun decodedSize(text: String): Int? = decode(text)?.size

    private fun decode(text: String): ByteArray? = try {
        Base64.decode(text)
    } catch (_: IllegalArgumentException) {
        null
    }

    private inline fun ensure(condition: Boolean, detail: () -> String) {
        if (!condition) fail(detail())
    }

    private fun fail(detail: String): Nothing = throw BackupCheckException(detail)
}
