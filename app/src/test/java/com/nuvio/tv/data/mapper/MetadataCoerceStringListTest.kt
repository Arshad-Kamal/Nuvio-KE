package com.nuvio.tv.data.mapper

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Moshi retirement M4-B: parity pins for coerceStringList over JsonElement —
 * the Any?-era semantics (string / string-list / {name} object / {name} list)
 * preserved exactly; numbers, booleans, nulls and nested shapes contribute
 * nothing, exactly as the Moshi reflective reader's typed views did.
 */
class MetadataCoerceStringListTest {

    @Test
    fun `string and string-list shapes coerce`() {
        assertEquals(listOf("Dir A"), coerceStringList(JsonPrimitive("Dir A")))
        assertEquals(
            listOf("A", "B"),
            coerceStringList(JsonArray(listOf(JsonPrimitive("A"), JsonPrimitive("B"))))
        )
    }

    @Test
    fun `name objects and mixed lists coerce`() {
        assertEquals(
            listOf("A", "B"),
            coerceStringList(
                JsonArray(
                    listOf(
                        buildJsonObject { put("name", "A") },
                        buildJsonObject { put("name", "B") }
                    )
                )
            )
        )
        assertEquals(
            listOf("keep"),
            coerceStringList(
                JsonArray(
                    listOf(
                        JsonPrimitive("keep"),
                        JsonPrimitive(42),                       // number entry — dropped
                        buildJsonObject { put("name", 7) },     // non-string name — dropped
                        buildJsonObject { put("other", "x") }   // no name — dropped
                    )
                )
            )
        )
        assertEquals(listOf("Solo"), coerceStringList(buildJsonObject { put("name", "Solo") }))
    }

    @Test
    fun `non-string scalars and blanks contribute nothing`() {
        assertEquals(emptyList<String>(), coerceStringList(JsonPrimitive(5)))
        assertEquals(emptyList<String>(), coerceStringList(JsonPrimitive(true)))
        assertEquals(emptyList<String>(), coerceStringList(JsonPrimitive("   ")))
        assertEquals(emptyList<String>(), coerceStringList(buildJsonObject { put("name", "  ") }))
        assertEquals(emptyList<String>(), coerceStringList(null))
        assertEquals(emptyList<String>(), coerceStringList(kotlinx.serialization.json.JsonNull))
    }
}
