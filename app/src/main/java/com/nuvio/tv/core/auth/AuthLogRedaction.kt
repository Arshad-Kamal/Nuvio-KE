package com.nuvio.tv.core.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Auth response bodies can carry access/refresh tokens; AuthManager's error-body debug
// log line runs through here first. Moved out of the removed auth-diagnostics uploader.
private const val EXCLUDED_CREDENTIAL = "[excluded-credential]"
private val credentialKeyRegex = Regex("(authorization|cookie|token|secret|password|apikey|api_key|accesskey|session|refresh|bearer)", RegexOption.IGNORE_CASE)
private val credentialJsonValueRegex = Regex("(\"[^\"]*(?:authorization|cookie|token|secret|password|apikey|api_key|accesskey|session|refresh|bearer)[^\"]*\"\\s*:\\s*)(\"(?:\\\\.|[^\"])*\"|[^,}\\]]+)", setOf(RegexOption.IGNORE_CASE))
private val redactionJson = Json { ignoreUnknownKeys = true }

/** Replaces credential-named JSON values; non-JSON bodies fall back to a regex pass. */
internal fun redactAuthCredentials(body: String?): String? {
    if (body == null) return null
    if (body.isBlank()) return body
    val parsed = runCatching { redactionJson.parseToJsonElement(body) }.getOrNull()
    if (parsed != null) return redactJsonElement(parsed).toString()
    return credentialJsonValueRegex.replace(body) { match -> "${match.groupValues[1]}\"$EXCLUDED_CREDENTIAL\"" }
}

private fun redactJsonElement(element: JsonElement, key: String? = null): JsonElement {
    if (key != null && credentialKeyRegex.containsMatchIn(key)) return JsonPrimitive(EXCLUDED_CREDENTIAL)
    return when (element) {
        is JsonObject -> JsonObject(element.mapValues { (childKey, childValue) -> redactJsonElement(childValue, childKey) })
        is JsonArray -> JsonArray(element.map { child -> redactJsonElement(child) })
        else -> element
    }
}
