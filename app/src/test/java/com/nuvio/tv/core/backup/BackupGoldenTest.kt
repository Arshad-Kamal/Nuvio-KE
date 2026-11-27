package com.nuvio.tv.core.backup

import com.nuvio.tv.core.backup.BackupFixtures.INSTALLED
import com.nuvio.tv.core.backup.BackupFixtures.PHOTO_NAME
import com.nuvio.tv.core.backup.BackupFixtures.gunzip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pins the file format and the sealed box against text and crypto written outside this code, so a
 * symmetric change (a renamed field or SerialName, another AAD, another KDF) fails here instead of
 * leaving every backup already on a phone unreadable.
 */
class BackupGoldenTest {

    private val headerLine =
        """{"format":"nuvio-ke-backup","formatVersion":1,"appVersionCode":$INSTALLED,"appVersionName":"1.3.0-ke103",""" +
            """"createdAtMs":1763000000000,"kind":"manual","profiles":[{"id":1,"name":"Arshad","usesPrimaryAddons":false}],""" +
            """"sealed":false}"""

    private val sectionLines = listOf(
        """{"type":"entry","entry":{"type":"prefs","store":"theme_settings","profile":1,"values":{""" +
            """"accent":{"type":"string","value":"amber"},"on":{"type":"boolean","value":true},""" +
            """"n":{"type":"int","value":3},"big":{"type":"long","value":1099511627776},""" +
            """"f":{"type":"float","value":1.25},"d":{"type":"double","value":0.5},""" +
            """"tags":{"type":"string_set","value":["a","b"]}}}}""",
        """{"type":"entry","entry":{"type":"prefs","store":"ui_scale_prefs","values":{}}}""",
        """{"type":"entry","entry":{"type":"file","store":"livetv/hidden.json","text":"{}"}}""",
        """{"type":"entry","entry":{"type":"photo","name":"$PHOTO_NAME","data":"/9j/4AECAw=="}}""",
    )

    private val header = BackupHeader(
        format = BACKUP_FORMAT_ID, formatVersion = 1, appVersionCode = INSTALLED, appVersionName = "1.3.0-ke103",
        createdAtMs = 1_763_000_000_000, kind = BackupKind.MANUAL,
        profiles = listOf(BackupProfile(1, "Arshad", usesPrimaryAddons = false)), sealed = false,
    )

    private val body = BackupBody(
        listOf(
            BackupEntry.Prefs(
                "theme_settings", profile = 1,
                values = linkedMapOf(
                    "accent" to BackupValue.Str("amber"),
                    "on" to BackupValue.Bool(true),
                    "n" to BackupValue.IntValue(3),
                    "big" to BackupValue.LongValue(1L shl 40),
                    "f" to BackupValue.FloatValue(1.25f),
                    "d" to BackupValue.DoubleValue(0.5),
                    "tags" to BackupValue.StrSet(linkedSetOf("a", "b")),
                ),
            ),
            BackupEntry.Prefs("ui_scale_prefs", values = emptyMap()),
            BackupEntry.TextFile("livetv/hidden.json", "{}"),
            BackupEntry.Photo(PHOTO_NAME, "/9j/4AECAw=="),
        ),
    )

    private fun lines(header: String, sections: List<String>) = (listOf(header) + sections).joinToString("") { "$it\n" }

    @Test
    fun `a hand-written file reads to the expected objects`() {
        assertEquals(BackupReadResult.Ok(header, body), BackupFixtures.read(BackupFixtures.gzip(lines(headerLine, sectionLines))))
    }

    @Test
    fun `the writer produces exactly the hand-written text`() {
        assertEquals(lines(headerLine, sectionLines), gunzip(BackupFixtures.bytes(header, body)))
    }

    private val pin = "246810"
    private val aad = "nuvio-ke-backup".toByteArray()
    /** The sealed payload: one entry per line, like the file's sections. */
    private val secretPlain = """{"type":"prefs","store":"tmdb_api_key","values":{"user_key":{"type":"string","value":"k"}}}""" + "\n"
    private val secrets = listOf(BackupEntry.Prefs("tmdb_api_key", values = mapOf("user_key" to BackupValue.Str("k"))))

