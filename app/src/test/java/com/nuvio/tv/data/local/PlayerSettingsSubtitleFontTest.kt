package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Spec 132: the subtitle font choice (id + family name) is per profile and a null id means Default.
 * That it is never synced is pinned by PlayerSettingsSyncClassificationTest.
 */
class PlayerSettingsSubtitleFontTest {
    private val idKey = stringPreferencesKey("subtitle_font_id")
    private val nameKey = stringPreferencesKey("subtitle_font_name")

    @Test
    fun `a choice round-trips and a null or blank id clears both`() = runTest {
        val store = TestPreferencesDataStore()
        val settings = playerSettingsOver(store)
        assertNull(settings.playerSettings.first().subtitleStyle.fontId)

        settings.setSubtitleFont(ID, NAME)
        assertEquals(ID, store.value[idKey])
        val style = settings.playerSettings.first().subtitleStyle
        assertEquals(ID, style.fontId)
        assertEquals(NAME, style.fontName)

        settings.setSubtitleFont(null, null)
        assertEquals(emptySet<String>(), store.value.asMap().keys.map { it.name }.toSet())
        assertNull(settings.playerSettings.first().subtitleStyle.fontId)

        settings.setSubtitleFont(ID, NAME)
        settings.setSubtitleFont(" ", NAME)
        assertEquals(emptySet<String>(), store.value.asMap().keys.map { it.name }.toSet())
    }

    @Test
    fun `a new pick without a name drops the old name`() = runTest {
        val store = TestPreferencesDataStore()
        val settings = playerSettingsOver(store)
        settings.setSubtitleFont(ID, NAME)
        settings.setSubtitleFont("font-2", null)
        assertNull(store.value[nameKey])
        assertNull(settings.playerSettings.first().subtitleStyle.fontName)
    }

    @Test
    fun `a stored blank id reads as Default, its name ignored`() = runTest {
        val store = TestPreferencesDataStore(preferencesOf(idKey to "", nameKey to NAME))
        val style = playerSettingsOver(store).playerSettings.first().subtitleStyle
        assertNull(style.fontId)
        assertNull(style.fontName)
    }

    @Test
    fun `each profile keeps its own choice and the device store holds none`() = runTest {
        val one = TestPreferencesDataStore()
        val two = TestPreferencesDataStore()
        val device = migratedDeviceStore()
        val active = MutableStateFlow(1)
        val settings = playerSettingsOver(one, device, active) { if (it == 2) two else one }

        settings.setSubtitleFont(ID, NAME)
        active.value = 2
        assertNull(settings.playerSettings.first().subtitleStyle.fontId)
        assertEquals(ID, one.value[idKey])
        assertNull(two.value[idKey])
        assertNull(device.value[idKey])
        assertNull(device.value[nameKey])
    }

    private companion object {
        /** The store's id shape: font-<millis>-<uuid>. */
        const val ID = "font-1731830400000-3f2b9c1e-8d4a-4f6b-9a2e-5c7d1e0f4a3b"
        const val NAME = "Montserrat Medium"
    }
}
