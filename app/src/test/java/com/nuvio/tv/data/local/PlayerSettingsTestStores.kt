package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

/**
 * A device store as the real one looks after its migrations ran (TestPreferencesDataStore has
 * no DataMigration support): [initial] marked seeded, then DevicePlayerSettingsMigrations applied.
 */
internal fun migratedDeviceStore(initial: Preferences = emptyPreferences()): TestPreferencesDataStore {
    val seeded = initial.toMutablePreferences().apply { this[DEVICE_STORE_SEEDED_KEY] = true }
    return TestPreferencesDataStore(runBlocking { DevicePlayerSettingsMigrations.migrate(seeded) })
}

/**
 * PlayerSettingsDataStore over fake profile stores (one shared by default, or per id via
 * [profileStores]) and a fake device store.
 */
internal fun playerSettingsOver(
    profile: TestPreferencesDataStore,
    device: TestPreferencesDataStore = migratedDeviceStore(),
    activeProfileId: MutableStateFlow<Int> = MutableStateFlow(1),
    profileStores: (Int) -> TestPreferencesDataStore = { profile }
): PlayerSettingsDataStore {
    val factory = mockk<ProfileDataStoreFactory>()
    every { factory.get(any(), any()) } answers { profileStores(firstArg()) }
    val profileManager = mockk<ProfileManager>(relaxed = true)
    every { profileManager.activeProfileId } returns activeProfileId
    return PlayerSettingsDataStore(
        factory,
        profileManager,
        mockk<Context>(relaxed = true),
        DevicePlayerSettingsStore(device)
    )
}
