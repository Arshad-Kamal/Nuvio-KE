package com.nuvio.tv.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Old-format-read gate for the Addon manifest disk cache: fixture JSON in the
 * exact shape the Gson writer produced (null fields omitted, enums by name)
 * must decode, and round-trip through the new codec must hold.
 */
class ManifestCacheJsonTest {

    // description/logo/background/idPrefixes/behaviorHints omitted = Gson null omission.
    private val gsonEraCache = """
        {"https://v3-cinemeta.strem.io":{
          "id":"com.linvo.cinemeta","name":"Cinemeta","version":"3.0.8",
          "baseUrl":"https://v3-cinemeta.strem.io",
          "catalogs":[{"id":"top","name":"Top Movies","type":"MOVIE","rawType":"movie",
                       "extra":[{"name":"genre","isRequired":false}]}],
          "types":["MOVIE","SERIES"],"rawTypes":["movie","series"],
          "resources":[{"name":"catalog","types":["movie","series"]}],
          "removedInLaterVersion": 1,
          "enabled":true
        }}
    """.trimIndent()

    @Test
    fun `decodes gson-written manifest cache with nulls omitted`() {
        val cached = decodeManifestCache(gsonEraCache)

        assertNotNull(cached)
        val addon = cached?.get("https://v3-cinemeta.strem.io")
        assertEquals("Cinemeta", addon?.name)
        assertEquals(null, addon?.description)
        assertEquals(1, addon?.catalogs?.size)
        assertEquals("genre", addon?.catalogs?.first()?.extra?.first()?.name)
        assertEquals(listOf("movie", "series"), addon?.resources?.first()?.types)
        assertEquals(null, addon?.resources?.first()?.idPrefixes)
        // Parameter-referencing defaults evaluate on decode.
        assertEquals("Cinemeta", addon?.displayName)
        assertEquals(listOf("movie", "series"), addon?.rawTypes)
    }

    @Test
    fun `manifest cache round-trips through the codec`() {
        val decoded = decodeManifestCache(gsonEraCache)!!

        val redecoded = decodeManifestCache(encodeManifestCache(decoded))

        assertEquals(decoded, redecoded)
    }

    @Test
    fun `malformed manifest cache decodes to null`() {
        assertEquals(null, decodeManifestCache("{oops"))
        assertEquals(null, decodeManifestCache(""))
    }
}
