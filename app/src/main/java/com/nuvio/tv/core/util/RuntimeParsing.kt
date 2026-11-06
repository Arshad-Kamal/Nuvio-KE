package com.nuvio.tv.core.util

// Hours may be decimal ("1.5h") and may carry a bare minute count after them ("1h30"); the
// lookbehinds keep "1.5h" from matching as "5h".
private val RUNTIME_HOURS = Regex("(?<![\\d.])(\\d+(?:\\.\\d+)?)\\s*h[a-z]*\\s*(\\d+)?")
private val RUNTIME_MINUTES = Regex("(?<![\\d.])(\\d+)\\s*m")
private val RUNTIME_CLOCK = Regex("^(\\d+):(\\d{1,2})(?::\\d{1,2})?$")
private val RUNTIME_DIGITS = Regex("\\d+")

/**
 * Addon `runtime` text as whole minutes: "130", "130 min", "2h 10min", "1h", and the clock forms
 * "1:32" / "1:32:05" (H:MM[:SS], seconds ignored); "1h30" and "1.5h" are 90. Without a unit or
 * clock form the first digit run wins ("approx. 95" -> 95). Null when blank or unreadable (a
 * total that does not fit an Int included), 0 when the source says 0.
 *
 * The one parser for meta runtimes (Spec 21 B2): the data mapper, watch progress, the external
 * player tracker, the internal player and the Detail hero used two copies that disagreed on
 * "1:30" (1 vs 90 minutes).
 */
internal fun parseRuntimeMinutes(raw: String?): Int? {
    val text = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    val hoursMatch = RUNTIME_HOURS.find(text)
    val minutesMatch = RUNTIME_MINUTES.find(text)
    if (hoursMatch != null || minutesMatch != null) {
        val hourMinutes = hoursMatch?.groupValues?.get(1)?.toDoubleOrNull()?.times(60) ?: 0.0
        val minutes = minutesMatch?.groupValues?.get(1)?.toDoubleOrNull()
            ?: hoursMatch?.groupValues?.get(2)?.toDoubleOrNull()
            ?: 0.0
        return (hourMinutes + minutes).toIntMinutesOrNull()
    }
    RUNTIME_CLOCK.find(text)?.groupValues?.let { clock ->
        val clockHours = clock[1].toDoubleOrNull() ?: return null
        val clockMinutes = clock[2].toDoubleOrNull() ?: return null
        return (clockHours * 60 + clockMinutes).toIntMinutesOrNull()
    }
    return RUNTIME_DIGITS.find(text)?.value?.toIntOrNull()
}

private fun Double.toIntMinutesOrNull(): Int? =
    if (isFinite() && this <= Int.MAX_VALUE) kotlin.math.round(this).toInt() else null
