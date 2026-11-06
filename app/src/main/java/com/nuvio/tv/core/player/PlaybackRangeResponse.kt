package com.nuvio.tv.core.player

import androidx.media3.common.C
import okhttp3.Response
import java.io.EOFException
import java.io.IOException

/*
 * Strict 206 checks for bytes the player adopts at a position it chose: the prefetch prewarm
 * windows and the ParallelRangeDataSource probe (Register A2 P12; upstream ysosrs 0954ef772
 * PlaybackPrewarmReader and a5e29d3cd ParallelRangeProbeResponse). A 206 whose Content-Range
 * does not describe the requested window would otherwise be read as the file's bytes at the
 * wrong offset.
 */

/** A parsed `Content-Range: bytes first-last/total`; [total] is null for `*`. */
internal data class ContentRange(val first: Long, val last: Long, val total: Long?) {
    val span: Long get() = last - first + 1
}

private val CONTENT_RANGE = Regex("^bytes ([0-9]+)-([0-9]+)/([0-9]+|\\*)$", RegexOption.IGNORE_CASE)
private val DECIMAL = Regex("^[0-9]+$")

/**
 * The single Content-Range value, or null when absent, repeated, malformed, or inconsistent
 * (last < first, or last at/after a known total). All values are passed because OkHttp's
 * header() returns only the last one, which could hide a conflicting earlier field.
 */
internal fun parseSingleContentRange(values: List<String>): ContentRange? {
    val match = CONTENT_RANGE.matchEntire(values.singleOrNull()?.trim() ?: return null) ?: return null
    val first = match.groupValues[1].toLongOrNull() ?: return null
    val last = match.groupValues[2].toLongOrNull() ?: return null
    val totalText = match.groupValues[3]
    val total = if (totalText == "*") null else totalText.toLongOrNull() ?: return null
    if (last < first || (total != null && last >= total)) return null
    return ContentRange(first, last, total)
}

/**
 * A probe 206 for a different window than requested. Deterministic (the same server answers the
 * same way), so PlayerLoadErrorHandlingPolicy fails it fast instead of riding the retry ladder.
 */
internal class InvalidRangeResponseException(label: String) : IOException("Invalid parallel probe $label")

private fun isIdentityEncoded(contentEncodings: List<String>): Boolean =
    contentEncodings.all { it.trim().equals("identity", ignoreCase = true) }

/** No Content-Length, or exactly one decimal value equal to [span]. */
private fun contentLengthMatches(contentLengths: List<String>, span: Long): Boolean {
    if (contentLengths.isEmpty()) return true
    val value = contentLengths.singleOrNull()?.trim() ?: return false
    return DECIMAL.matches(value) && value.toLongOrNull() == span
}

/**
 * The file total proven by the PRDS probe's response to a request at [position] for [length]
 * bytes (C.LENGTH_UNSET = to the end). Returns C.LENGTH_UNSET for a non-206 or an opaque (`*`)
 * total: the caller then stays on one connection. Throws for a 206 that starts elsewhere, is not
 * identity-encoded, disagrees with its Content-Length, or is shorter than requested.
 * Deviation from upstream: a longer span with the right start passes (reads are bounded and
 * such hosts played before).
 */
internal fun validatedProbeTotal(
    position: Long,
    length: Long,
    responseCode: Int,
    headers: Map<String, List<String>>
): Long {
    if (responseCode != 206) return C.LENGTH_UNSET.toLong()
    fun values(name: String) = headers.entries.filter { it.key.equals(name, ignoreCase = true) }.flatMap { it.value }
    fun reject(label: String): Nothing = throw InvalidRangeResponseException(label)
    val range = parseSingleContentRange(values("Content-Range")) ?: reject("range")
    if (range.first != position) reject("start")
    if (!isIdentityEncoded(values("Content-Encoding"))) reject("encoding")
    if (!contentLengthMatches(values("Content-Length"), range.span)) reject("length")
    val total = range.total ?: return C.LENGTH_UNSET.toLong()
    val available = total - position
    val requested = if (length == C.LENGTH_UNSET.toLong()) available else minOf(length, available)
    if (range.span < requested) reject("short")
    return total
}

/** Reads optional prewarm windows without trusting the server to honour Range. */
internal object PrewarmWindowReader {
    class Window(val start: Long, val total: Long, val bytes: ByteArray)

    /** Fixed labels only: never headers, URLs or exception messages (they can carry credentials). */
    enum class Rejection {
        HTTP_STATUS, CONTENT_ENCODING, CONTENT_RANGE, TOTAL_CHANGED, RANGE_START, RANGE_LENGTH,
        CONTENT_LENGTH, SHORT_BODY, OVERLONG_BODY, IO_FAILURE
    }

    /**
     * The window from [response] for a request of [maxBytes] at [start] (null = a suffix request
     * for the last [maxBytes]), or null when the response does not describe exactly that window.
     * [expectedTotal] binds a tail request derived from an earlier total to that total. Reads the
     * window plus at most one Okio segment (exact when Content-Length frames the body); closes
     * [response].
     */
    fun read(
        response: Response,
        maxBytes: Long,
        start: Long?,
        expectedTotal: Long? = null,
        onRejected: (Rejection) -> Unit = {}
    ): Window? = response.use { resp ->
        fun reject(reason: Rejection): Window? {
            onRejected(reason)
            return null
        }
        if (resp.code != 206) return reject(Rejection.HTTP_STATUS)
        if (!isIdentityEncoded(resp.headers.values("Content-Encoding"))) return reject(Rejection.CONTENT_ENCODING)
        val range = parseSingleContentRange(resp.headers.values("Content-Range"))
            ?: return reject(Rejection.CONTENT_RANGE)
        val total = range.total ?: return reject(Rejection.CONTENT_RANGE)
        if (expectedTotal != null && total != expectedTotal) return reject(Rejection.TOTAL_CHANGED)
        val wantedStart = start ?: (total - maxBytes).coerceAtLeast(0L)
        if (range.first != wantedStart) return reject(Rejection.RANGE_START)
        val wantedSize = minOf(maxBytes, total - wantedStart)
        if (range.span != wantedSize) return reject(Rejection.RANGE_LENGTH)
        if (!contentLengthMatches(resp.headers.values("Content-Length"), wantedSize)) {
            return reject(Rejection.CONTENT_LENGTH)
        }
        val bytes = ByteArray(wantedSize.toInt())
        try {
            val source = resp.body.source()
            source.readFully(bytes)
            if (!source.exhausted()) return reject(Rejection.OVERLONG_BODY)
        } catch (_: EOFException) {
            return reject(Rejection.SHORT_BODY)
        } catch (_: IOException) {
            return reject(Rejection.IO_FAILURE)
        }
        Window(wantedStart, total, bytes)
    }
}
