package com.nuvio.tv.core.report

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Spec 116 D3.6: keeps the last 24 hours of a `logcat -v threadtime` dump, cut in Kotlin (no
 * reliance on `-T`). Pure: [now] and [zone] are injected.
 *
 * threadtime has no year: a line is dated in [now]'s year, or the year before when that would put
 * it more than an hour after [now] (a log read on 1 January holds 31 December; the hour absorbs a
 * clock step between the log line and the read). A line with no timestamp (a
 * `--------- beginning of main` marker, a wrapped message) shares the previous line's verdict.
 */
object LogcatParser {

    private val STAMP = Regex("""^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d{3})""")
    private val WINDOW: Duration = Duration.ofHours(24)
    private val SKEW: Duration = Duration.ofHours(1)

    fun lastDay(log: String, now: Instant, zone: ZoneId): String {
        val cutoff = now.minus(WINDOW)
        val year = now.atZone(zone).year
        var keep = false
        return log.lineSequence()
            .filter { line ->
                val time = timeOf(line, year, now, zone)
                if (time != null) keep = !time.isBefore(cutoff)
                keep && line.isNotEmpty()
            }
            .joinToString("\n")
    }

    /** The line's instant, or null for a line with no timestamp; an impossible date (29 Feb) is old. */
    private fun timeOf(line: String, year: Int, now: Instant, zone: ZoneId): Instant? {
        val m = STAMP.find(line) ?: return null
        val (month, day, hour, minute, second, milli) = m.destructured
        fun at(y: Int): Instant? = runCatching {
            LocalDateTime.of(y, month.toInt(), day.toInt(), hour.toInt(), minute.toInt(), second.toInt(), milli.toInt() * 1_000_000)
                .atZone(zone).toInstant()
        }.getOrNull()
        val sameYear = at(year) ?: return Instant.EPOCH
        return if (sameYear.isAfter(now.plus(SKEW))) at(year - 1) ?: Instant.EPOCH else sameYear
    }
}
