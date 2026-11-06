package com.nuvio.tv.data.remote.dto

import com.nuvio.tv.core.di.NetworkModule
import com.nuvio.tv.data.remote.api.TmdbDetailsResponse
import com.nuvio.tv.data.remote.api.TmdbDiscoverResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Moshi retirement M4-A: decode fixtures for TMDB (52 mechanical classes —
 * pinned representatively: details, discover, nested genres/companies).
 */
class TmdbWireDtoDecodeTest {

    private val json = NetworkModule.provideWireJson()

    @Test
    fun `tmdb details decodes nested genres and companies`() {
        val payload = """
        {"id":550,"title":"Fight Club","original_title":"Fight Club",
         "overview":"A ticking-time-bomb insomniac...","release_date":"1999-10-15",
         "runtime":139,"vote_average":8.439,"unknown_future_field":{"a":1},
         "genres":[{"id":18,"name":"Drama"}],
         "production_companies":[{"id":508,"logo_path":"/7PzJdsLGlR7oW4n0Jq3Gj3V7oU2.png","name":"Regency Enterprises","origin_country":"US"}]}
        """.trimIndent()
        val decoded = json.decodeFromString<TmdbDetailsResponse>(payload)
        assertEquals(550, decoded.id)
        assertEquals("Fight Club", decoded.title)
        assertEquals(139, decoded.runtime)
        assertEquals(8.439, decoded.voteAverage!!, 0.0001)
        assertEquals("Drama", decoded.genres!![0].name)
        assertEquals("Regency Enterprises", decoded.productionCompanies!![0].name)
        assertEquals("/7PzJdsLGlR7oW4n0Jq3Gj3V7oU2.png", decoded.productionCompanies[0].logoPath)
    }

    @Test
    fun `tmdb details requires id`() {
        org.junit.Assert.assertThrows(kotlinx.serialization.SerializationException::class.java) {
            json.decodeFromString<TmdbDetailsResponse>("""{"title":"x"}""")
        }
    }

    @Test
    fun `tmdb discover decodes page shape`() {
        val decoded = json.decodeFromString<TmdbDiscoverResponse>(
            """{"page":1,"total_pages":9,"total_results":177,"results":[{"id":603,"title":"The Matrix"}]}"""
        )
        assertEquals(1, decoded.page)
        assertEquals(177, decoded.totalResults)
        assertTrue(decoded.results!![0].title != null)
    }
}
