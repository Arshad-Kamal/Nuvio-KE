package com.nuvio.tv.data.repository

import com.nuvio.tv.core.di.NetworkModule
import com.nuvio.tv.data.remote.dto.CatalogResponseDto
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Moshi retirement M4 dispositions: the catalog disk-cache codec claim. Old
 * blobs were Moshi-written (every field, explicit nulls, arbitrary Any? people
 * values); the repository decodes them via wireJson inside runCatching — a
 * decode failure degrades to a cache miss. These pins hold the claim at the
 * decode seam the repository uses.
 */
class CatalogDiskCacheBlobTest {

    private val json = NetworkModule.provideWireJson()

    /** Moshi-shaped legacy blob: full field set, explicit nulls, null metas entry. */
    private val legacyBlob = """
    {"metas":[{"id":"tt1","type":"series","name":"First","poster":null,"background":null,
               "logo":null,"description":null,"releaseInfo":null,"imdbRating":null,
               "genres":null,"runtime":null,"cast":null,"director":["D1","D2"],
               "writer":null,"writers":"W Solo","imdb_id":null,"country":null},
              null,
              {"id":"tt3","type":"series","name":"Third"}],
     "metas_count":15,"metas_skip":null}
    """.trimIndent()

    @Test
    fun `legacy moshi-written blob decodes with people shapes intact`() {
        val decoded = json.decodeFromString<CatalogResponseDto>(legacyBlob)
        assertEquals(3, decoded.metas.size)
        assertTrue(decoded.metas[1] == null)
        assertEquals("First", decoded.metas[0]!!.name)
        // People fields keep their raw element shapes for coerceStringList.
        assertEquals(2, (decoded.metas[0]!!.director as JsonArray).size)
        assertEquals("W Solo", decoded.metas[0]!!.writers!!.jsonPrimitive.content)
    }

    @Test
    fun `garbage blob throws - the repository converts that to a cache miss`() {
        assertThrows(SerializationException::class.java) {
            json.decodeFromString<CatalogResponseDto>("""{"metas":"not-a-list"}""")
        }
        // The repo's decode site is runCatching { ... }.getOrNull() — same shape.
        val entry = runCatching { json.decodeFromString<CatalogResponseDto>("""{"metas":"not-a-list"}""") }.getOrNull()
        assertEquals(null, entry)
    }
}
