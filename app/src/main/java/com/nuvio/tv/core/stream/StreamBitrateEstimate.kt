package com.nuvio.tv.core.stream

import com.nuvio.tv.core.assessment.averageMbps
import com.nuvio.tv.domain.model.Stream

/*
 * A stream's average bitrate from its size and length (Spec 51 P1), for the stream list limit.
 * No addon sends a bitrate, so this is size x 8 / seconds, as AIOStreams' useMetadataRuntime.
 * Every doubt lands on the showing side (a lower estimate, or null = unknown, never hidden).
 */

/**
 * No film file averages more (UHD Blu-ray's top disc read rate is 144 Mbit/s, Wikipedia citing
 * the BDA white paper, checked 2026-11-02): an estimate above it is a wrong size or length.
 */
const val STREAM_ESTIMATE_CEILING_MBPS = 150.0

/**
 * Mbit/s, or null when the size or length is unknown or the result is over
 * [STREAM_ESTIMATE_CEILING_MBPS]. [runtimeMinutes] is the list's runtime (the episode's for a
 * series), [episode] whether the list is a series episode's.
 */
fun estimatedStreamMbps(stream: Stream, runtimeMinutes: Int?, episode: Boolean): Double? =
    estimatedStreamMbps(countedSizeBytes(stream, episode), stream, runtimeMinutes)

/** The estimate over a size [countedSizeBytes] already gave. */
internal fun estimatedStreamMbps(countedSizeBytes: Long?, stream: Stream, runtimeMinutes: Int?): Double? {
    val size = countedSizeBytes?.takeIf { it > 0 } ?: return null
    val seconds = streamLengthSeconds(stream, runtimeMinutes) ?: return null
    return averageMbps(size, seconds).takeIf { it <= STREAM_ESTIMATE_CEILING_MBPS }
}

/**
 * The size the estimate counts (Spec 51 D5). A structured size is the played file's own; a size
 * read from the stream's text may be a whole season pack's, so for an episode it counts only when
 * the stream names a single-episode file (Torrentio's text size is that file's then). It is read
 * in decimal units, the smaller reading of a printed "GB". For an episode, a file named as several
 * episodes counts no size at all: one episode's runtime would double its estimate.
 */
internal fun countedSizeBytes(stream: Stream, episode: Boolean): Long? {
    val kind = if (episode) episodeFileKind(stream) else null
    if (kind == EpisodeFileKind.MULTI) return null
    return StreamTextSizeParser.structuredSizeBytes(stream)
        ?: StreamTextSizeParser.sizeBytesFromStreamText(stream, StreamTextSizeParser.DECIMAL_KILO)
            ?.takeIf { !episode || kind == EpisodeFileKind.SINGLE }
}

/**
 * Seconds of video: AIOStreams' own file length unless it is under half the list runtime (a wrong
 * length), else the list runtime. A file longer than the runtime keeps its own length (a lower
 * estimate). Null when neither is usable.
 */
internal fun streamLengthSeconds(stream: Stream, runtimeMinutes: Int?): Double? {
    val runtimeSeconds = runtimeMinutes?.takeIf { it > 0 }?.let { it * 60.0 }
    val fileSeconds = stream.streamData?.durationMs?.takeIf { it > 0 }?.let { it / 1000.0 }
    if (fileSeconds != null && (runtimeSeconds == null || fileSeconds >= runtimeSeconds * 0.5)) return fileSeconds
    return runtimeSeconds
}

internal enum class EpisodeFileKind { SINGLE, MULTI }

/**
 * What the stream's file names say: MULTI when any names several episodes, else SINGLE when any
 * names one, else null (no episode token).
 */
internal fun episodeFileKind(stream: Stream): EpisodeFileKind? {
    val tokens = listOfNotNull(
        stream.behaviorHints?.filename,
        stream.streamData?.filename,
        stream.clientResolve?.filename,
        stream.clientResolve?.stream?.raw?.filename
    ).flatMap { EPISODE_TOKEN.findAll(it) }
    if (tokens.isEmpty()) return null
    return if (tokens.any { it.groups[1] != null }) EpisodeFileKind.MULTI else EpisodeFileKind.SINGLE
}

/**
 * One SxxEyy token; group 1 = a second episode right after it ("E04", "-04", "-E04", " - E04",
 * "-S01E04", ".S01E04"). A hyphen then any digit reads as a range on purpose ("S02E03-720p" is
 * MULTI): a misread only leaves the size unknown.
 */
private val EPISODE_TOKEN = Regex(
    """(?<![a-z0-9])s\d{1,2}[ ._-]?e\d{1,4}(?!\d)""" +
        """([ ._]?-[ ._]?(?:s\d{1,2}[ ._-]?)?e?\d|[ ._]?(?:s\d{1,2}[ ._-]?)?e\d)?""",
    RegexOption.IGNORE_CASE
)
