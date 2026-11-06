package com.nuvio.tv.domain.model

import androidx.compose.runtime.Immutable
import com.nuvio.tv.core.util.languageCodeToName

@Immutable
data class Subtitle(
    val id: String,
    val url: String,
    val lang: String,
    val addonName: String,
    val addonLogo: String?,
    /** Per-subtitle request headers from the addon response; overrides stream headers on download. */
    val headers: Map<String, String>? = null,
    /** The addon's release name for this file (OpenSubtitles movieReleaseName, else its file name). */
    val releaseName: String? = null,
    val releaseGroup: String? = null
) {
    fun getDisplayLanguage(): String = languageCodeToName(lang)

    companion object {
        fun languageCodeToName(code: String): String = com.nuvio.tv.core.util.languageCodeToName(code)
    }
}
