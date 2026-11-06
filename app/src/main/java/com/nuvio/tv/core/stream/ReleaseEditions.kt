package com.nuvio.tv.core.stream

import com.nuvio.tv.domain.model.ReleaseVisualTag
import com.nuvio.tv.domain.model.StreamClientResolveParsed

/**
 * Edition display tokens for a stream's facts (P6), one vocabulary for every source.
 * AIOStreams sends parse-torrent-title's labels ("Extended Edition", "Director's Cut",
 * "Criterion Collection", "Remastered", ...), which [tokensOf] uppercases without the
 * Edition/Collection suffix, so they land on the same tokens as the resolver flags and text.
 * IMAX is the IMAX visual tag, never an edition.
 */
internal object ReleaseEditions {
    private const val EXTENDED = "EXTENDED"
    private const val THEATRICAL = "THEATRICAL"
    private const val REMASTERED = "REMASTERED"
    private const val UNRATED = "UNRATED"

    private val SUFFIX_REGEX = Regex("\\s+(edition|collection)$", RegexOption.IGNORE_CASE)

    /** Whole-token text patterns (lowercase text) with their display tokens. */
    private val TEXT_PATTERNS: List<Pair<Regex, String>> = listOf(
        ReleaseFactsParser.tokenRegex("extended") to EXTENDED,
        ReleaseFactsParser.tokenRegex("remastered") to REMASTERED,
        ReleaseFactsParser.tokenRegex("theatrical") to THEATRICAL,
        ReleaseFactsParser.tokenRegex("unrated") to UNRATED,
        ReleaseFactsParser.tokenRegex("uncut") to "UNCUT",
        ReleaseFactsParser.tokenRegex("criterion") to "CRITERION",
        ReleaseFactsParser.tokenRegex("director'?s[ ._-]?cut") to "DIRECTOR'S CUT"
    )

    fun tokensOf(values: List<String>): List<String> =
        values.map(::normalized).filter { it.isNotEmpty() && it != ReleaseVisualTag.IMAX.label }.distinct()

    fun fromResolver(parsed: StreamClientResolveParsed): List<String> = (
        parsed.edition?.let { tokensOf(listOf(it)) }.orEmpty() + listOfNotNull(
            EXTENDED.takeIf { parsed.extended == true },
            THEATRICAL.takeIf { parsed.theatrical == true },
            REMASTERED.takeIf { parsed.remastered == true },
            UNRATED.takeIf { parsed.unrated == true }
        )
    ).distinct()

    fun fromText(lowercaseText: String): List<String> =
        TEXT_PATTERNS.mapNotNull { (regex, token) -> token.takeIf { regex.containsMatchIn(lowercaseText) } }

    fun isImax(value: String): Boolean = normalized(value) == ReleaseVisualTag.IMAX.label

    private fun normalized(value: String): String = SUFFIX_REGEX.replace(value.trim(), "").uppercase()
}
