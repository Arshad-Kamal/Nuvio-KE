package com.nuvio.tv.ui.util

import com.nuvio.tv.core.util.parseRuntimeMinutes

/**
 * Runtime text as "1h 32m" / "45m". Addon text is untrusted: what [parseRuntimeMinutes] cannot
 * read (an overlong number, "n/a") passes through unformatted.
 */
fun formatHeroRuntime(runtime: String?): String? {
    if (runtime.isNullOrBlank()) return null
    return formatRuntimeMinutes(parseRuntimeMinutes(runtime) ?: return runtime)
}

/** Minutes as "1h 32m" / "2h" / "45m"; null for 0 or less. */
fun formatRuntimeMinutes(totalMinutes: Int): String? {
    // T9 (0.8.12 relay): TMDB answers with runtime 0, not null, for a title whose
    // length it does not know yet — common for anything newly added. Formatting that
    // produced a literal "0m" next to the genre. No runtime is the honest reading of
    // zero, and callers already drop a null.
    if (totalMinutes <= 0) return null

    val wholeHours = totalMinutes / 60
    val remainingMinutes = totalMinutes % 60
    return when {
        wholeHours > 0 && remainingMinutes > 0 -> "${wholeHours}h ${remainingMinutes}m"
        wholeHours > 0 -> "${wholeHours}h"
        else -> "${remainingMinutes}m"
    }
}
