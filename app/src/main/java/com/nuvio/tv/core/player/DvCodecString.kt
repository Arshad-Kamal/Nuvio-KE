package com.nuvio.tv.core.player

/**
 * Dolby Vision codec strings ("dvhe.07.06" = prefix.profile.level): one home for the profile
 * parse and the profile 5/7 -> 8 rewrite that the MKV transformer and the MP4/TS extractor
 * output both signal after RPU conversion.
 */
internal object DvCodecString {
    private val DV_PREFIXES = setOf("dvhe", "dvh1", "dvav", "dva1")

    /** Profile of the first codec in [codecs], e.g. "dvhe.07.06" -> 7; null when it is not DV. */
    fun profile(codecs: String?): Int? =
        codecs?.split(',')?.first()?.let(::entryProfile)

    /**
     * [codecs] with every DV profile 5 or 7 entry rewritten to profile 8, keeping the prefix
     * case and a two-digit profile ("dvhe.7.06" -> "dvhe.08.06"); null when nothing changes.
     */
    fun rewriteToProfile8(codecs: String?): String? {
        if (codecs.isNullOrBlank()) return null
        val rewritten = codecs.split(',').joinToString(",") { entry ->
            val profile = entryProfile(entry)
            if (profile != 5 && profile != 7) return@joinToString entry
            val parts = entry.trim().split('.').toMutableList()
            parts[1] = "8".padStart(parts[1].length.coerceAtLeast(2), '0')
            parts.joinToString(".")
        }
        return rewritten.takeIf { it != codecs }
    }

    private fun entryProfile(entry: String): Int? {
        val parts = entry.trim().split('.')
        if (parts.size < 2 || parts[0].lowercase() !in DV_PREFIXES) return null
        return parts[1].toIntOrNull()
    }
}
