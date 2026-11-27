package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.ui.screens.settings.SettingsCategory.ABOUT
import com.nuvio.tv.ui.screens.settings.SettingsCategory.ACCOUNT
import com.nuvio.tv.ui.screens.settings.SettingsCategory.ADVANCED
import com.nuvio.tv.ui.screens.settings.SettingsCategory.APPEARANCE
import com.nuvio.tv.ui.screens.settings.SettingsCategory.BACKUP
import com.nuvio.tv.ui.screens.settings.SettingsCategory.CONTENT_DISCOVERY
import com.nuvio.tv.ui.screens.settings.SettingsCategory.DEBUG
import com.nuvio.tv.ui.screens.settings.SettingsCategory.INTEGRATION
import com.nuvio.tv.ui.screens.settings.SettingsCategory.LAYOUT
import com.nuvio.tv.ui.screens.settings.SettingsCategory.PLAYBACK
import com.nuvio.tv.ui.screens.settings.SettingsCategory.PROFILES
import com.nuvio.tv.ui.screens.settings.SettingsCategory.TRACKERS
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsCategoriesTest {

    @Test
    fun `the primary profile sees every category in rail order, Debug last in a debug build`() {
        assertEquals(
            listOf(ACCOUNT, PROFILES, APPEARANCE, LAYOUT, CONTENT_DISCOVERY, INTEGRATION, PLAYBACK, BACKUP, ABOUT, ADVANCED, DEBUG),
            visibleSettingsCategories(isPrimaryProfile = true, isDebugBuild = true, hasTrackers = false)
        )
    }

    @Test
    fun `a release build has no Debug`() {
        assertEquals(
            listOf(ACCOUNT, PROFILES, APPEARANCE, LAYOUT, CONTENT_DISCOVERY, INTEGRATION, PLAYBACK, BACKUP, ABOUT, ADVANCED),
            visibleSettingsCategories(isPrimaryProfile = true, isDebugBuild = false, hasTrackers = false)
        )
    }

    @Test
    fun `a build with a tracker shows Trackers after Integrations, on every profile`() {
        assertEquals(
            listOf(APPEARANCE, LAYOUT, CONTENT_DISCOVERY, INTEGRATION, TRACKERS, PLAYBACK, ABOUT, ADVANCED),
            visibleSettingsCategories(isPrimaryProfile = false, isDebugBuild = false, hasTrackers = true)
        )
    }

    @Test
    fun `another profile loses Account, Profiles and Backup`() {
        assertEquals(
            listOf(APPEARANCE, LAYOUT, CONTENT_DISCOVERY, INTEGRATION, PLAYBACK, ABOUT, ADVANCED),
            visibleSettingsCategories(isPrimaryProfile = false, isDebugBuild = false, hasTrackers = false)
        )
    }
}
