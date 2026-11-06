package com.nuvio.tv.data.local

import kotlinx.serialization.ExperimentalSerializationApi
import com.nuvio.tv.domain.model.Addon
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Shared kotlinx JSON home for the persisted DataStore string slots
 * (json-consolidation Group A). One home per codec so every writer and
 * reader of a slot agrees.
 *
 * Reads must accept everything Gson ever wrote for these keys: standard
 * JSON (Gson's \uXXXX escapes decode identically here) and objects whose
 * optional fields are absent (Gson omits nulls), hence ignoreUnknownKeys
 * + explicitNulls=false. encodeDefaults=true keeps writes field-complete
 * like Gson's output. Decoders return null on malformed input so callers
 * keep their fallback-to-default paths.
 *
 * Also used for element-tree parsing of foreign YouTube player responses
 * (InAppYouTubeExtractor) - config changes here reach that scraper too.
 */
@OptIn(ExperimentalSerializationApi::class)
internal val localStoreJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

internal fun encodeStringList(values: List<String>): String =
    JsonArray(values.map(::JsonPrimitive)).toString()

internal fun encodeStringMap(values: Map<String, String>): String = buildJsonObject {
    values.forEach { (key, value) -> put(key, value) }
}.toString()

/**
 * A user-set name map after naming [key] [name]: trimmed; blank, or equal to [original] (the
 * item's own name), removes the entry so the item shows its own name. One rule for addon names
 * and custom catalog titles.
 */
internal fun Map<String, String>.withCustomName(key: String, name: String, original: String): Map<String, String> {
    val trimmed = name.trim()
    return if (trimmed.isEmpty() || trimmed == original.trim()) this - key else this + (key to trimmed)
}

internal fun encodeBooleanMap(values: Map<String, Boolean>): String = buildJsonObject {
    values.forEach { (key, value) -> put(key, value) }
}.toString()

internal fun encodeLongMap(values: Map<String, Long>): String = buildJsonObject {
    values.forEach { (key, value) -> put(key, value) }
}.toString()

internal fun decodeStringList(json: String): List<String>? =
    runCatching { localStoreJson.decodeFromString(ListSerializer(String.serializer()), json) }.getOrNull()

internal fun decodeStringMap(json: String): Map<String, String>? =
    runCatching {
        localStoreJson.decodeFromString(
            MapSerializer(String.serializer(), String.serializer()),
            json
        )
    }.getOrNull()

internal fun decodeBooleanMap(json: String): Map<String, Boolean>? =
    runCatching {
        localStoreJson.decodeFromString(
            MapSerializer(String.serializer(), Boolean.serializer()),
            json
        )
    }.getOrNull()

internal fun decodeLongMap(json: String): Map<String, Long>? =
    runCatching {
        localStoreJson.decodeFromString(
            MapSerializer(String.serializer(), Long.serializer()),
            json
        )
    }.getOrNull()

/** Manifest disk cache (SharedPreferences slot owned by AddonRepositoryImpl). */
internal fun encodeManifestCache(cache: Map<String, Addon>): String =
    localStoreJson.encodeToString(manifestCacheSerializer, cache)

internal fun decodeManifestCache(json: String): Map<String, Addon>? =
    runCatching { localStoreJson.decodeFromString(manifestCacheSerializer, json) }.getOrNull()

private val manifestCacheSerializer =
    MapSerializer(String.serializer(), Addon.serializer())