    /** The JDK's own PBKDF2WithHmacSHA256, independent of BackupCrypto's Mac loop. */
    private fun jdkKey(salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, 256)
        return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
    }

    private fun jdkSeal(salt: ByteArray, nonce: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, jdkKey(salt, 1_000), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return Base64.getEncoder().encodeToString(cipher.doFinal(secretPlain.toByteArray()))
    }

    @Test
    fun `a box sealed by the JDK opens with the PIN`() {
        val salt = ByteArray(16) { it.toByte() }
        val nonce = ByteArray(12) { (100 + it).toByte() }
        val b64 = Base64.getEncoder()
        val box = SealedBox("pbkdf2-hmac-sha256", 1_000, b64.encodeToString(salt), b64.encodeToString(nonce), jdkSeal(salt, nonce))
        assertEquals(secrets, BackupCodec.openSecrets(BackupFixtures.header(sealed = true), box, pin, retained = 0))
    }

    @Test
    fun `a hand-written sealed file reads, and its secrets open with the PIN`() {
        val salt = ByteArray(16) { (200 + it).toByte() }
        val nonce = ByteArray(12) { (50 + it).toByte() }
        val b64 = Base64.getEncoder()
        val sealedHeader = headerLine.replace("\"sealed\":false", "\"sealed\":true")
        val sealedLine = """{"type":"sealed","box":{"kdf":"pbkdf2-hmac-sha256","iterations":1000,""" +
            """"salt":"${b64.encodeToString(salt)}","nonce":"${b64.encodeToString(nonce)}","data":"${jdkSeal(salt, nonce)}"}}"""
        val ok = BackupFixtures.read(BackupFixtures.gzip(lines(sealedHeader, listOf(sealedLine)))) as BackupReadResult.Ok
        assertEquals(true, ok.header.sealed)
        assertEquals(secrets, BackupCodec.openSecrets(ok, pin))
    }

    @Test
    fun `a hand-written pre-restore copy reads with its unsealed secrets`() {
        val copyHeader = headerLine.replace("\"kind\":\"manual\"", "\"kind\":\"pre_restore_copy\"")
        val copyLine = """{"type":"copy_secret","entry":${secretPlain.trimEnd()}}"""
        val ok = BackupFixtures.read(BackupFixtures.gzip(lines(copyHeader, listOf(copyLine))), trustedCopy = true) as BackupReadResult.Ok
        assertEquals(BackupKind.PRE_RESTORE_COPY, ok.header.kind)
        assertEquals(secrets, ok.body.copySecrets)
    }

    @Test
    fun `NaN and infinite floats are written as bare words`() {
        val values = mapOf(
            "n" to BackupValue.FloatValue(Float.NaN),
            "p" to BackupValue.FloatValue(Float.POSITIVE_INFINITY),
            "m" to BackupValue.DoubleValue(Double.NEGATIVE_INFINITY),
        )
        val text = gunzip(BackupFixtures.bytes(BackupFixtures.header(), BackupBody(listOf(BackupEntry.Prefs("player_settings", 1, values)))))
        assertTrue(text, "\"n\":{\"type\":\"float\",\"value\":NaN}" in text)
        assertTrue(text, "\"p\":{\"type\":\"float\",\"value\":Infinity}" in text)
        assertTrue(text, "\"m\":{\"type\":\"double\",\"value\":-Infinity}" in text)
    }

    @Test
    fun `a box sealed here opens with the JDK to the expected payload`() {
        val box = BackupCodec.sealSecrets(BackupFixtures.header(sealed = true), secrets, pin, iterations = 1_000)
        val b64 = Base64.getDecoder()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, jdkKey(b64.decode(box.salt), box.iterations), GCMParameterSpec(128, b64.decode(box.nonce)))
        cipher.updateAAD(aad)
        assertEquals(secretPlain, String(cipher.doFinal(b64.decode(box.data))))
    }
}
