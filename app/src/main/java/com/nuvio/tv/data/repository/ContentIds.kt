package com.nuvio.tv.data.repository

/** The TMDB id in a content id ("tmdb:N" or a bare number), or null. */
internal fun parseTmdbId(contentId: String): Int? {
    val raw = contentId.trim()
    return if (raw.startsWith("tmdb:", ignoreCase = true)) {
        raw.substringAfter(':').toIntOrNull()
    } else {
        raw.toIntOrNull()
    }
}
