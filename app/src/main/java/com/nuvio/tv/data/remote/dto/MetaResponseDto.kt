package com.nuvio.tv.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class MetaResponseDto(
    @SerialName("meta") val meta: MetaDto? = null
)

@Serializable
data class MetaDto(
    @SerialName("id") val id: String,
    @SerialName("type") val type: String,
    @SerialName("name") val name: String,
    @SerialName("poster") val poster: String? = null,
    @SerialName("posterShape") val posterShape: String? = null,
    @SerialName("background") val background: String? = null,
    @SerialName("logo") val logo: String? = null,
    @SerialName("landscapePoster") val landscapePoster: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("releaseInfo") val releaseInfo: String? = null,
    @SerialName("released") val released: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("imdbRating") val imdbRating: String? = null,
    @SerialName("imdb_id") val imdbId: String? = null,
    @SerialName("slug") val slug: String? = null,
    @SerialName("genres") val genres: List<String>? = null,
    @SerialName("runtime") val runtime: String? = null,
    // Stremio addons are inconsistent here (string vs list). Keep it tolerant.
    @SerialName("director") val director: JsonElement? = null,
    // Addons are inconsistent: may be `writer` (string/list) or `writers`.
    // JsonElement (Moshi-era Any?): foreign addon JSON, string-or-array shapes.
    @SerialName("writer") val writer: JsonElement? = null,
    @SerialName("writers") val writers: JsonElement? = null,
    @SerialName("cast") val cast: JsonElement? = null,
    @SerialName("videos") val videos: List<VideoDto>? = null,
    @SerialName("country") val country: String? = null,
    @SerialName("awards") val awards: String? = null,
    @SerialName("language") val language: String? = null,
    @SerialName("links") val links: List<MetaLinkDto>? = null,
    @SerialName("trailers") val trailers: List<MetaTrailerDto>? = null,
    @SerialName("behaviorHints") val behaviorHints: MetaBehaviorHintsDto? = null,
    @SerialName("trailerStreams") val trailerStreams: List<TrailerStreamDto>? = null,
    @SerialName("app_extras") val appExtras: AppExtrasDto? = null,
    @SerialName("_rawPosterUrl") val rawPosterUrl: String? = null,
    @SerialName("_hasPoster") val hasPoster: Boolean? = null,
    @SerialName("_hasBackground") val hasBackground: Boolean? = null,
    @SerialName("_hasLandscapePoster") val hasLandscapePoster: Boolean? = null,
    @SerialName("_hasLogo") val hasLogo: Boolean? = null,
    @SerialName("_hasLinks") val hasLinks: Boolean? = null,
    @SerialName("_hasVideos") val hasVideos: Boolean? = null
)

@Serializable
data class TrailerStreamDto(
    @SerialName("ytId") val ytId: String? = null
)

@Serializable
data class AppExtrasDto(
    @SerialName("cast") val cast: List<AppExtrasCastMemberDto>? = null,
    @SerialName("directors") val directors: List<AppExtrasCastMemberDto>? = null,
    @SerialName("writers") val writers: List<AppExtrasCastMemberDto>? = null,
    @SerialName("releaseDates") val releaseDates: MetaReleaseDatesEnvelopeDto? = null,
    @SerialName("certification") val certification: String? = null
)

@Serializable
data class AppExtrasCastMemberDto(
    // Nested fields from untrusted addons are nullable with defaults so one malformed
    // element is dropped by the mapper instead of failing the whole meta decode.
    @SerialName("name") val name: String? = null,
    @SerialName("character") val character: String? = null,
    @SerialName("photo") val photo: String? = null,
    @SerialName("tmdbId") val tmdbId: Int? = null
)

@Serializable
data class VideoDto(
    // Nullable: an entry without an id is dropped by the mapper (see AppExtrasCastMemberDto).
    @SerialName("id") val id: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("released") val released: String? = null,
    @SerialName("thumbnail") val thumbnail: String? = null,
    @SerialName("streams") val streams: List<StreamDto>? = null,
    @SerialName("season") val season: Int? = null,
    @SerialName("episode") val episode: Int? = null,
    @SerialName("number") val number: Int? = null,
    @SerialName("overview") val overview: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("runtime") val runtime: String? = null,
    @SerialName("rating") val rating: String? = null,
    @SerialName("available") val available: Boolean? = null
)

@Serializable
data class MetaLinkDto(
    // Nullable: a link missing either is dropped by the mapper. Also encoded into the
    // catalog disk cache (wireJson, explicitNulls = false): a non-null value encodes
    // exactly as before.
    @SerialName("name") val name: String? = null,
    @SerialName("category") val category: String? = null,
    @SerialName("url") val url: String? = null
)

@Serializable
data class MetaTrailerDto(
    @SerialName("source") val source: String? = null,
    @SerialName("type") val type: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("ytId") val ytId: String? = null,
    @SerialName("lang") val lang: String? = null
)

@Serializable
data class MetaBehaviorHintsDto(
    @SerialName("defaultVideoId") val defaultVideoId: String? = null,
    @SerialName("hasScheduledVideos") val hasScheduledVideos: Boolean? = null
)

@Serializable
data class MetaReleaseDatesEnvelopeDto(
    @SerialName("results") val results: List<MetaReleaseDateCountryDto>? = null
)

@Serializable
data class MetaReleaseDateCountryDto(
    // Nullable: an entry without a country is dropped by the mapper.
    @SerialName("iso_3166_1") val iso31661: String? = null,
    @SerialName("release_dates") val releaseDates: List<MetaReleaseDateDto>? = null
)

@Serializable
data class MetaReleaseDateDto(
    @SerialName("certification") val certification: String? = null,
    @SerialName("descriptors") val descriptors: List<String>? = null,
    @SerialName("iso_639_1") val iso6391: String? = null,
    @SerialName("note") val note: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("type") val type: Int? = null
)
