package com.nuvio.tv.core.server

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Shared JSON plumbing for the LAN config servers (kotlinx.serialization only).
 *
 * encodeDefaults mirrors Gson's field-complete output: the served web pages read
 * plain fields without default guards, so every non-null field must be emitted
 * and nulls omitted (explicitNulls = false). ignoreUnknownKeys keeps POST
 * decoding tolerant of bodies carrying extra keys.
 */
@OptIn(ExperimentalSerializationApi::class)
internal val configServerJson: Json = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
}

/** Single-field JSON body, e.g. {"status":"saved"} / {"error":"..."} responses. */
internal fun jsonMessage(field: String, value: String): String =
    buildJsonObject { put(field, value) }.toString()

/** Parses a POST body to a JsonObject; null for empty, malformed, or non-object bodies. */
internal fun parseJsonBody(body: String): JsonObject? = runCatching {
    if (body.isBlank()) return@runCatching null
    configServerJson.parseToJsonElement(body).jsonObject
}.getOrNull()

// Field-extraction predicates (Gson-cast parity) live in core/util
// JsonElementFields.kt — one home shared with the persisted-store readers.
