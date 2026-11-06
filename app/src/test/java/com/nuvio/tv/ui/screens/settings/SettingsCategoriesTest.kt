package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.ui.screens.settings.SettingsCategory.ABOUT
import com.nuvio.tv.ui.screens.settings.SettingsCategory.ACCOUNT
import com.nuvio.tv.ui.screens.settings.SettingsCategory.ADVANCED
import com.nuvio.tv.ui.screens.settings.SettingsCategory.APPEARANCE
import com.nuvio.tv.ui.screens.settings.SettingsCategory.CONTENT_DISCOVERY
import com.nuvio.tv.ui.screens.settings.SettingsCategory.DEBUG
import com.nuvio.tv.ui.screens.settings.SettingsCategory.INTEGRATION
import com.nuvio.tv.ui.screens.settings.SettingsCategory.LAYOUT
import com.nuvio.tv.ui.screens.settings.SettingsCategory.PLAYBACK
import com.nuvio.tv.ui.screens.settings.SettingsCategory.PROFILES
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsCategoriesTest {

    @Test
    fun `the primary profile sees every category in rail order, Debug last in a debug build`() {
        assertEquals(
            listOf(ACCOUNT, PROFILES, APPEARANCE, LAYOUT, CONTENT_DISCOVERY, INTEGRATION, PLAYBACK, ABOUT, ADVANCED, DEBUG),
            visibleSettingsCategories(isPrimaryProfile = true, isDebugBuild = true)
        )
    }

    @Test
    fun `a release build has no Debug`() {
        assertEquals(
            listOf(ACCOUNT, PROFILES, APPEARANCE, LAYOUT, CONTENT_DISCOVERY, INTEGRATION, PLAYBACK, ABOUT, ADVANCED),
            visibleSettingsCategories(isPrimaryProfile = true, isDebugBuild = false)
        )
    }

    @Test
    fun `another profile loses Account and Profiles`() {
        assertEquals(
            listOf(APPEARANCE, LAYOUT, CONTENT_DISCOVERY, INTEGRATION, PLAYBACK, ABOUT, ADVANCED),
            visibleSettingsCategories(isPrimaryProfile = false, isDebugBuild = false)
        )
    }
}
