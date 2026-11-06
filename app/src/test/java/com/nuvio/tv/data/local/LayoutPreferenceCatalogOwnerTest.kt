package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.SyncCatalogItem
import com.nuvio.tv.core.sync.SyncHomeCatalogPayload
import com.nuvio.tv.domain.model.UserProfile
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * L9-38: home-catalog order/disabled/title reads and writes resolve the same store via the
 * addon-owner resolver, re-resolved on profile-list edits; a read-only profile never
 * writes profile 1's layout.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LayoutPreferenceCatalogOwnerTest {
    private val orderKey = stringPreferencesKey("home_catalog_order_keys")
    private val activeProfileId = MutableStateFlow(2)
    private val profiles = MutableStateFlow(
        listOf(
            UserProfile(id = 1, name = "Parent", avatarColorHex = "#000000"),
            UserProfile(id = 2, name = "Child", avatarColorHex = "#000000")
        )
    )
    private val stores = mapOf(
        1 to TestPreferencesDataStore(preferencesOf(orderKey to """["parent.catalog"]""")),
        2 to TestPreferencesDataStore(preferencesOf(orderKey to """["child.catalog"]"""))
    )
    private val layout: LayoutPreferenceDataStore

    init {
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } answers { stores.getValue(firstArg()) }
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns activeProfileId
        every { profileManager.profiles } returns profiles
        layout = LayoutPreferenceDataStore(factory, profileManager)
    }

    @Test
    fun `catalog order re-resolves when the active profile starts sharing primary addons`() =
        runTest(UnconfinedTestDispatcher()) {
            val seen = mutableListOf<List<String>>()
            val job = launch { layout.homeCatalogOrderKeys.collect { seen += it } }

            runCurrent()
            assertEquals(listOf("child.catalog"), seen.last())

            profiles.value = profiles.value.map {
                if (it.id == 2) it.copy(usesPrimaryAddons = true) else it
            }
            runCurrent()

            assertEquals(listOf("parent.catalog"), seen.last())
            job.cancel()
        }

    @Test
    fun `read-only profile catalog writes and remote applies are dropped`() = runTest {
        profiles.value = profiles.value.map {
            if (it.id == 2) it.copy(usesPrimaryAddons = true) else it
        }

        layout.setHomeCatalogOrderKeys(listOf("edited.by.child"))
        layout.setDisabledHomeCatalogKeys(listOf("parent.catalog"))
        assertFalse(layout.setCustomCatalogTitle("parent.catalog", "Mine", originalName = "Parent"))
        layout.applyCatalogSettingsFromRemote(
            SyncHomeCatalogPayload(items = listOf(SyncCatalogItem("addon", "movie", "remote"))),
            profileId = 2
        )

        assertEquals(listOf("parent.catalog"), layout.homeCatalogOrderKeys.first())
        assertEquals(emptyList<String>(), layout.disabledHomeCatalogKeys.first())
        assertEquals(emptyMap<String, String>(), layout.customCatalogTitles.first())
        assertEquals("""["parent.catalog"]""", stores.getValue(1).value[orderKey])
        assertEquals("""["child.catalog"]""", stores.getValue(2).value[orderKey])
    }

    @Test
    fun `own-addons profile writes are visible to its reads`() = runTest {
        layout.setHomeCatalogOrderKeys(listOf("child.reordered"))

        assertEquals(listOf("child.reordered"), layout.homeCatalogOrderKeys.first())
        assertEquals(listOf("child.reordered"), layout.getHomeCatalogSettingsState(2).orderKeys)
        assertEquals("""["parent.catalog"]""", stores.getValue(1).value[orderKey])
    }
}
