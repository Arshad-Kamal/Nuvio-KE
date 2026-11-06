package com.nuvio.tv.data.mapper

import com.nuvio.tv.data.remote.dto.AppExtrasCastMemberDto
import com.nuvio.tv.data.remote.dto.MetaBehaviorHintsDto
import com.nuvio.tv.data.remote.dto.MetaReleaseDateCountryDto
import com.nuvio.tv.data.remote.dto.MetaReleaseDatesEnvelopeDto
import com.nuvio.tv.data.remote.dto.MetaTrailerDto
import com.nuvio.tv.data.remote.dto.TrailerStreamDto
import com.nuvio.tv.domain.model.MetaBehaviorHints
import com.nuvio.tv.domain.model.MetaCastMember
import com.nuvio.tv.domain.model.MetaReleaseDate
import com.nuvio.tv.domain.model.MetaReleaseDateCountry
import com.nuvio.tv.domain.model.MetaTrailer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Foreign addon metadata arrives in mixed shapes (string / list of strings /
 * list of {name} objects / single {name} object). Moshi-era Any? semantics:
 * only STRING primitives and {"name": string} entries produce values; numbers,
 * booleans, nulls and other shapes contribute nothing.
 */
internal fun coerceStringList(value: JsonElement?): List<String> {
    return when (value) {
        null, is JsonNull -> emptyList()
        is JsonPrimitive -> if (value.isString) listOf(value.content) else emptyList()
        is JsonArray -> value.mapNotNull { entry ->
            when (entry) {
                is JsonPrimitive -> entry.content.takeIf { entry.isString }
                is JsonObject -> (entry["name"] as? JsonPrimitive)
                    ?.takeIf { it.isString }?.content
                else -> null
            }
        }
        is JsonObject -> (value["name"] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content
            ?.let { listOf(it) } ?: emptyList()
    }.mapNotNull { it.trim().takeIf(String::isNotBlank) }
}

internal fun mapPeople(
    people: List<AppExtrasCastMemberDto>?,
    roleFallback: String? = null,
    forceRole: Boolean = false
): List<MetaCastMember> {
    return people.orEmpty().mapNotNull { person ->
        val name = person.name?.trim()
        if (name.isNullOrBlank()) return@mapNotNull null
        MetaCastMember(
            name = name,
            character = if (forceRole) roleFallback else person.character?.takeIf { it.isNotBlank() } ?: roleFallback,
            photo = person.photo?.takeIf { it.isNotBlank() },
            tmdbId = person.tmdbId
        )
    }
}

internal fun mapBehaviorHints(dto: MetaBehaviorHintsDto?): MetaBehaviorHints? {
    if (dto == null) return null
    return MetaBehaviorHints(
        defaultVideoId = dto.defaultVideoId?.takeIf { it.isNotBlank() },
        hasScheduledVideos = dto.hasScheduledVideos
    )
}

internal fun mapTrailers(
    trailers: List<MetaTrailerDto>?,
    trailerStreams: List<TrailerStreamDto>?
): List<MetaTrailer> {
    val fromTrailers = trailers.orEmpty().map {
        MetaTrailer(
            source = it.source?.takeIf(String::isNotBlank),
            type = it.type?.takeIf(String::isNotBlank),
            name = it.name?.takeIf(String::isNotBlank),
            ytId = (it.source ?: it.ytId)?.takeIf(String::isNotBlank),
            lang = it.lang?.takeIf(String::isNotBlank)
        )
    }
    val existingYtIds = fromTrailers.mapNotNull { it.ytId }.toSet()
    val fromStreams = trailerStreams.orEmpty().mapNotNull { stream ->
        val ytId = stream.ytId?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        if (ytId in existingYtIds) return@mapNotNull null
        MetaTrailer(ytId = ytId)
    }
    return fromTrailers + fromStreams
}

internal fun mapReleaseDates(
    envelope: MetaReleaseDatesEnvelopeDto?
): List<MetaReleaseDateCountry> {
    return envelope?.results.orEmpty().mapNotNull(::mapReleaseDateCountry)
}

/**
 * Descriptors of the releases that carry [certification]. The addon sends the
 * certification without its country and boards share codes ("PG", "12"), so they
 * count only when every matching release names the same reasons.
 */
internal fun certificationDescriptors(
    certification: String?,
    releaseDates: List<MetaReleaseDateCountry>
): List<String> {
    if (certification == null) return emptyList()
    return releaseDates.asSequence()
        .flatMap { it.releaseDates.asSequence() }
        .filter { it.certification == certification && it.descriptors.isNotEmpty() }
        .map { it.descriptors }
        .distinct()
        .singleOrNull()
        .orEmpty()
}

/** One normalised release entry; shared by the addon (app_extras) and TMDB release dates. */
internal fun metaReleaseDate(
    certification: String?,
    descriptors: List<String>?,
    languageCode: String?,
    note: String?,
    releaseDate: String?,
    type: Int?
) = MetaReleaseDate(
    certification = certification?.trim()?.takeIf(String::isNotBlank),
    descriptors = descriptors.orEmpty().map(String::trim).filter(String::isNotBlank),
    languageCode = languageCode?.takeIf(String::isNotBlank),
    note = note?.takeIf(String::isNotBlank),
    releaseDate = releaseDate?.takeIf(String::isNotBlank),
    type = type
)

private fun mapReleaseDateCountry(dto: MetaReleaseDateCountryDto): MetaReleaseDateCountry? {
    val countryCode = dto.iso31661?.trim()
    if (countryCode.isNullOrBlank()) return null
    return MetaReleaseDateCountry(
        countryCode = countryCode,
        releaseDates = dto.releaseDates.orEmpty().map {
            metaReleaseDate(it.certification, it.descriptors, it.iso6391, it.note, it.releaseDate, it.type)
        }
    )
}
