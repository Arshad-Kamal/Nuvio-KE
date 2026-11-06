package com.nuvio.tv.core.player

import com.nuvio.tv.R
import com.nuvio.tv.data.repository.ParentalGuideResult

private val SEVERITY_ORDER = mapOf("severe" to 0, "moderate" to 1, "mild" to 2)

/**
 * The guide's categories as labelled warnings, most severe first (the player's content-warning
 * overlay and Detail's About sheet). [getString] resolves the `parental_*` labels.
 */
internal fun ParentalGuideResult.toParentalWarnings(getString: (Int) -> String): List<ParentalWarning> =
    listOfNotNull(
        nudity?.let { R.string.parental_nudity to it },
        violence?.let { R.string.parental_violence to it },
        profanity?.let { R.string.parental_profanity to it },
        alcohol?.let { R.string.parental_alcohol to it },
        frightening?.let { R.string.parental_frightening to it }
    )
        .sortedBy { SEVERITY_ORDER[it.second.lowercase()] ?: 3 }
        .map { (label, severity) ->
            val localizedSeverity = when (severity.lowercase()) {
                "severe" -> getString(R.string.parental_severity_severe)
                "moderate" -> getString(R.string.parental_severity_moderate)
                "mild" -> getString(R.string.parental_severity_mild)
                else -> severity
            }
            ParentalWarning(label = getString(label), severity = localizedSeverity)
        }
