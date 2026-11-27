package com.nuvio.tv.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import com.nuvio.tv.domain.model.ContentRatingCap
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer

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
    @SerialName("trailerStreams") val trailerStreams: List<TrailerStreamDto>? = null,
    @SerialName("app_extras")
    @Serializable(with = CatalogAppExtrasSerializer::class)
    val appExtras: CatalogAppExtrasDto? = null
)

/**
 * The `app_extras` fields a catalog item needs (Spec 64 K1a, Spec 102). Deliberately not [AppExtrasDto]:
 * catalog items also carry the full cast/directors/writers lists (K0: ~2 MB across the household
 * Home pool vs 39 KB for certification alone), which would be decoded for every item and
 * re-encoded into the catalog disk cache. Unknown keys are dropped by the wire Json.
 * [certifications] is kept only for an item without a known US [certification] (see [readCertifications]).
 */
@Serializable
data class CatalogAppExtrasDto(
    @SerialName("certification") val certification: String? = null,
    @SerialName("certifications") val certifications: Map<String, String>? = null
)

/**
 * Untrusted addon input: keeps only a string `certification` and the [readCertifications] map, and
 * turns any other shape (a non-object app_extras, a numeric or array certification) into an empty
 * object, so one odd item never fails the whole catalog decode. Runs on disk-cache decodes too.
 */
internal object CatalogAppExtrasSerializer :
    JsonTransformingSerializer<CatalogAppExtrasDto>(CatalogAppExtrasDto.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        val extras = element as? JsonObject ?: return JsonObject(emptyMap())
        val certification = (extras["certification"] as? JsonPrimitive)?.takeIf { it.isString }
        val certifications = readCertifications(extras["certifications"], certification?.content)
        return JsonObject(
            buildMap {
                certification?.let { put("certification", it) }
                certifications?.let { map -> put("certifications", JsonObject(map.mapValues { JsonPrimitive(it.value) })) }
            }
        )
    }
}

/**
 * The addon's `app_extras.certifications` (country -> code, Spec 102) as a map, or null. Null when
 * [certification] is already a known US code (it decides alone, R1), when the value is not an object,
 * or when any entry is not a string, a code is over [MAX_CERTIFICATION_LENGTH] chars or the map has
 * over [MAX_CERTIFICATIONS] entries: dropping one entry could drop the strictest board, so a bad
 * entry costs the whole map (the title is then unknown, fail closed). A JSON-null entry is dropped.
 * Shared by catalog items and metas.
 */
internal fun readCertifications(element: JsonElement?, certification: String?): Map<String, String>? {
    if (ContentRatingCap.tierOf(certification) != null) return null
    val entries = element as? JsonObject ?: return null
    if (entries.size > MAX_CERTIFICATIONS) return null
    val map = LinkedHashMap<String, String>()
    for ((country, value) in entries) {
        if (value is JsonNull) continue
        val code = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        if (code.length > MAX_CERTIFICATION_LENGTH || country.length > MAX_CERTIFICATION_LENGTH) return null
        map[country] = code
    }
    return map.takeIf { it.isNotEmpty() }
}

private const val MAX_CERTIFICATIONS = 80
private const val MAX_CERTIFICATION_LENGTH = 32
