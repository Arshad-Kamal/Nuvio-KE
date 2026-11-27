package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec 108 D10: AutoSync is on for a profile that never stored the key; a stored value wins. */
class PlayerSettingsSubtitleAutoSyncDefaultTest {
    private val key = booleanPreferencesKey("subtitle_auto_sync_enabled")

    @Test
    fun `unset key reads as on`() = runTest {
        val settings = playerSettingsOver(TestPreferencesDataStore())

        assertEquals(true, settings.playerSettings.first().subtitleAutoSyncEnabled)
        assertEquals(true, PlayerSettings().subtitleAutoSyncEnabled)
    }

    @Test
    fun `stored off stays off`() = runTest {
        val settings = playerSettingsOver(TestPreferencesDataStore(preferencesOf(key to false)))

        assertEquals(false, settings.playerSettings.first().subtitleAutoSyncEnabled)
    }

    @Test
    fun `writer stores the toggle`() = runTest {
        val store = TestPreferencesDataStore()
        val settings = playerSettingsOver(store)

        settings.setSubtitleAutoSyncEnabled(false)
        assertEquals(false, store.value[key])
        assertEquals(false, settings.playerSettings.first().subtitleAutoSyncEnabled)
    }
}
