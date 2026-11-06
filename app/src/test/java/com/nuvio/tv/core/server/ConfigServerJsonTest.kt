package com.nuvio.tv.core.server

import kotlinx.serialization.json.JsonObject
import com.nuvio.tv.core.util.booleanField
import com.nuvio.tv.core.util.numberField
import com.nuvio.tv.core.util.stringField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Gson-cast parity contract of the shared helpers: quoted scalars
 * are not booleans, unquoted scalars are not strings, JSON null is absent,
 * and non-object/blank/malformed bodies parse to null.
 */
class ConfigServerJsonTest {

    @Test
    fun `stringField accepts quoted strings only`() {
        val obj = parseJsonBody("""{"a":"x","b":123,"c":null,"d":true}""")!!

        assertEquals("x", obj.stringField("a"))
        assertNull(obj.stringField("b"))
        assertNull(obj.stringField("c"))
        assertNull(obj.stringField("d"))
        assertNull(obj.stringField("missing"))
    }

    @Test
    fun `booleanField accepts unquoted booleans only`() {
        val obj = parseJsonBody("""{"a":true,"b":"true","c":null,"d":"yes"}""")!!

        assertEquals(true, obj.booleanField("a"))
        assertNull(obj.booleanField("b"))
        assertNull(obj.booleanField("c"))
        assertNull(obj.booleanField("d"))
        assertNull(obj.booleanField("missing"))
    }

    @Test
    fun `numberField accepts unquoted numbers only`() {
        val obj = parseJsonBody("""{"a":123,"b":123.0,"c":"123","d":null}""")!!

        assertEquals(123L, obj.numberField("a"))
        assertEquals(123L, obj.numberField("b"))
        assertEquals(null, obj.numberField("c"))
        assertEquals(null, obj.numberField("d"))
        assertEquals(null, obj.numberField("missing"))
    }

    @Test
    fun `parseJsonBody rejects blank malformed and non-object bodies`() {
        assertNull(parseJsonBody(""))
        assertNull(parseJsonBody("   "))
        assertNull(parseJsonBody("{oops"))
        assertNull(parseJsonBody("[1,2,3]"))
        assertNull(parseJsonBody("\"x\""))
        assertNull(parseJsonBody("42"))
        assertTrue(parseJsonBody("""{"a":1}""") is JsonObject)
    }
}
