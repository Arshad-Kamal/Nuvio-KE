package com.nuvio.tv.core.backup

import com.nuvio.tv.core.backup.BackupFixtures.PHOTO_NAME
import com.nuvio.tv.core.backup.BackupFixtures.entries
import com.nuvio.tv.core.backup.BackupFixtures.header
import com.nuvio.tv.core.backup.BackupFixtures.jpeg
import com.nuvio.tv.core.backup.BackupFixtures.secretEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.io.encoding.Base64

class BackupChecksTest {

    private fun refused(detail: String, block: () -> Unit) {
        val e = assertThrows(BackupCheckException::class.java) { block() }
        assertEquals(detail, e.detail)
    }

    private fun body(vararg extra: BackupEntry) = BackupBody(entries() + extra)

    @Test
    fun `the fixture passes every check`() {
        BackupChecks.checkHeader(header(), trustedCopy = false)
        BackupChecks.checkBody(header(), BackupBody(entries()))
        BackupChecks.checkSecrets(header(), secretEntries())
    }

    @Test
    fun `header profiles are 1 to 6 distinct ids including the primary`() {
        refused("profile count") { BackupChecks.checkHeader(header(profiles = emptyList()), false) }
        refused("profile id range") {
            BackupChecks.checkHeader(header(profiles = listOf(BackupProfile(1, "a", false), BackupProfile(7, "b", false))), false)
        }
        refused("duplicate profile id") {
            BackupChecks.checkHeader(header(profiles = listOf(BackupProfile(1, "a", false), BackupProfile(1, "b", false))), false)
        }
        refused("format version") { BackupChecks.checkHeader(header().copy(formatVersion = BACKUP_FORMAT_VERSION + 1), false) }
        refused("no primary profile") { BackupChecks.checkHeader(header(profiles = listOf(BackupProfile(2, "a", false))), false) }
        refused("profile name length") {
            BackupChecks.checkHeader(header(profiles = listOf(BackupProfile(1, "x".repeat(201), false))), false)
        }
        BackupChecks.checkHeader(header().copy(deviceName = "x".repeat(100)), false)
        refused("device name length") { BackupChecks.checkHeader(header().copy(deviceName = "x".repeat(101)), false) }
    }

    @Test
    fun `a pre-restore copy is accepted only from the copies folder`() {
        refused("pre-restore copy from outside the copies folder") {
            BackupChecks.checkHeader(header(kind = BackupKind.PRE_RESTORE_COPY), trustedCopy = false)
        }
        BackupChecks.checkHeader(header(kind = BackupKind.PRE_RESTORE_COPY), trustedCopy = true)
    }

    @Test
    fun `only catalog stores, never excluded ones`() {
        refused("store not in the catalog") { BackupChecks.checkBody(header(), body(BackupEntry.Prefs("profile_settings", values = emptyMap()))) }
        refused("store not in the catalog") { BackupChecks.checkBody(header(), body(BackupEntry.TextFile("../shared_prefs/x.xml", ""))) }
    }

    @Test
    fun `secrets stay out of the plain entries and plain stores out of the secrets`() {
        refused("secret store outside the sealed part") { BackupChecks.checkBody(header(), body(secretEntries().first())) }
        refused("plain store among secrets") { BackupChecks.checkSecrets(header(), entries()) }
    }

    @Test
    fun `per-profile stores need a backup profile and TV-wide stores none`() {
        refused("per-profile store without a backup profile") {
            BackupChecks.checkBody(header(), body(BackupEntry.Prefs("collections", values = emptyMap())))
        }
        refused("per-profile store without a backup profile") {
            BackupChecks.checkBody(header(), body(BackupEntry.Prefs("collections", profile = 3, values = emptyMap())))
        }
        refused("profile on a TV-wide store") {
            BackupChecks.checkBody(header(), body(BackupEntry.Prefs("ui_scale_prefs", profile = 1, values = emptyMap())))
        }
    }

    @Test
    fun `entry kind must match the store kind`() {
        refused("prefs entry for a non-prefs store") {
            BackupChecks.checkBody(header(), body(BackupEntry.Prefs("livetv/epg-overrides.json", values = emptyMap())))
        }
        refused("file entry for a non-file store") { BackupChecks.checkBody(header(), body(BackupEntry.TextFile("ui_scale_prefs", "{}"))) }
    }

    @Test
    fun `duplicates are refused`() {
        refused("duplicate store") { BackupChecks.checkBody(header(), body(entries().first())) }
        refused("duplicate file") { BackupChecks.checkBody(header(), body(BackupEntry.TextFile("livetv/hidden.json", "{}"))) }
        refused("duplicate photo") { BackupChecks.checkBody(header(), body(BackupEntry.Photo(PHOTO_NAME, jpeg))) }
    }

    @Test
    fun `keys and values stay in their caps and SharedPreferences gets no double`() {
        refused("key length") { BackupChecks.checkBody(header(), body(BackupEntry.Prefs("ui_scale_prefs", values = mapOf("" to BackupValue.IntValue(1))))) }
        refused("double in shared preferences") {
            BackupChecks.checkBody(
                header(),
                BackupBody(listOf(BackupEntry.Prefs("app_locale", values = mapOf("x" to BackupValue.DoubleValue(1.0))))),
            )
        }
        refused("file size") {
            BackupChecks.checkBody(header(), body(BackupEntry.TextFile("livetv/epg-overrides.json", "x".repeat(BackupLimits.MAX_TEXT_FILE_CHARS + 1))))
        }
    }

