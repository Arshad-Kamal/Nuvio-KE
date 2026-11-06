package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.streams.StreamBadgeFilter
import com.nuvio.tv.core.streams.StreamBadgeImport
import com.nuvio.tv.core.streams.StreamBadgeRules
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** U5j "Use addon formatting": default off, its own setter, untouched by the phone page's save. */
class StreamBadgeSettingsAddonFormattingTest {

    @Test
    fun `defaults to off`() = runTest {
        val (store, _) = harness()

        assertFalse(store.settings.first().useAddonFormatting)
    }

    @Test
    fun `setter stores the key the settings flow reads`() = runTest {
        val (store, backing) = harness()

        store.setUseAddonFormatting(true)

        assertTrue(store.settings.first().useAddonFormatting)
        assertEquals(true, backing.value[KEY])
    }

    @Test
    fun `phone page save leaves the key as it was`() = runTest {
        val (store, backing) = harness()
        store.setUseAddonFormatting(true)

        store.setStreamBadgeRules(
            StreamBadgeRules(
                imports = listOf(
                    StreamBadgeImport(
                        sourceUrl = "https://example.com/b.json",
                        filters = listOf(StreamBadgeFilter(name = "DV", pattern = "DV"))
                    )
                )
            )
        )

        assertEquals(true, backing.value[KEY])
        assertTrue(store.settings.first().rules.hasImport)
    }

    private fun harness(): Pair<StreamBadgeSettingsDataStore, TestPreferencesDataStore> {
        val backing = TestPreferencesDataStore()
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } returns backing
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns MutableStateFlow(1)
        return StreamBadgeSettingsDataStore(factory, profileManager) to backing
    }

    private companion object {
        val KEY = booleanPreferencesKey("use_addon_formatting")
    }
}
