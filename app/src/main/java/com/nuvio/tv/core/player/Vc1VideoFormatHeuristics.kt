package com.nuvio.tv.core.player

import androidx.media3.common.MimeTypes
import java.util.Locale

/** VC-1 / WMV detection shared by track selection and first-frame recovery. */
internal object Vc1VideoFormatHeuristics {
    private val vc1HyphenTokenRegex = Regex("(?<![a-z0-9])vc-1(?![a-z0-9])")
    private val vc1TokenRegex = Regex("(?<![a-z0-9])vc1(?![a-z0-9])")

    fun isLikelyVc1(
        sampleMimeType: String?,
        codecs: String?,
        label: String?,
    ): Boolean {
        if (sampleMimeType?.equals(MimeTypes.VIDEO_VC1, ignoreCase = true) == true) {
            return true
        }

        val haystack = listOfNotNull(codecs, label)
            .joinToString(" ")
            .lowercase(Locale.ROOT)

        return haystack.contains("wvc1") ||
            vc1HyphenTokenRegex.containsMatchIn(haystack) ||
            haystack.contains("wmv3") ||
            vc1TokenRegex.containsMatchIn(haystack)
    }
}
