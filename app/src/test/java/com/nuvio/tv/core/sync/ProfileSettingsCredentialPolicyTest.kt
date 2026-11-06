package com.nuvio.tv.core.sync

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.data.local.ContinueWatchingSettingsDataStore
import com.nuvio.tv.data.local.WatchCountDataStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSettingsCredentialPolicyTest {
    @Test
    fun `non tracker credentials are excluded from profile settings blobs`() {
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("debrid_settings", "torbox_api_key"))
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("debrid_settings", "premiumize_api_key"))
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("debrid_settings", "real_debrid_api_key"))
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("mdblist_settings", "mdblist_api_key"))
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("animeskip_settings", "animeskip_client_id"))
    }

    @Test
    fun `tracker and non credential settings remain in their existing sync surfaces`() {
        assertFalse(shouldExcludePreferenceFromProfileSettingsSync("trakt_settings", "trakt_access_token"))
        assertFalse(shouldExcludePreferenceFromProfileSettingsSync("debrid_settings", "debrid_enabled"))
        assertFalse(shouldExcludePreferenceFromProfileSettingsSync("mdblist_settings", "mdblist_enabled"))
        assertFalse(shouldExcludePreferenceFromProfileSettingsSync("animeskip_settings", "animeskip_enabled"))
    }

    @Test
    fun `audio passthrough and denied handling are device-local, excluded from profile settings blobs`() {
        val deviceLocalAudioKeys = listOf(
            "force_optical_passthrough",
            "allow_ac3_passthrough",
            "allow_eac3_passthrough",
            "allow_truehd_passthrough",
            "allow_dts_passthrough",
            "allow_dts_hd_passthrough",
            "denied_codec_handling",
            "mat_passthrough_enabled",
            "audio_rejections_seen",
            "audio_rejections_confirmed",
            "migration_audio_rejections_reset_done",
        )
        deviceLocalAudioKeys.forEach { key ->
            assertTrue(shouldExcludePreferenceFromProfileSettingsSync("player_settings", key))
            // the exclusion is scoped to player_settings only
            assertFalse(shouldExcludePreferenceFromProfileSettingsSync("theme_settings", key))
        }
    }

    @Test
    fun `per-device player keys added after the list stay local (P26)`() {
        listOf(
            "inject_hdr10_metadata_on_strip",
            "assessment_revert_snapshot",
            "parallel_chunk_size_kb",
            "enable_http2",
            "migration_retain_back_buffer_wired_done",
            "migration_back_buffer_budget_done",
        ).forEach { key ->
            assertTrue(key, shouldExcludePreferenceFromProfileSettingsSync("player_settings", key))
        }
    }

    @Test
    fun `every player migration flag is local by prefix, only in player_settings`() {
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("player_settings", "migration_some_future_done"))
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("player_settings", "migration_vod_cache_split_done"))
        assertFalse(shouldExcludePreferenceFromProfileSettingsSync("layout_settings", "migration_some_future_done"))
        // ordinary player preferences still sync
        assertFalse(shouldExcludePreferenceFromProfileSettingsSync("player_settings", "subtitle_size"))
        assertFalse(shouldExcludePreferenceFromProfileSettingsSync("player_settings", "stream_auto_play_mode"))
    }

    @Test
    fun `This TV marks per-device keys only, never catalog or credential exclusions`() {
        assertTrue(isLocalOnlyProfileSetting("player_settings", "tunneling_enabled"))
        assertTrue(isLocalOnlyProfileSetting("player_settings", "migration_some_future_done"))
        assertTrue(isLocalOnlyProfileSetting("layout_settings", "last_non_off_discover_location"))
        assertFalse(isLocalOnlyProfileSetting("player_settings", "subtitle_size"))
        // excluded from the blob but synced elsewhere (home catalog sync) or never shown
        assertFalse(isLocalOnlyProfileSetting("layout_settings", "home_catalog_order_keys"))
        assertFalse(isLocalOnlyProfileSetting("debrid_settings", "torbox_api_key"))
        assertFalse(isLocalOnlyProfileSetting("theme_settings", "tunneling_enabled"))
    }

    @Test
    fun `a pull preserves every local-only player entry, including prefix-matched migration flags`() {
        val prefs = mutablePreferencesOf(
            booleanPreferencesKey("migration_some_future_done") to true,
            booleanPreferencesKey("inject_hdr10_metadata_on_strip") to true,
            stringPreferencesKey("assessment_revert_snapshot") to "{}",
            intPreferencesKey("subtitle_size") to 120,
        )
        val preserved = localOnlyPreferenceEntries("player_settings", prefs)
        assertEquals(
            setOf("migration_some_future_done", "inject_hdr10_metadata_on_strip", "assessment_revert_snapshot"),
            preserved.keys.map { it.name }.toSet()
        )
        assertEquals(true, preserved[booleanPreferencesKey("migration_some_future_done")])
        assertEquals("{}", preserved[stringPreferencesKey("assessment_revert_snapshot")])
    }

    @Test
    fun `a pull never preserves layout's legacy search_discover_enabled`() {
        val prefs = mutablePreferencesOf(
            booleanPreferencesKey("search_discover_enabled") to true,
            stringPreferencesKey("last_non_off_discover_location") to "SEARCH",
            stringPreferencesKey("home_catalog_order_keys") to "[]",
            stringPreferencesKey("discover_location") to "SEARCH",
        )
        assertEquals(
            setOf("last_non_off_discover_location", "home_catalog_order_keys"),
            localOnlyPreferenceEntries("layout_settings", prefs).keys.map { it.name }.toSet()
        )
    }

    @Test
    fun `a pull preserves credentials, not the feature's synced keys`() {
        val prefs = mutablePreferencesOf(
            stringPreferencesKey("torbox_api_key") to "k",
            booleanPreferencesKey("debrid_enabled") to true,
        )
        assertEquals(
            setOf("torbox_api_key"),
            localOnlyPreferenceEntries("debrid_settings", prefs).keys.map { it.name }.toSet()
        )
    }

    @Test
    fun `profile settings blob feature list is a pinned wire contract`() {
        assertEquals(
            listOf(
                "theme_settings",
                "layout_settings",
                "experience_settings",
                "player_settings",
                "stream_badge_settings",
                "trailer_settings",
                "tmdb_settings",
                "mdblist_settings",
                "trakt_settings",
                "debrid_settings",
                "track_preference"
            ),
            profileSettingsSyncedFeatures
        )
    }

    @Test
    fun `continue watching settings keep their legacy on-disk and blob names`() {
        assertEquals("trakt_settings", ContinueWatchingSettingsDataStore.FEATURE)
        assertEquals("dismissed_next_up_keys", ContinueWatchingSettingsDataStore.DISMISSED_NEXT_UP_KEYS_KEY)
    }

    @Test
    fun `watch counts stay on the device`() {
        // Spec 27e / Q7: Library's play counts are local per profile, never in the blob.
        assertFalse(WatchCountDataStore.FEATURE in profileSettingsSyncedFeatures)
    }
}
