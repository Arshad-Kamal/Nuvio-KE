package com.nuvio.tv.data.repository

import com.nuvio.tv.domain.model.Addon

/**
 * Types to try, in preference order, when routing a stream or subtitle request
 * to an addon. Some anime metadata addons type items `anime.movie` /
 * `anime.series`, which stream and subtitle addons declare as `movie` /
 * `series` (or `anime`); an exact match on the raw type always wins, and the
 * matched candidate is also the type sent in the request URL.
 */
internal fun addonRequestTypeCandidates(type: String): List<String> {
    val baseType = when (type.trim().lowercase()) {
        "anime.movie" -> "movie"
        "anime.series" -> "series"
        else -> return listOf(type)
    }
    return listOf(type, baseType, "anime")
}

/**
 * The type to request streams with from this addon for the given type and
 * video id, or null when it has no matching stream resource. Tries the
 * [addonRequestTypeCandidates] in order (exact type first). Respects the
 * resource-level idPrefixes declared in the addon manifest, falling back to
 * the top-level addon idPrefixes if the resource doesn't declare its own.
 * One home for stream-addon eligibility: StreamRepositoryImpl routes requests
 * with it and the stream/player source chips use it, so a chip exists exactly
 * for the addons that are queried.
 */
internal fun Addon.streamRequestType(type: String, videoId: String): String? =
    addonRequestTypeCandidates(type).firstOrNull { candidate ->
        resources.any { resource ->
            resource.name == "stream" &&
                (resource.types.isEmpty() || resource.types.contains(candidate)) &&
                run {
                    val prefixes = resource.idPrefixes?.takeIf { it.isNotEmpty() }
                        ?: idPrefixes.takeIf { it.isNotEmpty() }
                    prefixes == null || prefixes.any { prefix -> videoId.startsWith(prefix) }
                }
        }
    }
