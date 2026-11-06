package com.nuvio.tv.domain.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class Addon(
    val id: String,
    val name: String,
    val displayName: String = name,
    val version: String,
    val description: String?,
    val logo: String?,
    val background: String? = null,
    val baseUrl: String,
    val catalogs: List<CatalogDescriptor>,
    val types: List<ContentType>,
    val rawTypes: List<String> = types.map { it.toApiString() },
    val resources: List<AddonResource>,
    val idPrefixes: List<String> = emptyList(),
    val behaviorHints: AddonBehaviorHints? = null,
    val stremioAddonsConfig: StremioAddonsConfig? = null,
    val manifestLanguage: String? = null,
    val configVersion: Long? = null,
    val timestamp: Long? = null,
    val enabled: Boolean = true
)

fun List<Addon>.enabledAddons(): List<Addon> =
    filter { it.enabled }

private const val AIOSTREAMS_MARKER = "aiostreams"

/**
 * True for an AIOStreams instance (X1, QU5-2). Its manifest id defaults to
 * `com.aiostreams.viren070.<uuid12>[.<hash>]` and its name to "AIOStreams", each
 * overridable per instance, so either one naming it counts. Raw manifest [Addon.name],
 * not the user's [Addon.displayName]. A miss only sends the stream request without the
 * AIO User-Agent, so the response carries no streamData.
 */
fun Addon.isAioStreams(): Boolean =
    id.contains(AIOSTREAMS_MARKER, ignoreCase = true) ||
        name.contains(AIOSTREAMS_MARKER, ignoreCase = true)

@Immutable
@Serializable
data class CatalogDescriptor(
    val type: ContentType,
    val rawType: String = type.toApiString(),
    val id: String,
    val name: String,
    val extra: List<CatalogExtra> = emptyList(),
    val pageSize: Int? = null,
    val showInHome: Boolean = false,
    val hasExplicitShowInHome: Boolean = false,
    val extraSupported: List<String> = emptyList(),
    val extraRequired: List<String> = emptyList()
) {
    val apiType: String
        get() = type.toApiString(rawType)
}

@Immutable
@Serializable
data class CatalogExtra(
    val name: String,
    val isRequired: Boolean = false,
    val options: List<String>? = null,
    val defaultValue: String? = null,
    val optionsLimit: Int? = null
)

@Immutable
@Serializable
data class AddonResource(
    val name: String,
    val types: List<String>,
    val idPrefixes: List<String>?
)

@Immutable
@Serializable
data class AddonBehaviorHints(
    val configurable: Boolean? = null,
    val configurationRequired: Boolean? = null,
    val newEpisodeNotifications: Boolean? = null,
    val p2p: Boolean? = null,
    val adult: Boolean? = null
)

@Immutable
@Serializable
data class StremioAddonsConfig(
    val issuer: String? = null,
    val signature: String? = null
)
