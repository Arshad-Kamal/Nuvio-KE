package com.nuvio.tv.core.backup

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * The backup file (Spec 101 R2, D7). Shape after Arc's backup format (ysosrs123/NuvioTV-Fork
 * 0d6f5f8f2, e34ee509f: typed values, a header, a sealed secrets box), rewritten for KE as strict
 * typed kotlinx classes: an unknown value type or entry kind fails the whole file instead of being
 * dropped.
 *
 * On disk (D40): gzip of '\n'-terminated JSON lines. Line one is [BackupHeader]; every further line is
 * one [BackupLine] section. The header comes first and alone so a reader can refuse a newer or too-old
 * file (R2) from the header without parsing sections whose shape it may not know; sections come one per
 * line so a reader loads and checks one at a time under a fixed memory budget (BackupCodec.kt).
 */

/** Written in [BackupHeader.format]; also the AES-GCM AAD of the sealed secrets (R8). */
const val BACKUP_FORMAT_ID = "nuvio-ke-backup"

/** Raised when the body's shape changes; a reader refuses a newer one (R2, D32). */
const val BACKUP_FORMAT_VERSION = 1

/**
 * The oldest app versionCode whose backups this build restores (R2, D35). Raise it to the new
 * versionCode whenever a once-per-device migration that moves data between stores is added: an older
 * backup would carry the data in the old store without the flag, or the flag without the data.
 * 1440 is a released build from before this format existed (it ships with Spec 101's Close), so no
 * real backup is below it and none is refused yet.
 */
const val MIN_BACKUP_VERSION_CODE = 1440

/** [MIN_BACKUP_VERSION_CODE]'s versionName, for b12's older-file line; raised with it. (Not `*_NAME`: the catalog guard reads those as store names.) */
const val MIN_BACKUP_VERSION_LABEL = "1.3.0-ke103"

@Serializable
enum class BackupKind {
    /** Made by the user (Back up now, Save to phone): secrets only inside [BackupBody.sealedSecrets]. */
    @SerialName("manual") MANUAL,

    /**
     * Made before a restore (D11), app-private: secrets unsealed in [BackupBody.copySecrets]. A reader
     * accepts this kind only from the app's own copies folder (R7).
     */
    @SerialName("pre_restore_copy") PRE_RESTORE_COPY,
}

/** A profile as the backing-up TV had it; profile 1 is the primary (R1 matches by it and by name). */
@Serializable
data class BackupProfile(
    val id: Int,
    val name: String,
    val usesPrimaryAddons: Boolean,
)

@Serializable
data class BackupHeader(
    val format: String,
    val formatVersion: Int,
    val appVersionCode: Int,
    /** Shown on the refusal screen (D34: names both versions). */
    val appVersionName: String,
    val createdAtMs: Long,
    val kind: BackupKind,
    val profiles: List<BackupProfile>,
    /** True when [BackupBody.sealedSecrets] is present, so the restore screen can ask for the PIN first. */
    val sealed: Boolean,
    /** The backing-up device's model (mockup b8, b12: "Made on SHIELD Android TV"); empty when unknown. */
    val deviceName: String = "",
)

/**
 * One stored value with its type, so a restore writes back exactly what was read. The `type` names
 * match the account sync's preference codec (`ProfileSettingsSyncService.encodePreferenceValue`).
 */
@Serializable
sealed interface BackupValue {
    @Serializable @SerialName("string") data class Str(val value: String) : BackupValue
    @Serializable @SerialName("boolean") data class Bool(val value: Boolean) : BackupValue
    @Serializable @SerialName("int") data class IntValue(val value: Int) : BackupValue
    @Serializable @SerialName("long") data class LongValue(val value: Long) : BackupValue
    @Serializable @SerialName("float") data class FloatValue(val value: Float) : BackupValue
    @Serializable @SerialName("double") data class DoubleValue(val value: Double) : BackupValue
    @Serializable @SerialName("string_set") data class StrSet(val value: Set<String>) : BackupValue

    /** The value as a Preferences / SharedPreferences store holds it. */
    val raw: Any
        get() = when (this) {
            is Str -> value
            is Bool -> value
            is IntValue -> value
            is LongValue -> value
            is FloatValue -> value
            is DoubleValue -> value
            is StrSet -> value
        }

    companion object {
        /** Null for a type no Preferences store can hold. */
        fun of(raw: Any?): BackupValue? = when (raw) {
            is String -> Str(raw)
            is Boolean -> Bool(raw)
            is Int -> IntValue(raw)
            is Long -> LongValue(raw)
            is Float -> FloatValue(raw)
            is Double -> DoubleValue(raw)
            is Set<*> -> if (raw.all { it is String }) StrSet(raw.mapTo(LinkedHashSet()) { it as String }) else null
            else -> null
        }
    }
}

/** One catalog store's content ([BackupCatalog] ids only, R7). */
@Serializable
sealed interface BackupEntry {
    val store: String

    /** A DataStore or SharedPreferences file; [profile] is set exactly for per-profile stores. */
    @Serializable
    @SerialName("prefs")
    data class Prefs(
        override val store: String,
        val profile: Int? = null,
        val values: Map<String, BackupValue>,
    ) : BackupEntry

    /** A JSON file the app keeps under `files/` (Live TV). */
    @Serializable
    @SerialName("file")
    data class TextFile(override val store: String, val text: String) : BackupEntry

    /** One My photos JPEG, base64; [name] is its file name in the gallery. */
    @Serializable
    @SerialName("photo")
    data class Photo(val name: String, val data: String) : BackupEntry {
        override val store: String get() = BackupCatalog.MY_PHOTOS
    }
}

/** PBKDF2-HMAC-SHA256 + AES-256-GCM box (R8); byte fields are base64. */
@Serializable
data class SealedBox(
    val kdf: String,
    val iterations: Int,
    val salt: String,
    val nonce: String,
    val data: String,
)

/** A backup's content in memory: the file's sections gathered. */
data class BackupBody(
    /** Everything except secrets (catalog groups PROFILE and DEVICE). */
    val entries: List<BackupEntry>,
    /**
     * Secret entries sealed by the backup PIN; MANUAL only, absent without a PIN (D2). The plaintext is
     * the same section format: one [BackupEntry] JSON line each.
     */
    val sealedSecrets: SealedBox? = null,
    /** Secrets unsealed; PRE_RESTORE_COPY only (R7). */
    val copySecrets: List<BackupEntry> = emptyList(),
)

/** One section of the file, one per line (D40). */
@Serializable
sealed interface BackupLine {
    @Serializable @SerialName("entry") data class Entry(val entry: BackupEntry) : BackupLine
    @Serializable @SerialName("copy_secret") data class CopySecret(val entry: BackupEntry) : BackupLine
    @Serializable @SerialName("sealed") data class Sealed(val box: SealedBox) : BackupLine
}

/**
 * The one JSON instance for backup files, written by this app and read back as untrusted input
 * (KT-JSON-02). Special floats are allowed because a Preferences float may hold NaN; refusing them
 * would make such a TV impossible to back up.
 */
@OptIn(ExperimentalSerializationApi::class)
internal val backupJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = false
    explicitNulls = false
    coerceInputValues = false
    allowSpecialFloatingPointValues = true
}
