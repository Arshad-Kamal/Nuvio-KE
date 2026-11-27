package com.nuvio.tv.core.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import kotlin.io.encoding.Base64

class BackupCryptoTest {

    private fun hex(text: String): ByteArray = text.filterNot { it.isWhitespace() }.hexToByteArray()

    // RFC 7914 section 11 (PBKDF2-HMAC-SHA-256 test vectors).
    @Test
    fun `pbkdf2 matches RFC 7914 vector 1`() {
        val expected = hex(
            "55 ac 04 6e 56 e3 08 9f ec 16 91 c2 25 44 b6 05 f9 41 85 21 6d de 04 65 e6 8b 9d 57 c2 0d ac bc " +
                "49 ca 9c cc f1 79 b6 45 99 16 64 b3 9d 77 ef 31 7c 71 b8 45 b1 e3 0b d5 09 11 20 41 d3 a1 97 83"
        )
        assertArrayEquals(expected, BackupCrypto.pbkdf2HmacSha256("passwd".encodeToByteArray(), "salt".encodeToByteArray(), 1, 64))
    }

    @Test
    fun `pbkdf2 matches RFC 7914 vector 2`() {
        val expected = hex(
            "4d dc d8 f6 0b 98 be 21 83 0c ee 5e f2 27 01 f9 64 1a 44 18 d0 4c 04 14 ae ff 08 87 6b 34 ab 56 " +
                "a1 d4 25 a1 22 58 33 54 9a db 84 1b 51 c9 b3 17 6a 27 2b de bb a1 d0 78 47 8f 62 b3 97 f3 3c 8d"
        )
        assertArrayEquals(expected, BackupCrypto.pbkdf2HmacSha256("Password".encodeToByteArray(), "NaCl".encodeToByteArray(), 80_000, 64))
    }

    // McGrew & Viega, "The Galois/Counter Mode of Operation (GCM)", appendix B, test case 16 (AES-256).
    private val gcmKey = hex("feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308")
    private val gcmPlain = hex(
        "d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a72" +
            "1c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39"
    )
    private val gcmAad = hex("feedfacedeadbeeffeedfacedeadbeefabaddad2")
    private val gcmNonce = hex("cafebabefacedbaddecaf888")
    private val gcmCipherAndTag = hex(
        "522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa" +
            "8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662" +
            "76fc6ece0f4e1768cddf8853bb2d551b"
    )

    @Test
    fun `aes-gcm encrypts GCM spec test case 16`() {
        assertArrayEquals(gcmCipherAndTag, BackupCrypto.aesGcm(Cipher.ENCRYPT_MODE, gcmKey, gcmNonce, gcmAad, gcmPlain))
    }

    @Test
    fun `aes-gcm decrypts GCM spec test case 16 and rejects a flipped tag bit`() {
        assertArrayEquals(gcmPlain, BackupCrypto.aesGcm(Cipher.DECRYPT_MODE, gcmKey, gcmNonce, gcmAad, gcmCipherAndTag))
        val tampered = gcmCipherAndTag.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertThrows(AEADBadTagException::class.java) {
            BackupCrypto.aesGcm(Cipher.DECRYPT_MODE, gcmKey, gcmNonce, gcmAad, tampered)
        }
    }

    @Test
    fun `seal then open round-trips and the box carries the KDF and its parameters`() {
        val plain = "trakt token + tmdb key".encodeToByteArray()
        val box = BackupCrypto.seal(plain, "123456", SecureRandom(), iterations = 1_000)
        assertEquals(BackupCrypto.KDF_ID, box.kdf)
        assertEquals(1_000, box.iterations)
        assertEquals(BackupCrypto.SALT_BYTES, Base64.decode(box.salt).size)
        assertEquals(BackupCrypto.NONCE_BYTES, Base64.decode(box.nonce).size)
        assertArrayEquals(plain, BackupCrypto.open(box, "123456"))
    }

    @Test
    fun `two seals of the same data differ (fresh salt and nonce)`() {
        val a = BackupCrypto.seal(byteArrayOf(1, 2, 3), "123456", iterations = 10)
        val b = BackupCrypto.seal(byteArrayOf(1, 2, 3), "123456", iterations = 10)
        assertNotEquals(a.salt, b.salt)
        assertNotEquals(a.nonce, b.nonce)
    }

    @Test
    fun `wrong PIN and altered data both read as a wrong PIN`() {
        val box = BackupCrypto.seal(byteArrayOf(9, 9), "12345678", iterations = 10)
        assertThrows(WrongBackupPinException::class.java) { BackupCrypto.open(box, "12345679") }
        val data = Base64.decode(box.data).also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertThrows(WrongBackupPinException::class.java) { BackupCrypto.open(box.copy(data = Base64.encode(data)), "12345678") }
    }

    @Test
    fun `open refuses a malformed box before deriving`() {
        val box = BackupCrypto.seal(byteArrayOf(1), "123456", iterations = 10)
        assertThrows(BackupCheckException::class.java) {
            BackupCrypto.open(box.copy(iterations = BackupCrypto.MAX_ITERATIONS + 1), "123456")
        }
        assertThrows(BackupCheckException::class.java) { BackupCrypto.open(box.copy(kdf = "pbkdf2-hmac-sha1"), "123456") }
    }

    @Test
    fun `PIN is 6 to 12 digits`() {
        assertTrue(BackupCrypto.isValidPin("123456"))
        assertTrue(BackupCrypto.isValidPin("123456789012"))
        assertFalse(BackupCrypto.isValidPin("12345"))
        assertFalse(BackupCrypto.isValidPin("1234567890123"))
        assertFalse(BackupCrypto.isValidPin("12345a"))
        assertFalse(BackupCrypto.isValidPin("１２３４５６")) // full-width digits are not 0-9
        assertThrows(IllegalArgumentException::class.java) { BackupCrypto.seal(byteArrayOf(1), "12345") }
    }
}
