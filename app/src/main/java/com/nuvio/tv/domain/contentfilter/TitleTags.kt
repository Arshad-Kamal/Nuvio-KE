package com.nuvio.tv.domain.contentfilter

/** A title's TMDB tags, as the content-filter matcher reads them (Spec 117 P3/P4). */
data class TitleTags(
    val genreIds: Set<Int>,
    val keywordIds: Set<Int>,
    /** Keyword names, lowercased, in the same order as [keywordIds]. */
    val keywordNames: List<String>,
    val originalLanguage: String?,
    val adult: Boolean
)
