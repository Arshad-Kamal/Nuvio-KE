package com.nuvio.tv.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class MetaPreview(
    val id: String,
    val type: ContentType,
    val rawType: String = type.toApiString(),
    val name: String,
    val poster: String?,
    val posterShape: PosterShape,
    val background: String?,
    val logo: String?,
    val description: String?,
    val releaseInfo: String?,
    val imdbRating: Float?,
    val genres: List<String>,
    val runtime: String? = null,
    val status: String? = null,
    val ageRating: String? = null,
    /**
     * Spec 102: the addon's non-US ratings (country -> code), only for a title without a known US
     * [ageRating]; read by the rating limit, never shown.
     */
    val nonUsRatings: Map<String, String> = emptyMap(),
    val language: String? = null,
    val released: String? = null,
    val country: String? = null,
    val imdbId: String? = null,
    val slug: String? = null,
    val landscapePoster: String? = null,
    val rawPosterUrl: String? = null,
    val director: List<String> = emptyList(),
    val writer: List<String> = emptyList(),
    val links: List<MetaLink> = emptyList(),
    val behaviorHints: MetaBehaviorHints? = null,
    val trailers: List<MetaTrailer> = emptyList(),
    val trailerYtIds: List<String> = emptyList(),
    val seasonCount: Int? = null,
    /** TMDB vote average (0-10) for TMDB-sourced items; never written into [imdbRating] (26/B2). */
    val tmdbRating: Float? = null,
    /** TMDB vote count, beside [tmdbRating]. */
    val voteCount: Int? = null,
    val sourceAddonBaseUrl: String? = null,
    /**
     * Spec 117 S5: the TMDB genre ids and adult flag a TMDB list row carries, so the content filter can
     * hide a title on them before its tag lookup; empty / false for every other source.
     */
    val tmdbGenreIds: List<Int> = emptyList(),
    val tmdbAdult: Boolean = false,
    /**
     * Spec 122 V3: while previewing a profile's content filters, the ids of every pack that would hide
     * this title (pack order); empty when it is clean or nothing is previewed. Written by the gates only.
     */
    val filterHits: List<String> = emptyList()
) {
    val apiType: String
        get() = type.toApiString(rawType)

    val backdropUrl: String?
        get() = background ?: landscapePoster ?: poster

    /** Real landscape art only (no poster fallback): what a landscape card may keep (T31, P40). */
    val landscapeBackdropUrl: String?
        get() = background?.takeIf { it.isNotBlank() } ?: landscapePoster?.takeIf { it.isNotBlank() }
}
