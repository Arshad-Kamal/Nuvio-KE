package com.nuvio.tv.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsReturnTargetTest {

    private val all = visibleSettingsCategories(isPrimaryProfile = true, isDebugBuild = false)
    private val nonPrimary = visibleSettingsCategories(isPrimaryProfile = false, isDebugBuild = false)

    @Test
    fun `a target survives its saveable form, a slash in the row key included`() {
        val target = SettingsReturnTarget(SettingsCategory.PROFILES, "profiles/manage")
        assertEquals(target, decodeSettingsReturnTarget(target.encode()))
    }

    @Test
    fun `nothing saved, an unknown category or a missing part decodes to null`() {
        assertNull(decodeSettingsReturnTarget(null))
        assertNull(decodeSettingsReturnTarget("GONE/row"))
        assertNull(decodeSettingsReturnTarget("PROFILES/"))
        assertNull(decodeSettingsReturnTarget("/row"))
        assertNull(decodeSettingsReturnTarget("PROFILES"))
    }

    @Test
    fun `a saved row in a shown category wins`() {
        val target = SettingsReturnTarget(SettingsCategory.CONTENT_DISCOVERY, "content_addons")
        assertEquals(
            SettingsEntryFocus.Row(target),
            settingsEntryFocus(target, SettingsCategory.APPEARANCE, all)
        )
    }

    @Test
    fun `a saved row in a hidden category falls back to the selected rail item`() {
        val target = SettingsReturnTarget(SettingsCategory.ACCOUNT, "account_sign_in")
        assertEquals(
            SettingsEntryFocus.Rail(SettingsCategory.LAYOUT),
            settingsEntryFocus(target, SettingsCategory.LAYOUT, nonPrimary)
        )
    }

    @Test
    fun `without a target the selected rail item, or the first one when it is hidden`() {
        assertEquals(
            SettingsEntryFocus.Rail(SettingsCategory.PLAYBACK),
            settingsEntryFocus(null, SettingsCategory.PLAYBACK, all)
        )
        assertEquals(
            SettingsEntryFocus.Rail(SettingsCategory.APPEARANCE),
            settingsEntryFocus(null, SettingsCategory.PROFILES, nonPrimary)
        )
    }

    @Test
    fun networkEntryOpensBufferAndNetworkAtThisTv() {
        val entry = SettingsEntry.fromName("NETWORK")
        assertEquals(SettingsEntry.NETWORK, entry)
        assertEquals(SettingsReturnTarget(SettingsCategory.PLAYBACK, THIS_TV_ENTRY_ROW_KEY), entry!!.target)
        assertEquals(PlaybackSection.BUFFER_NETWORK, entry.playbackSection)
    }

    @Test
    fun tmdbKeyEntryOpensTheKeyPanelInIntegrations() {
        val entry = SettingsEntry.fromName("TMDB_KEY")
        assertEquals(SettingsEntry.TMDB_KEY, entry)
        assertEquals(SettingsReturnTarget(SettingsCategory.INTEGRATION, TMDB_KEY_ROW_KEY), entry!!.target)
        assertEquals(null, entry.playbackSection)
        assertEquals(true, entry.opensPanel)
        assertEquals(false, SettingsEntry.NETWORK.opensPanel)
    }

    @Test
    fun updateEntryOpensTheUpdatePanelInAbout() {
        val entry = SettingsEntry.fromName("UPDATE")
        assertEquals(SettingsEntry.UPDATE, entry)
        assertEquals(SettingsReturnTarget(SettingsCategory.ABOUT, ABOUT_UPDATE_ROW_KEY), entry!!.target)
        assertEquals(null, entry.playbackSection)
        assertEquals(true, entry.opensPanel)
    }

    @Test
    fun unknownEntryNamesAreIgnored() {
        assertNull(SettingsEntry.fromName(null))
        assertNull(SettingsEntry.fromName("network"))
    }
}
