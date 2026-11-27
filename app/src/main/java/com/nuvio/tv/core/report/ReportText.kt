package com.nuvio.tv.core.report

/** The six parts of D3, raw (before the scrub), in D3's order. */
data class ReportParts(
    val thisTv: String,
    val app: String,
    val lastCloses: String,
    val playbackErrors: String,
    val settings: String,
    val appLog: String,
)

/**
 * What the panel shows and sends: each part exactly as uploaded (B2), the upload's bytes, and the
 * B1 summary lines (TV only, never in [bytes]).
 */
data class ReportFile(val parts: ReportParts, val bytes: ByteArray, val summary: ReportSummary = ReportSummary()) {
    override fun equals(other: Any?): Boolean =
        other is ReportFile && parts == other.parts && bytes.contentEquals(other.bytes) && summary == other.summary
    override fun hashCode(): Int = 31 * (31 * parts.hashCode() + bytes.contentHashCode()) + summary.hashCode()
}

/**
 * Spec 116 D3: builds, scrubs (D6), then trims by UTF-8 byte count so the whole file is at most
 * [MAX_BYTES] (a margin under the Worker's 262,144). Pure. The App log goes first, oldest lines
 * first; when the other parts alone still overflow (many ANR traces), Last closes, then Playback
 * errors, then This TV keep their first lines. The app and settings parts are a few fixed lines.
 */
object ReportText {
    const val FIRST_LINE = "Nuvio KE report v1"
    const val MAX_BYTES = 245_760

    private val TITLES = listOf("This TV", "Nuvio KE", "Last closes", "Playback errors", "Playback settings", "App log")

    /** [summary]: the builder's structured lines, kept only where the final parts still carry them. */
    fun build(raw: ReportParts, secrets: ReportSecrets, summary: ReportSummary = ReportSummary()): ReportFile {
        val scrub = { text: String -> ReportScrub.scrub(text, secrets.knownValues, secrets.addonHosts) }
        // The settings part is fixed keys and raw values, scrubbed all the same: one rule, every part.
        val scrubbed = ReportParts(
            thisTv = scrub(raw.thisTv),
            app = scrub(raw.app),
            lastCloses = scrub(raw.lastCloses),
            playbackErrors = scrub(raw.playbackErrors),
            settings = scrub(raw.settings),
            appLog = scrub(raw.appLog),
        )
        var parts = scrubbed
        parts = parts.copy(appLog = fit(parts.appLog, MAX_BYTES - size(parts.copy(appLog = "")), keepNewest = true))
        val cuts: List<Pair<(ReportParts) -> String, (ReportParts, String) -> ReportParts>> = listOf(
            ReportParts::lastCloses to { p, v -> p.copy(lastCloses = v) },
            ReportParts::playbackErrors to { p, v -> p.copy(playbackErrors = v) },
            ReportParts::thisTv to { p, v -> p.copy(thisTv = v) },
        )
        for ((get, set) in cuts) {
            if (size(parts) <= MAX_BYTES) break
            parts = set(parts, fit(get(parts), MAX_BYTES - size(set(parts, "")), keepNewest = false))
        }
        val bytes = render(parts).toByteArray(Charsets.UTF_8)
        check(bytes.size <= MAX_BYTES) { "report over the cap: ${bytes.size}" }
        return ReportFile(parts, bytes, summary.shownIn(parts, logTrimmed = parts.appLog != scrubbed.appLog))
    }

    private fun size(parts: ReportParts): Int = render(parts).toByteArray(Charsets.UTF_8).size

    /**
     * The lines of [text] that fit in [budget] UTF-8 bytes, each line counting its `\n`: the last
     * lines when [keepNewest] (a log is oldest first), else the first.
     */
    private fun fit(text: String, budget: Int, keepNewest: Boolean): String {
        if (text.isEmpty() || budget <= 0) return ""
        if (utf8Size(text) + 1 <= budget) return text
        val lines = text.split('\n').let { if (keepNewest) it.asReversed() else it }
        var used = 0
        var count = 0
        for (line in lines) {
            val cost = utf8Size(line) + 1
            if (used + cost > budget) break
            used += cost
            count++
        }
        val kept = lines.take(count)
        return (if (keepNewest) kept.asReversed() else kept).joinToString("\n")
    }

    private fun utf8Size(s: String): Int = s.toByteArray(Charsets.UTF_8).size

    private fun render(parts: ReportParts): String {
        val bodies = listOf(parts.thisTv, parts.app, parts.lastCloses, parts.playbackErrors, parts.settings, parts.appLog)
        return buildString {
            append(FIRST_LINE).append('\n')
            TITLES.zip(bodies).forEach { (title, body) ->
                append('\n').append("== ").append(title).append(" ==").append('\n')
                if (body.isNotEmpty()) append(body).append('\n')
            }
        }
    }
}
