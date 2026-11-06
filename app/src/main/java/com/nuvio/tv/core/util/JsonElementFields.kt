package com.nuvio.tv.core.util

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Gson-cast-parity field extraction from untyped [kotlinx.serialization.json.JsonElement]
 * trees. One home for the predicate family (json-consolidation): quoted scalars are
 * not booleans, unquoted scalars are not strings, JSON null is absent — the exact
 * semantics of Gson's `as? String` / `as? Boolean` / element-wise `as? String`
 * casts on a parsed map, which lenient readers rely on.
 */

/** String elements of a JSON array in order; non-string elements are dropped. */
fun JsonArray.stringElements(): List<String> =
    mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }

/** JSON string field; null when absent, null, or not a quoted string. */
fun JsonObject?.stringField(name: String): String? =
    (this?.get(name) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/** JSON boolean field; null when absent, null, or a quoted/unquoted non-boolean. */
fun JsonObject?.booleanField(name: String): Boolean? =
    (this?.get(name) as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

/**
 * Numeric field as Long, unquoted only — Gson's `as? Number`?.toLong() semantics
 * (123 and 123.0 both yield 123; a quoted "123" is not a number).
 */
fun JsonObject?.numberField(name: String): Long? {
    val primitive = (this?.get(name) as? JsonPrimitive)?.takeIf { !it.isString } ?: return null
    return primitive.contentOrNull?.toLongOrNull()
        ?: primitive.contentOrNull?.toDoubleOrNull()?.toLong()
}