    @Test
    fun `photos need a gallery name, a JPEG header, the size cap and the count cap`() {
        refused("photo name") { BackupChecks.checkBody(header(), body(BackupEntry.Photo("../../x.jpg", jpeg))) }
        refused("photo name") {
            BackupChecks.checkBody(header(), body(BackupEntry.Photo(PHOTO_NAME.replace("avatar", "poster"), jpeg)))
        }
        refused("photo name") {
            BackupChecks.checkBody(header(), body(BackupEntry.Photo(PHOTO_NAME.replace("1763000000000", "9999999999999999999"), jpeg)))
        }
        val second = PHOTO_NAME.replace("avatar", "background")
        refused("photo is not a JPEG") { BackupChecks.checkBody(header(), body(BackupEntry.Photo(second, Base64.encode(byteArrayOf(1, 2, 3, 4))))) }
        refused("photo encoding") { BackupChecks.checkBody(header(), body(BackupEntry.Photo(second, "not*base64"))) }
        // Over the size from its length alone: invalid base64 proves it is never decoded.
        refused("photo size") { BackupChecks.checkBody(header(), body(BackupEntry.Photo(second, "*".repeat((BackupLimits.MAX_PHOTO_BYTES + 2) / 3 * 4 + 1)))) }
        val big = ByteArray(BackupLimits.MAX_PHOTO_BYTES + 1).also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[2] = 0xFF.toByte() }
        refused("photo size") { BackupChecks.checkBody(header(), body(BackupEntry.Photo(second, Base64.encode(big)))) }
        val many = (0..BackupLimits.MAX_PHOTOS).map { i ->
            BackupEntry.Photo("background-${1_763_000_000_000 + i}-0123abcd-4567-89ab-cdef-0123456789ab.jpg", jpeg)
        }
        refused("photo count") { BackupChecks.checkBody(header(), BackupBody(many)) }
    }

    @Test
    fun `excluded keys are refused when writing and read back from an older file`() {
        val withExcluded = body(BackupEntry.Prefs("update_settings", values = mapOf("known_release" to BackupValue.Str("https://x"))))
        BackupChecks.checkBody(header(), withExcluded)
        refused("excluded key") { BackupChecks.checkBody(header(), withExcluded, writing = true) }
    }

    @Test
    fun `entry, secret entry and key counts are capped`() {
        val many = (1..BackupLimits.MAX_ENTRIES + 1).map { BackupEntry.Prefs("theme_settings", profile = 1, values = emptyMap()) }
        refused("entry count") { BackupChecks.checkBody(header(), BackupBody(many)) }
        refused("secret entry count") { BackupChecks.checkSecrets(header(), many) }
        refused("key length") {
            BackupChecks.checkBody(header(), BackupBody(listOf(BackupEntry.Prefs("ui_scale_prefs", values = mapOf("k".repeat(BackupLimits.MAX_KEY_CHARS + 1) to BackupValue.IntValue(1))))))
        }
        refused("created time") { BackupChecks.checkHeader(header().copy(createdAtMs = -1), false) }
        refused("version name length") { BackupChecks.checkHeader(header().copy(appVersionName = "v".repeat(101)), false) }
    }

    @Test
    fun `sealed secrets agree with the header flag and the box is well formed`() {
        val box = BackupCrypto.seal(byteArrayOf(1), "123456", iterations = 10)
        refused("sealed flag") { BackupChecks.checkBody(header(sealed = true), BackupBody(entries())) }
        refused("sealed flag") { BackupChecks.checkBody(header(), BackupBody(entries(), sealedSecrets = box)) }
        BackupChecks.checkBody(header(sealed = true), BackupBody(entries(), sealedSecrets = box))
        refused("KDF id") { BackupChecks.checkSealedBox(box.copy(kdf = "scrypt")) }
        refused("KDF iterations") { BackupChecks.checkSealedBox(box.copy(iterations = 0)) }
        refused("KDF iterations") { BackupChecks.checkSealedBox(box.copy(iterations = BackupCrypto.MAX_ITERATIONS + 1)) }
        refused("salt") { BackupChecks.checkSealedBox(box.copy(salt = Base64.encode(ByteArray(8)))) }
        refused("nonce") { BackupChecks.checkSealedBox(box.copy(nonce = "%%")) }
        refused("sealed data") { BackupChecks.checkSealedBox(box.copy(data = Base64.encode(ByteArray(15)))) }
        refused("sealed data") { BackupChecks.checkSealedBox(box.copy(data = "A".repeat(BackupLimits.MAX_SECTION_BYTES + 1))) }
        refused("salt") { BackupChecks.checkSealedBox(box.copy(salt = "A".repeat(10_000))) }
        refused("sealed data") { BackupCrypto.open(box.copy(data = "%".repeat(24)), "123456") }
    }

    @Test
    fun `unsealed secrets only in a pre-restore copy, sealed only in a manual backup`() {
        val copy = header(kind = BackupKind.PRE_RESTORE_COPY)
        BackupChecks.checkBody(copy, BackupBody(entries(), copySecrets = secretEntries()))
        refused("unsealed secrets outside a copy") { BackupChecks.checkBody(header(), BackupBody(entries(), copySecrets = secretEntries())) }
        val box = BackupCrypto.seal(byteArrayOf(1), "123456", iterations = 10)
        refused("sealed secrets in a copy") {
            BackupChecks.checkBody(copy.copy(sealed = true), BackupBody(entries(), sealedSecrets = box))
        }
        refused("plain store among secrets") { BackupChecks.checkBody(copy, BackupBody(entries(), copySecrets = entries())) }
    }
}
