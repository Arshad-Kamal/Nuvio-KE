package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.streams.StreamBadgePlacement
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
import org.junit.Test

/**
 * U5m retired the Addon logo and Badge position settings: stored values still read, and no
 * writer (the phone page's rules save, the live setters) rewrites, adds, or removes either key
 * (the profile-settings sync carries them).
 */
class StreamBadgeSettingsRetiredKeysTest {
    private val addonLogoKey = booleanPreferencesKey("show_addon_logo")
    private val placementKey = stringPreferencesKey("stream_badge_placement")
    private val retired = preferencesOf(addonLogoKey to false, placementKey to "TOP")
    private val oneImport = StreamBadgeRules(
        imports = listOf(
            StreamBadgeImport(
                sourceUrl = "https://example.com/b.json",
                filters = listOf(StreamBadgeFilter(name = "DV", pattern = "DV"))
            )
        )
    )

    private fun storeOver(backing: TestPreferencesDataStore): StreamBadgeSettingsDataStore {
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } returns backing
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns MutableStateFlow(1)
        return StreamBadgeSettingsDataStore(factory, profileManager)
    }

    @Test
    fun `stored retired values keep reading`() = runTest {
        val settings = storeOver(TestPreferencesDataStore(retired)).settings.first()

        assertFalse(settings.showAddonLogo)
        assertEquals(StreamBadgePlacement.TOP, settings.badgePlacement)
    }

    @Test
    fun `phone page save and live setters leave the stored retired keys as they were`() = runTest {
        val backing = TestPreferencesDataStore(retired)
        val store = storeOver(backing)

        store.setStreamBadgeRules(oneImport)
        store.setStreamBadgeRules(StreamBadgeRules())
        store.setShowFileSizeBadges(false)
        store.setUseAddonFormatting(true)

        assertEquals(false, backing.value[addonLogoKey])
        assertEquals("TOP", backing.value[placementKey])
    }

    @Test
    fun `phone page save on a fresh profile writes only the rules key`() = runTest {
        val backing = TestPreferencesDataStore()

        storeOver(backing).setStreamBadgeRules(oneImport)

        assertEquals(
            setOf("stream_badge_rules"),
            backing.value.asMap().keys.mapTo(mutableSetOf()) { it.name }
        )
    }
}
