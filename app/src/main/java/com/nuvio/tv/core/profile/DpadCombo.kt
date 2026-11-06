package com.nuvio.tv.core.profile

import java.security.MessageDigest
import java.security.SecureRandom

/** One press of the D-pad unlock combination; serialized by name for hashing. */
enum class DpadDirection {
    UP, DOWN, LEFT, RIGHT
}

/**
 * D-pad combination hashing + bounds for the device-local profile unlock
 * (docs/specs/006-profile-guard-features.md). The raw sequence is never
 * persisted: each save draws a fresh random salt and stores it alongside the
 * SHA-256 of "salt:U,D,L,R". That stops cross-device hash reuse, but the
 * keyspace is small (4^6..4^12), so hash+salt do NOT make the combination
 * secret from an attacker holding the file — the threat model is shoulder-surf
 * and casual device access, same class as the 4-digit profile PIN.
 */
object DpadCombo {
    const val MIN_LENGTH = 6
    const val MAX_LENGTH = 12

    private val secureRandom = SecureRandom()

    fun isValidLength(length: Int): Boolean = length in MIN_LENGTH..MAX_LENGTH

    /** 16 random bytes, hex-encoded; a fresh salt per save. */
    fun generateSalt(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return bytes.toHexString()
    }

    fun hash(sequence: List<DpadDirection>, salt: String): String {
        val joined = sequence.joinToString(",") { it.name }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$salt:$joined".toByteArray(Charsets.UTF_8))
        return digest.toHexString()
    }

    /** Constant-time comparison; a plain String.equals leaks matching prefixes. */
    fun verify(sequence: List<DpadDirection>, salt: String, expectedHash: String): Boolean {
        if (salt.isEmpty() || expectedHash.isEmpty()) return false
        return MessageDigest.isEqual(
            hash(sequence, salt).toByteArray(Charsets.UTF_8),
            expectedHash.toByteArray(Charsets.UTF_8)
        )
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x".format(it) }
}
