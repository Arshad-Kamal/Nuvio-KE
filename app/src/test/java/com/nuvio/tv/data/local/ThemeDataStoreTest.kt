package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.SettingsUiStyle
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T5 pin for the W2-F4 read path: the stored settings-ui style must actually
 * be read back (it was read then discarded, always rendering CLASSIC — the
 * second time this read path was wrong, hence the pin).
 */
class ThemeDataStoreTest {

    @Test
    fun `settings ui style falls back to classic unless a valid name is stored`() = runTest {
        assertEquals(SettingsUiStyle.CLASSIC, harness().settingsUiStyle.first())
        assertEquals(SettingsUiStyle.ZEN, harness("ZEN").settingsUiStyle.first())
        assertEquals(SettingsUiStyle.HORIZON, harness("HORIZON").settingsUiStyle.first())
        assertEquals(SettingsUiStyle.CLASSIC, harness("GARBAGE").settingsUiStyle.first())
    }

    private fun harness(storedStyle: String? = null): ThemeDataStore {
        val backing = TestPreferencesDataStore(
            storedStyle?.let { style ->
                emptyPreferences().toMutablePreferences().apply {
                    this[stringPreferencesKey("settings_ui_style")] = style
                }.toPreferences()
            } ?: emptyPreferences()
        )
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } returns backing
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns MutableStateFlow(1)
        return ThemeDataStore(factory, profileManager)
    }
}
