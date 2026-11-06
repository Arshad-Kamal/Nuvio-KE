package com.nuvio.tv.core.stream

import com.nuvio.tv.domain.model.Stream
import java.util.Locale

/**
 * Parses a file size out of free-text stream metadata. Addons like Torrentio
 * embed the size in the stream title (e.g. "👤 12 💾 1.81 GB ⚙️ TPB") instead
 * of providing a structured size field, so this is the last-resort fallback
 * for size display and ranking.
 */
object StreamTextSizeParser {

    private val SIZE_REGEX = Regex(
        """(\d+(?:[.,]\d+)?)\s*(TB|GB|MB|KB)\b""",
        RegexOption.IGNORE_CASE
    )

    private const val KILO = 1024.0

    /** Decimal units, the smaller reading of a printed "GB" (the stream list limit's, Spec 51 P1). */
    const val DECIMAL_KILO = 1000.0

    /**
     * Canonical stream size resolution: structured fields first, free-text
     * parsing as last resort. Shared by release-fact parsing so display and
     * ranking cannot drift.
     */
    fun effectiveSizeBytes(stream: Stream): Long? =
        structuredSizeBytes(stream) ?: sizeBytesFromStreamText(stream)

    /**
     * The addon's structured size, the played file's own (Spec 51 D4 check: AIOStreams'
     * streamData.size and videoSize are the episode file even inside a season pack).
     */
    fun structuredSizeBytes(stream: Stream): Long? =
        stream.clientResolve?.stream?.raw?.size
            ?: stream.streamData?.size?.takeIf { it > 0 }
            ?: stream.behaviorHints?.videoSize

    /** [kilo] = the unit step: binary by default (display, ranking), [DECIMAL_KILO] for the smaller reading. */
    fun sizeBytesFromText(text: String?, kilo: Double = KILO): Long? {
        if (text.isNullOrBlank()) return null
        val match = SIZE_REGEX.find(text) ?: return null
        val value = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val multiplier = when (match.groupValues[2].uppercase(Locale.ROOT)) {
            "TB" -> kilo * kilo * kilo * kilo
            "GB" -> kilo * kilo * kilo
            "MB" -> kilo * kilo
            "KB" -> kilo
            else -> return null
        }
        return (value * multiplier).toLong().takeIf { it > 0L }
    }

    fun sizeBytesFromStreamText(stream: Stream, kilo: Double = KILO): Long? =
        sizeBytesFromText(stream.description, kilo)
            ?: sizeBytesFromText(stream.title, kilo)
            ?: sizeBytesFromText(stream.name, kilo)
}
