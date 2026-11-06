package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** T19 (P17): the stored key names are the profile-sync contract with upstream builds. */
class PlayerSettingsSubtitleEdgeStyleTest {
    private val edgeKey = stringPreferencesKey("subtitle_edge_style")
    private val legacyKey = booleanPreferencesKey("subtitle_outline_enabled")

    @Test
    fun `legacy outline off reads as None`() = runTest {
        val store = TestPreferencesDataStore(preferencesOf(legacyKey to false))
        val settings = playerSettingsOver(store)

        assertEquals(SubtitleEdgeStyle.NONE, settings.playerSettings.first().subtitleStyle.edgeStyle)
    }

    @Test
    fun `writer stores the enum name and keeps the legacy toggle in step`() = runTest {
        val store = TestPreferencesDataStore()
        val settings = playerSettingsOver(store)

        settings.setSubtitleEdgeStyle(SubtitleEdgeStyle.DROP_SHADOW)
        assertEquals("DROP_SHADOW", store.value[edgeKey])
        assertEquals(false, store.value[legacyKey])
        assertEquals(SubtitleEdgeStyle.DROP_SHADOW, settings.playerSettings.first().subtitleStyle.edgeStyle)

        settings.setSubtitleEdgeStyle(SubtitleEdgeStyle.OUTLINE)
        assertEquals("OUTLINE", store.value[edgeKey])
        assertEquals(true, store.value[legacyKey])
    }
}
