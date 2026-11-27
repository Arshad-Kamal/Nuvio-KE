package com.nuvio.tv.core.backup

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64

/** The PIN does not open the box (or the box was altered: GCM cannot tell the two apart). */
class WrongBackupPinException : Exception("wrong backup PIN")

/*
 * Seals the passwords and keys with the backup PIN (Spec 101 R8, D2, D17). Shape after Arc's
 * BackupCrypto (ysosrs123/NuvioTV-Fork 0d6f5f8f2). KE differs: PBKDF2 runs over Mac("HmacSHA256")
 * itself, because SecretKeyFactory "PBKDF2WithHmacSHA256" exists only from API 26 (minSdk is 24) and
 * Arc's SHA-1 fallback made the KDF depend on the device; the KDF id and iterations go in the box.
 */
object BackupCrypto {
    const val KDF_ID = "pbkdf2-hmac-sha256"
    const val ITERATIONS = 600_000
    /** A box asking for more is refused (R8): a crafted file cannot make the TV derive for minutes. */
    const val MAX_ITERATIONS = 2_000_000
    const val PIN_MIN_DIGITS = 6
    const val PIN_MAX_DIGITS = 12
    const val SALT_BYTES = 16
    const val NONCE_BYTES = 12
    const val TAG_BYTES = 16
    private const val KEY_BYTES = 32
    private const val HMAC = "HmacSHA256"

    fun isValidPin(pin: String): Boolean =
        pin.length in PIN_MIN_DIGITS..PIN_MAX_DIGITS && pin.all { it in '0'..'9' }

    fun seal(
        plain: ByteArray,
        pin: String,
        random: SecureRandom = SecureRandom(),
        iterations: Int = ITERATIONS,
    ): SealedBox {
        require(isValidPin(pin)) { "backup PIN must be $PIN_MIN_DIGITS-$PIN_MAX_DIGITS digits" }
        require(iterations in 1..MAX_ITERATIONS) { "iterations out of range" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val key = pbkdf2HmacSha256(pin.encodeToByteArray(), salt, iterations, KEY_BYTES)
        val data = aesGcm(Cipher.ENCRYPT_MODE, key, nonce, AAD, plain)
        return SealedBox(KDF_ID, iterations, Base64.encode(salt), Base64.encode(nonce), Base64.encode(data))
    }

    /**
     * The plaintext of [box]. Throws [WrongBackupPinException] when the PIN (or the box) is wrong,
     * BackupCheckException when the box is malformed ([BackupChecks.checkSealedBox], checked before
     * any key derivation).
     */
    fun open(box: SealedBox, pin: String): ByteArray {
        BackupChecks.checkSealedBox(box)
        if (!isValidPin(pin)) throw WrongBackupPinException()
        val salt = Base64.decode(box.salt)
        val nonce = Base64.decode(box.nonce)
        val data = try {
            Base64.decode(box.data)
        } catch (_: IllegalArgumentException) {
            throw BackupCheckException("sealed data")
        }
        if (data.size < TAG_BYTES) throw BackupCheckException("sealed data")
        val key = pbkdf2HmacSha256(pin.encodeToByteArray(), salt, box.iterations, KEY_BYTES)
        return try {
            aesGcm(Cipher.DECRYPT_MODE, key, nonce, AAD, data)
        } catch (_: GeneralSecurityException) {
            throw WrongBackupPinException()
        }
    }

    /** PBKDF2 (RFC 8018 section 5.2) with HMAC-SHA256 as the PRF. [password] must not be empty. */
    internal fun pbkdf2HmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, keyBytes: Int): ByteArray {
        require(password.isNotEmpty() && iterations >= 1 && keyBytes >= 1)
        val mac = Mac.getInstance(HMAC).apply { init(SecretKeySpec(password, HMAC)) }
        val hLen = mac.macLength
        val out = ByteArray(keyBytes)
        val u = ByteArray(hLen)
        val t = ByteArray(hLen)
        var block = 1
        var offset = 0
        while (offset < keyBytes) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            mac.doFinal(u, 0)
            u.copyInto(t)
            repeat(iterations - 1) {
                mac.update(u)
                mac.doFinal(u, 0)
                for (i in 0..<hLen) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
            }
            val n = minOf(hLen, keyBytes - offset)
            t.copyInto(out, offset, 0, n)
            offset += n
            block++
        }
        return out
    }

    /** AES-GCM, 128-bit tag; [mode] is Cipher.ENCRYPT_MODE or DECRYPT_MODE. Data carries the tag at its end. */
    internal fun aesGcm(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(data)
    }

    private val AAD = BACKUP_FORMAT_ID.encodeToByteArray()
}
