package com.nuvio.tv.core.report

import com.nuvio.tv.core.diagnostics.AbnormalExitKind

/**
 * Spec 116 B1: the short line under each part in the panel, from the builder's structured data (never
 * parsed from the scrubbed text). Shown on the TV only, never uploaded. [shownIn] keeps a value only
 * when its scrubbed part still carries it, so the line never shows what the scrub removed or the
 * trim cut.
 */
data class ReportSummary(
    val tv: TvSummary = TvSummary(),
    val app: AppSummary = AppSummary(),
    /** Abnormal process ends, newest first. */
    val closes: List<CloseSummary> = emptyList(),
    /** Failure boxes, newest first. */
    val errors: List<ErrorSummary> = emptyList(),
    /** The Playback settings part's own lines (D3.5). */
    val settings: List<SettingLine> = emptyList(),
    val log: LogSummary = LogSummary(),
) {
    /** This summary without any value its part in [parts] no longer carries; [log] from the final App log. */
    fun shownIn(parts: ReportParts, logTrimmed: Boolean): ReportSummary {
        fun String.has(line: String) = line in lineSequence()
        return ReportSummary(
            tv = TvSummary(
                model = tv.model?.takeIf { parts.thisTv.has("model = $it") },
                android = tv.android?.takeIf { a -> parts.thisTv.lineSequence().any { it.startsWith("android = $a (") } },
                ramMb = tv.ramMb?.takeIf { parts.thisTv.has("ram_mb = $it") },
                dolbyVision = tv.dolbyVision && parts.thisTv.has("dolby_vision = true"),
            ),
            app = AppSummary(
                versionName = app.versionName?.takeIf { parts.app.has("version = $it (${app.versionCode})") },
                versionCode = app.versionCode,
                build = app.build?.takeIf { parts.app.has("build = $it") },
                abi = app.abi?.takeIf { parts.app.has("abi = $it") },
            ),
            // Kept while its header line survived the scrub and any cut (an exit line may go on with its description).
            closes = closes.filter { c ->
                c.header.isNotEmpty() && parts.lastCloses.lineSequence().any { it == c.header || it.startsWith(c.header + " ") }
            },
            // One line per record in the same order (newest first); a trim keeps the first lines.
            errors = errors.zip(parts.playbackErrors.lines().takeIf { parts.playbackErrors != NONE }.orEmpty())
                .filter { (e, line) -> "cause=${e.cause}" in line.split(' ') }
                .map { it.first },
            settings = settings.filter { parts.settings.has("${it.key} = ${it.value}") },
            log = LogSummary(
                lines = if (parts.appLog.isEmpty()) 0 else parts.appLog.count { it == '\n' } + 1,
                bytes = parts.appLog.toByteArray(Charsets.UTF_8).size,
                trimmed = logTrimmed,
            ),
        )
    }

    companion object {
        /** An empty part's text (ReportBuilder). */
        const val NONE = "none"
        private const val SAME_CRASH_MS = 60_000L
        private val ABNORMAL = AbnormalExitKind.entries.map { it.label }.toSet()

        /**
         * The abnormal ends among [exits] (Android's records; normal ends dropped) and this app's crash
         * files ([crashFiles]), newest first. On API 30+ one crash is in both: a crash file within a
         * minute of a `crash` record is that record.
         */
        fun closes(exits: List<CloseSummary>, crashFiles: List<CloseSummary>): List<CloseSummary> {
            val abnormal = exits.filter { it.kind in ABNORMAL }
            val crashes = abnormal.filter { it.kind == AbnormalExitKind.CRASH.label }.map { it.timeMs }
            val own = crashFiles.filter { f -> crashes.none { kotlin.math.abs(it - f.timeMs) <= SAME_CRASH_MS } }
            return (abnormal + own).sortedByDescending { it.timeMs }
        }
    }
}

data class TvSummary(
    val model: String? = null,
    /** Build.VERSION.RELEASE. */
    val android: String? = null,
    val ramMb: Long? = null,
    val dolbyVision: Boolean = false,
)

data class AppSummary(
    val versionName: String? = null,
    val versionCode: Int? = null,
    /** "public", "household" or "debug". */
    val build: String? = null,
    val abi: String? = null,
)

/** [kind]: an [AbnormalExitKind] label; [header]: the start of its first line in Last closes, the part the scrub leaves alone. */
data class CloseSummary(val kind: String, val timeMs: Long, val header: String = "")

/** [cause]: a `FailureCause` name. */
data class ErrorSummary(val cause: String, val timeMs: Long)

/** The final App log: its line count and UTF-8 size; [trimmed] = older lines were cut to fit the cap. */
data class LogSummary(val lines: Int = 0, val bytes: Int = 0, val trimmed: Boolean = false)
