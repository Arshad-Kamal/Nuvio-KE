package com.nuvio.tv.core.stream

/**
 * Audio-language display tokens for a stream's facts (P7): ISO 639-1 codes in upper case, or
 * [MULTI] for multi/dual-audio releases and for more than two languages.
 */
internal object ReleaseLanguages {
    const val MULTI = "MULTI"

    private const val MAX_LISTED = 2

    private val MULTI_VALUES = setOf("multi", "multi audio", "dual", "dual audio")

    /**
     * AIOStreams' LANGUAGES names (packages/core/src/utils/constants.ts, 2026-10-11). A table,
     * not Locale lookup: "Latino" and "Portuguese (Brazil)" are not language names.
     * "Dubbed", "Original" and "Unknown" carry no language and map to nothing. Resolver
     * parses write Latino as "la" (Latin's code), mapped here like AIOStreams' "Latino".
     */
    private val CODE_BY_NAME = mapOf(
        "la" to "ES",
        "english" to "EN", "japanese" to "JA", "chinese" to "ZH", "russian" to "RU",
        "arabic" to "AR", "portuguese" to "PT", "portuguese (brazil)" to "PT", "spanish" to "ES",
        "latino" to "ES", "french" to "FR", "german" to "DE", "italian" to "IT", "korean" to "KO",
        "hindi" to "HI", "bengali" to "BN", "punjabi" to "PA", "marathi" to "MR",
        "gujarati" to "GU", "tamil" to "TA", "telugu" to "TE", "kannada" to "KN",
        "malayalam" to "ML", "thai" to "TH", "vietnamese" to "VI", "indonesian" to "ID",
        "turkish" to "TR", "hebrew" to "HE", "persian" to "FA", "ukrainian" to "UK",
        "greek" to "EL", "lithuanian" to "LT", "latvian" to "LV", "estonian" to "ET",
        "polish" to "PL", "czech" to "CS", "slovak" to "SK", "hungarian" to "HU",
        "romanian" to "RO", "bulgarian" to "BG", "serbian" to "SR", "croatian" to "HR",
        "slovenian" to "SL", "dutch" to "NL", "danish" to "DA", "finnish" to "FI",
        "swedish" to "SV", "norwegian" to "NO", "malay" to "MS"
    )

    private val ISO_639_1_REGEX = Regex("[a-z]{2}")

    /**
     * "multi"/"dual" as a release token, not "multi-subs"/"-subtitles"/"-subbed" (subtitles,
     * not audio). Lowercase input; the fallback when no structured languages exist (QU5-13).
     */
    private val MULTI_TEXT_REGEX =
        Regex("(^|[^a-z0-9])(multi|dual)(?![a-z0-9])(?![ ._-]*sub(s|bed|titles?)?(?![a-z0-9]))")

    /** Language names or codes (AIOStreams names, resolver codes) to display tokens. */
    fun tokensOf(values: List<String>): List<String> {
        val tokens = values.mapNotNull(::tokenOf).distinct()
        return if (MULTI in tokens || tokens.size > MAX_LISTED) listOf(MULTI) else tokens
    }

    fun multiFromText(lowercaseText: String): List<String> =
        if (MULTI_TEXT_REGEX.containsMatchIn(lowercaseText)) listOf(MULTI) else emptyList()

    private fun tokenOf(value: String): String? {
        val normalized = value.trim().lowercase()
        if (normalized in MULTI_VALUES) return MULTI
        return CODE_BY_NAME[normalized] ?: normalized.takeIf { ISO_639_1_REGEX.matches(it) }?.uppercase()
    }
}
