package com.nuvio.tv.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Codec contracts for the persisted DataStore string slots: everything Gson
 * ever wrote for these keys (fixtures below are raw Gson-era shapes, including
 * its \u003d-style HTML escaping) must decode, and round-trips must hold.
 */
class LocalStoreJsonTest {

    @Test
    fun `decodes gson-written string list including html-escaped values`() {
        val legacy = "[\"cinemeta.movie\",\"trakt.trending\\u003dpopular\"]"
        assertEquals(listOf("cinemeta.movie", "trakt.trending=popular"), decodeStringList(legacy))
    }

    @Test
    fun `string list round-trips`() {
        val values = listOf("home_catalog_order_keys", "collection_x", "a=b&c")
        assertEquals(values, decodeStringList(encodeStringList(values)))
    }

    @Test
    fun `decodes gson-written string map`() {
        val legacy = "{\"cinemeta.movie\":\"Featured\",\"k\\u003d\":\"v\\u0026\"}"
        assertEquals(mapOf("cinemeta.movie" to "Featured", "k=" to "v&"), decodeStringMap(legacy))
    }

    @Test
    fun `string map round-trips`() {
        val values = mapOf("cinemeta.movie" to "Featured", "k" to "v")
        assertEquals(values, decodeStringMap(encodeStringMap(values)))
    }

    @Test
    fun `decodes gson-written boolean map`() {
        val legacy = "{\"https://addon.example\":false,\"https://other.example\":true}"
        assertEquals(
            mapOf("https://addon.example" to false, "https://other.example" to true),
            decodeBooleanMap(legacy)
        )
    }

    @Test
    fun `boolean map round-trips`() {
        val values = mapOf("a" to true, "b" to false)
        assertEquals(values, decodeBooleanMap(encodeBooleanMap(values)))
    }

    @Test
    fun `decodes gson-written long map`() {
        val legacy = "{\"tv:123\":1750000000000,\"tv:456\":0}"
        assertEquals(mapOf("tv:123" to 1750000000000L, "tv:456" to 0L), decodeLongMap(legacy))
    }

    @Test
    fun `long map round-trips`() {
        val values = mapOf("tv:123" to 1750000000000L)
        assertEquals(values, decodeLongMap(encodeLongMap(values)))
    }

    @Test
    fun `malformed json decodes to null`() {
        assertNull(decodeStringList("{oops"))
        assertNull(decodeStringList("""[1,2]""")) // numbers are not strings
        assertNull(decodeStringMap("[1,2]"))
        assertNull(decodeStringList(""))
    }

    @Test
    fun `quoted scalars decode leniently for boolean and long maps`() {
        // kotlinx unquotes primitive literals; Gson would have failed these.
        // Gson-written data never contains them (real booleans/longs only), so
        // the leniency is inert on real persisted shapes — recorded as-is.
        assertEquals(mapOf("a" to true), decodeBooleanMap("""{"a":"true"}"""))
        assertEquals(mapOf("a" to 123L), decodeLongMap("""{"a":"123"}"""))
    }
}
