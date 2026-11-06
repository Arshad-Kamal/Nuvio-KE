package com.nuvio.tv.data.remote.dto

import com.nuvio.tv.core.di.NetworkModule
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Moshi retirement M4-B: decode fixtures for the addon core
 * (catalog/meta/stream/subtitle/manifest) — the foreign-addon surfaces where
 * lenient shapes are load-bearing.
 */
class AddonWireDtoDecodeTest {

    private val json = NetworkModule.provideWireJson()

    @Test
    fun `addon catalog keeps null-entry tolerance`() {
        // Foreign addons emit null entries inside metas; the nullable element
        // type preserves the Moshi-era per-entry skip (not a whole-response
        // garbage classification).
        val payload = """
        {"metas":[{"id":"tt1","type":"series","name":"First"}, null,
                  {"id":" ","type":"series","name":"No id"},
                  {"id":"tt3","type":"series","name":"Third"}],
         "metas_count":15}
        """.trimIndent()
        val decoded = json.decodeFromString<CatalogResponseDto>(payload)
        assertEquals(4, decoded.metas.size)
        assertTrue(decoded.metas[1] == null)
    }

    @Test
    fun `addon meta decodes string-or-array people fields`() {
        val payload = """
        {"id":"tt1","type":"movie","name":"Film","director":"Dir String",
         "writers":["W1","W2"],
         "cast":[{"name":"Actor A"},{"name":"Actor B"}],
         "imdb_id":"tt1","imdbRating":"7.9","runtime":"120",
         "behaviorHints":{},"trailers":[]}
        """.trimIndent()
        val decoded = json.decodeFromString<MetaDto>(payload)
        assertEquals("Film", decoded.name)
        // The JsonElement fields hold the raw shapes; coercion is pinned in
        // MetadataCoerceStringListTest.
        assertEquals("Dir String", decoded.director!!.jsonPrimitive.content)
        assertEquals(2, (decoded.writers as JsonArray).size)
    }

    @Test
    fun `stream response decodes`() {
        val payload = """
        {"streams":[{"name":"Src","title":"4K HDR","url":"https://s/1",
                     "behaviorHints":{"notWebReady":false},"infoHash":"h1"}]}
        """.trimIndent()
        val decoded = json.decodeFromString<StreamResponseDto>(payload)
        assertEquals(1, decoded.streams?.size)
        assertEquals("https://s/1", decoded.streams!![0].url)
    }

    @Test
    fun `addon manifest decodes`() {
        val payload = """
        {"id":"org.addon","version":"1.0.0","name":"Addon","description":"d",
         "resources":[{"name":"catalog","types":["movie","series"],"idPrefixes":["tt"]}],
         "types":["movie","series"],"catalogs":[{"type":"movie","id":"cat","name":"Cat"}]}
        """.trimIndent()
        val decoded = json.decodeFromString<AddonManifestDto>(payload)
        assertEquals("org.addon", decoded.id)
        assertEquals(1, decoded.catalogs.size)
    }
}
