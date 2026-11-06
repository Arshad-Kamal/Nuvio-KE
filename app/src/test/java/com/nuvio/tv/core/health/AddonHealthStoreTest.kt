package com.nuvio.tv.core.health

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException

/**
 * Regression guard for record()'s "Never throws" contract: the call sites fire
 * it from bare launches (no handler), so an unguarded DataStore failure — a
 * full disk is realistic on this device — would crash the app from passive
 * telemetry. A write failure must cost one lost sample, nothing more.
 */
class AddonHealthStoreTest {

    @Test
    fun `record swallows a DataStore write failure instead of throwing`() = runTest {
        val dataStore = mockk<DataStore<Preferences>>()
        coEvery { dataStore.updateData(any()) } throws IOException("disk full")
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } returns dataStore
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns kotlinx.coroutines.flow.MutableStateFlow(1)
        // The store's constructor builds its profile flow from this.
        every { profileManager.profiles } returns kotlinx.coroutines.flow.MutableStateFlow(emptyList())
        val layoutPreferenceDataStore = mockk<LayoutPreferenceDataStore>()
        every { layoutPreferenceDataStore.addonHealthEnabled } returns flowOf(true)
        val store = AddonHealthStore(
            factory = factory,
            profileManager = profileManager,
            layoutPreferenceDataStore = layoutPreferenceDataStore
        )

        // FAILURE skips the success coalescing path and goes straight to the write.
        store.record("addon:https://addon.example", HealthOutcome.FAILURE, 10L)
    }
}
