package com.nuvio.tv.core.player

import com.nuvio.tv.core.stream.ReleaseFactsParser
import com.nuvio.tv.domain.model.Stream

/**
 * Deterministic quality ranking for auto-select (task 3.7; TRaSH-aligned
 * rebuild).
 *
 * Candidates are ranked with the shared release-fact extraction
 * (ReleaseFactsParser) against the shipped default ladders
 * (ReleaseRankPreferences.DEFAULT); there is no user-editable override.
 *
 * Order (Q18: resolution wins outright, no cross-tier trading):
 * resolution -> quality (REMUX > BluRay > WEB-DL > ...) -> preferred release
 * group ladder (TRaSH HQ tiers by default) -> visual tags -> audio tags
 * (TrueHD first by default) -> channels -> encode -> size (desc) -> container
 * (mkv over mp4/unknown). Ties keep incoming order (stable sort).
 *
 * Streams matching any EXCLUDED preference (release groups, encodes, visual
 * tags, qualities, ...) are dropped from the pool first. If that empties the
 * pool, ranking falls back to the unfiltered candidates so auto-select still
 * plays something rather than silently doing nothing. There are no
 * required/floor filters: resolution-rank ordering means low tiers only win
 * when nothing better exists.
 */
object StreamQualityRank {

    private val CONTAINER_MKV = Regex("\\.mkv\\b|\\bmkv\\b", RegexOption.IGNORE_CASE)

    /** Stable, deterministic best-first ordering of the given streams. */
    fun rank(streams: List<Stream>): List<Stream> {
        if (streams.size <= 1) return streams
        val factsByStream = streams.associateWith { ReleaseFactsParser.factsFor(it) }
        val pool = streams.filter {
            ReleaseFactsParser.passesExclusionFilters(factsByStream.getValue(it))
        }.ifEmpty { streams }
        return pool.sortedWith(
            compareBy<Stream> { factsByStream.getValue(it).resolutionRank }
                .thenBy { factsByStream.getValue(it).qualityRank }
                .thenBy { factsByStream.getValue(it).groupRank }
                .thenBy { factsByStream.getValue(it).visualRank }
                .thenBy { factsByStream.getValue(it).audioRank }
                .thenBy { factsByStream.getValue(it).channelRank }
                .thenBy { factsByStream.getValue(it).encodeRank }
                .thenByDescending { factsByStream.getValue(it).size ?: -1L }
                .thenByDescending { containerScore(it) }
        )
    }

    internal fun containerScore(stream: Stream): Int {
        val text = listOfNotNull(
            stream.behaviorHints?.filename,
            stream.getStreamUrl(),
            stream.name,
            stream.title,
            stream.description
        ).joinToString(" ")
        return if (CONTAINER_MKV.containsMatchIn(text)) 1 else 0
    }
}
