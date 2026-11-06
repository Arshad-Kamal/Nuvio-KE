package com.nuvio.tv.ui.util

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.core.util.isEpisodeReleaseAired
import com.nuvio.tv.core.util.parseEpisodeReleaseLocalDate
import com.nuvio.tv.domain.model.ContentType
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

internal fun parseEpisodeReleaseDate(raw: String?): LocalDate? {
    return parseEpisodeReleaseLocalDate(raw)
}

internal fun computeAirDateBadgeText(
    context: Context,
    releasedIso: String?,
    airDateLabel: String?
): String? {
    if (releasedIso.isNullOrBlank()) {
        return airDateLabel?.let { context.getString(R.string.cw_airs_date, it) }
    }
    if (isEpisodeReleaseAired(releasedIso) == true) return null

    // Parse failure falls through to the label branch instead of dropping the badge.
    val releaseDate = parseEpisodeReleaseDate(releasedIso) ?: return airDateLabel
        ?.let { context.getString(R.string.cw_airs_date, it) }
    val today = LocalDate.now(ZoneId.systemDefault())
    val daysUntil = ChronoUnit.DAYS.between(today, releaseDate)

    return when {
        daysUntil < 0 -> null
        daysUntil == 0L -> context.getString(R.string.cw_airs_today)
        daysUntil == 1L -> context.getString(R.string.cw_airs_tomorrow)
        daysUntil in 2..7 -> context.resources.getQuantityString(
            R.plurals.cw_airs_in_days,
            daysUntil.toInt(),
            daysUntil.toInt()
        )
        else -> airDateLabel?.let { context.getString(R.string.cw_airs_date, it) }
    }
}

/**
 * Year/full-date text for a card or hero: for movies with a parseable
 * `released` date, a locale-formatted full date ("15 March 2024"); otherwise
 * the releaseInfo year or range, trimmed as given.
 *
 * G6 consolidation home (2026-08-30): the body previously lived in
 * ModernHomeModels (used by ModernHomeContent/Models); HeroSection carried a
 * hand-rolled twin of the whole block (private formatYearRange +
 * inline SimpleDateFormat) WITHOUT the LocalDate fallback — adopting this home
 * gives it #2516's "2024-03-15" support and the dMMMMy pattern cache. The
 * SimpleDateFormat-vs-DateTimeFormatter choice is load-bearing (inflected
 * month forms, e.g. Polish genitive) — see the comment inside.
 */
internal fun extractYearText(
    type: ContentType,
    releaseInfo: String?,
    released: String?,
    showFullDate: Boolean = true
): String? {
    if (showFullDate && type == ContentType.MOVIE) {
        val full = released
            ?.let {
                // Try OffsetDateTime first (addon format: "2024-03-15T00:00:00.000Z"),
                // then LocalDate (TMDB collection format: "2024-03-15"). (#2516)
                runCatching { java.time.OffsetDateTime.parse(it).toLocalDate() }.getOrNull()
                    ?: runCatching { java.time.LocalDate.parse(it) }.getOrNull()
            }
            ?.let {
                val locale = java.util.Locale.getDefault()
                val pattern = if (locale == cachedDateFormatLocale && cachedDateFormatPattern != null) {
                    cachedDateFormatPattern!!
                } else {
                    android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMMMy").also { p ->
                        cachedDateFormatPattern = p
                        cachedDateFormatLocale = locale
                    }
                }
                // Use SimpleDateFormat (not DateTimeFormatter): DateTimeFormatter.ofPattern
                // interprets MMMM as the standalone month form in some locales (e.g.
                // Polish "czerwiec" instead of the genitive "czerwca" used in full dates),
                // while SimpleDateFormat uses the inflected form expected in date context.
                // Episode release dates (formatReleaseDate, ui/screens/detail/DateFormat.kt)
                // still use DateTimeFormatter and a different parser, so the two are not
                // guaranteed identical for the same date and locale.
                java.text.SimpleDateFormat(pattern, locale)
                    .format(java.util.Date(it.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()))
            }
        if (full != null) return full
    }
    return extractYearOrRange(releaseInfo)
}

/** releaseInfo used as the year text verbatim (trimmed) — "2024" or "2021–2024". */
internal fun extractYearOrRange(releaseInfo: String?): String? {
    if (releaseInfo.isNullOrBlank()) return null
    return releaseInfo.trim()
}

@Volatile
private var cachedDateFormatLocale: java.util.Locale? = null
@Volatile
private var cachedDateFormatPattern: String? = null
