package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VOD disk cache cut (2026-10-06): the retired vod_cache_* keys are never read, normalized,
 * migrated, or reset anymore, so stored values survive a settings pass byte-for-byte (they
 * stay local-only via ProfileSettingsSyncService.localOnlyPlayerProfileSettingsKeys). The
 * seeded values are ones the removed code would have rewritten: size below its 100 MB floor,
 * an invalid mode, and no split-done flag. Neither the profile file nor the device store
 * (Spec 33 D1) gains a vod-cache value key (on a real device the seed may copy the retired
 * split flag, which nothing reads).
 */
class PlayerSettingsRetiredVodCacheKeysTest {
    private val retired = preferencesOf(
        booleanPreferencesKey("vod_cache_enabled") to true,
        stringPreferencesKey("vod_cache_size_mode") to "BOGUS",
        intPreferencesKey("vod_cache_size_mb") to 50
    )

    private val device = migratedDeviceStore()

    @Test
    fun `read, migration and buffer reset leave stored vod cache keys untouched`() = runTest {
        val store = TestPreferencesDataStore(retired)
        val settings = playerSettingsOver(store, device)

        settings.playerSettings.first()
        settings.resetBufferSettingsToDefaults()

        val after = store.value.asMap()
        for ((key, value) in retired.asMap()) {
            assertEquals(key.name, value, after[key])
        }
        assertTrue(after.keys.none { it.name == "migration_vod_cache_split_done" })
        assertTrue(device.value.asMap().keys.none { it.name.contains("vod_cache") })
    }

    @Test
    fun `fresh profile never gains a vod cache key`() = runTest {
        val store = TestPreferencesDataStore()
        val settings = playerSettingsOver(store, device)

        settings.playerSettings.first()
        settings.resetBufferSettingsToDefaults()

        assertTrue(store.value.asMap().keys.none { it.name.contains("vod_cache") })
        assertTrue(device.value.asMap().keys.none { it.name.contains("vod_cache") })
    }
}
