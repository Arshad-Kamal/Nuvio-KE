package com.nuvio.tv.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class CatalogResponseDto(
    @SerialName("metas") val metas: List<MetaPreviewDto?> = emptyList()
)

@Serializable
data class MetaPreviewDto(
    @SerialName("id") val id: String? = null,
    @SerialName("type") val type: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("poster") val poster: String? = null,
    @SerialName("posterShape") val posterShape: String? = null,
    @SerialName("background") val background: String? = null,
    @SerialName("logo") val logo: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("releaseInfo") val releaseInfo: String? = null,
    @SerialName("imdbRating") val imdbRating: String? = null,
    @SerialName("genres") val genres: List<String>? = null,
    @SerialName("runtime") val runtime: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("released") val released: String? = null,
    @SerialName("country") val country: String? = null,
    @SerialName("imdb_id") val imdbId: String? = null,
    @SerialName("slug") val slug: String? = null,
    @SerialName("landscapePoster") val landscapePoster: String? = null,
    @SerialName("_rawPosterUrl") val rawPosterUrl: String? = null,
    @SerialName("director") val director: JsonElement? = null,
    @SerialName("writer") val writer: JsonElement? = null,
    @SerialName("writers") val writers: JsonElement? = null,
    @SerialName("links") val links: List<MetaLinkDto>? = null,
    @SerialName("trailers") val trailers: List<MetaTrailerDto>? = null,
    @SerialName("behaviorHints") val behaviorHints: MetaBehaviorHintsDto? = null,
    @SerialName("trailerStreams") val trailerStreams: List<TrailerStreamDto>? = null
)
