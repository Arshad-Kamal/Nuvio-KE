package com.nuvio.tv.core.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.io.encoding.Base64

internal object BackupFixtures {
    const val INSTALLED = 1500

    fun header(
        kind: BackupKind = BackupKind.MANUAL,
        sealed: Boolean = false,
        profiles: List<BackupProfile> = listOf(
            BackupProfile(1, "Arshad", usesPrimaryAddons = false),
            BackupProfile(2, "Kids", usesPrimaryAddons = true),
        ),
        appVersionCode: Int = INSTALLED,
    ) = BackupHeader(
        format = BACKUP_FORMAT_ID,
        formatVersion = BACKUP_FORMAT_VERSION,
        appVersionCode = appVersionCode,
        appVersionName = "1.3.0-ke103",
        createdAtMs = 1_763_000_000_000,
        kind = kind,
        profiles = profiles,
        sealed = sealed,
    )

    val jpeg: String = Base64.encode(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3))
    const val PHOTO_NAME = "avatar-1763000000000-0123abcd-4567-89ab-cdef-0123456789ab.jpg"

    fun entries(): List<BackupEntry> = listOf(
        BackupEntry.Prefs(
            "theme_settings", profile = 1,
            values = mapOf(
                "accent" to BackupValue.Str("amber"),
                "migration_theme_done" to BackupValue.Bool(true),
                "scale" to BackupValue.FloatValue(1.25f),
                "ratio" to BackupValue.DoubleValue(0.5),
                "count" to BackupValue.IntValue(3),
                "stamp" to BackupValue.LongValue(1L shl 40),
                "tags" to BackupValue.StrSet(setOf("a", "b")),
            ),
        ),
        BackupEntry.Prefs("watch_count", profile = 2, values = mapOf("wc_tt1" to BackupValue.IntValue(2))),
        BackupEntry.Prefs("device_player_settings", values = mapOf("tunneling" to BackupValue.Bool(true))),
        BackupEntry.Prefs("app_locale", values = mapOf("tag" to BackupValue.Str("en"))),
        BackupEntry.TextFile("livetv/hidden.json", "{\"keys\":[]}"),
        BackupEntry.Photo(PHOTO_NAME, jpeg),
    )

    fun secretEntries(): List<BackupEntry> = listOf(
        BackupEntry.Prefs("tracking_accounts", profile = 1, values = mapOf("trakt" to BackupValue.Str("{token}"))),
        BackupEntry.Prefs("tmdb_api_key", values = mapOf("user_key" to BackupValue.Str("k"))),
        BackupEntry.TextFile("livetv/sources.json", "[]"),
    )

    fun bytes(header: BackupHeader = header(), body: BackupBody = BackupBody(entries())): ByteArray =
        ByteArrayOutputStream().also { BackupCodec.write(it, header, body) }.toByteArray()

    fun read(bytes: ByteArray, compressedLimit: Long? = null, trustedCopy: Boolean = false): BackupReadResult =
        BackupCodec.read(ByteArrayInputStream(bytes), INSTALLED, compressedLimit, trustedCopy)

    fun gunzip(bytes: ByteArray): String =
        java.util.zip.GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes().decodeToString() }

    /** Gzip of raw text, for files the writer would never produce. */
    fun gzip(text: String): ByteArray = gzip(text.encodeToByteArray())

    fun gzip(raw: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
        GZIPOutputStream(out).use { it.write(raw) }
    }.toByteArray()
}
