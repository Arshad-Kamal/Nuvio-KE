package com.nuvio.tv.data.local

import com.nuvio.tv.core.di.NetworkModule
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Moshi retirement M5: persisted-store compatibility. These keys are USER DATA
 * written by Moshi-era builds (all fields present, explicit nulls, enums by
 * name); the kotlinx readers must consume them unchanged. The pin exercises
 * the real decode path.
 */
class MoshiLegacyStoreShapeReadTest {

    private val json = NetworkModule.provideWireJson()

    @Test
    fun `profiles json decodes from moshi-written shape`() {
        val legacy = """
        [{"id":1,"name":"Primary","avatarColorHex":"#1E88E5","usesPrimaryAddons":false,
          "usesPrimaryPlugins":false,"avatarId":null,"avatarUrl":null,
          "profileBackgroundId":null,"profileBackgroundUrl":null},
         {"id":2,"name":"Kids","avatarColorHex":"#E53935","usesPrimaryAddons":true,
          "usesPrimaryPlugins":true,"avatarId":"cat","avatarUrl":"https://a",
          "profileBackgroundId":"bg1","profileBackgroundUrl":"https://b"}]
        """.trimIndent()
        val decoded = json.decodeFromString<List<ProfileJson>>(legacy)
        assertEquals(2, decoded.size)
        assertNull(decoded[0].avatarId)
        assertEquals("cat", decoded[1].avatarId)
        assertTrue(decoded[1].usesPrimaryAddons)
        assertEquals(decoded, json.decodeFromString<List<ProfileJson>>(json.encodeToString(decoded)))
    }
}
