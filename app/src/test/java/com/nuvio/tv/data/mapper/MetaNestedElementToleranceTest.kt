package com.nuvio.tv.data.mapper

import com.nuvio.tv.core.di.NetworkModule
import com.nuvio.tv.data.remote.dto.MetaDto
import com.nuvio.tv.data.remote.dto.MetaLinkDto
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * L8-14: one malformed nested element from an untrusted addon is dropped instead of
 * failing the whole meta decode, and MetaLinkDto (also written to the catalog disk
 * cache) still encodes a complete link byte-for-byte as before.
 */
class MetaNestedElementToleranceTest {

    private val json = NetworkModule.provideWireJson()

    @Test
    fun `malformed nested elements are dropped, valid ones kept`() {
        val payload = """
        {"id":"tt1","type":"series","name":"Show",
         "videos":[{"name":"No id"},{"id":"tt1:1:1","season":1,"episode":1}],
         "links":[{"name":"Drama","url":"stremio:///x"},
                  {"name":"Drama","category":"Genres","url":"stremio:///y"}],
         "app_extras":{
           "cast":[{"character":"Nameless"},{"name":"Actor A"}],
           "releaseDates":{"results":[{"release_dates":[]},{"iso_3166_1":"US","release_dates":[]}]}
         }}
        """.trimIndent()

        val meta = json.decodeFromString<MetaDto>(payload).toDomain()

        assertEquals(listOf("tt1:1:1"), meta.videos.map { it.id })
        assertEquals(listOf("stremio:///y"), meta.links.map { it.url })
        assertEquals(listOf("Actor A"), meta.castMembers.map { it.name })
        assertEquals(listOf("US"), meta.releaseDates.map { it.countryCode })
    }

    @Test
    fun `a complete link encodes exactly as before`() {
        val encoded = json.encodeToString(MetaLinkDto(name = "Drama", category = "Genres", url = "stremio:///y"))

        assertEquals("""{"name":"Drama","category":"Genres","url":"stremio:///y"}""", encoded)
    }
}
