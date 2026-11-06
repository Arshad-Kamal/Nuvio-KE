package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.UserProfile
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 40 S1: per-profile "search on submit" marks, keyed by canonical url, pruned with the addon. */
class AddonPreferencesSearchOnSubmitTest {
    private val orderKey = stringPreferencesKey("installed_addon_urls_ordered")
    private val a = "https://a.example"
    private val b = "https://b.example"

    private val stores = mutableMapOf<Int, TestPreferencesDataStore>()

    private val parentOnly = listOf(UserProfile(id = 1, name = "Parent", avatarColorHex = "#000000"))

    private fun seed(urls: List<String>, profiles: List<UserProfile> = parentOnly) {
        for (profile in profiles) {
            stores[profile.id] = TestPreferencesDataStore(preferencesOf(orderKey to encodeStringList(urls)))
        }
    }

    /** Preferences seen by [activeProfile] over the current [stores] (no reseed). */
    private fun preferencesAs(activeProfile: Int = 1, profiles: List<UserProfile> = parentOnly): AddonPreferences {
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } answers { stores.getValue(firstArg()) }
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns MutableStateFlow(activeProfile)
        every { profileManager.profiles } returns MutableStateFlow(profiles)
        return AddonPreferences(factory, profileManager)
    }

    private fun preferencesWith(urls: List<String>): AddonPreferences {
        seed(urls)
        return preferencesAs()
    }

    @Test
    fun `marks are stored canonically and toggle off`() = runTest {
        val preferences = preferencesWith(listOf(a, b))

        assertTrue(preferences.setSearchOnSubmit("$a/manifest.json", onSubmit = true))
        assertEquals(setOf(a), preferences.searchOnSubmitUrls.first())

        assertFalse(preferences.setSearchOnSubmit(a, onSubmit = true))
        assertTrue(preferences.setSearchOnSubmit(a, onSubmit = false))
        assertEquals(emptySet<String>(), preferences.searchOnSubmitUrls.first())
    }

    @Test
    fun `a mark in another url case counts as set and unsets`() = runTest {
        val preferences = preferencesWith(listOf(a, b))
        assertTrue(preferences.setSearchOnSubmit(a.uppercase(), onSubmit = true))

        assertFalse(preferences.setSearchOnSubmit(a, onSubmit = true))
        assertTrue(preferences.setSearchOnSubmit(a, onSubmit = false))
        assertEquals(emptySet<String>(), preferences.searchOnSubmitUrls.first())
    }

    @Test
    fun `removing an addon drops its mark`() = runTest {
        val preferences = preferencesWith(listOf(a, b))
        preferences.setSearchOnSubmit(a, onSubmit = true)
        preferences.setSearchOnSubmit(b, onSubmit = true)

        preferences.removeAddon(a)

        assertEquals(setOf(b), preferences.searchOnSubmitUrls.first())
    }

    @Test
    fun `an order write keeps marks of addons still installed only`() = runTest {
        val preferences = preferencesWith(listOf(a, b))
        preferences.setSearchOnSubmit(a, onSubmit = true)
        preferences.setSearchOnSubmit(b, onSubmit = true)

        // A sync apply that no longer lists a.
        preferences.setAddonOrder(listOf(b))

        assertEquals(setOf(b), preferences.searchOnSubmitUrls.first())
    }

    @Test
    fun `a pure reorder keeps every mark`() = runTest {
        val preferences = preferencesWith(listOf(a, b))
        preferences.setSearchOnSubmit(a, onSubmit = true)

        preferences.moveAddon(a, direction = 1)
        preferences.setAddonOrder(listOf(a, b))

        assertEquals(setOf(a), preferences.searchOnSubmitUrls.first())
    }

    @Test
    fun `a splice that re-spells an url keeps its mark`() = runTest {
        val preferences = preferencesWith(listOf(a, b))
        preferences.setSearchOnSubmit(a, onSubmit = true)

        preferences.spliceAddonOrder(listOf("https://A.example", b), removedUrls = emptyList())

        assertEquals(setOf(a), preferences.searchOnSubmitUrls.first())
    }

    @Test
    fun `a read-only profile reads profile 1's marks and cannot write`() = runTest {
        val profiles = parentOnly + UserProfile(id = 2, name = "Kid", avatarColorHex = "#000000", usesPrimaryAddons = true)
        seed(listOf(a), profiles)
        preferencesAs(activeProfile = 1, profiles = profiles).setSearchOnSubmit(a, onSubmit = true)
        val stored1 = stores.getValue(1).value

        val readOnly = preferencesAs(activeProfile = 2, profiles = profiles)

        assertEquals(setOf(a), readOnly.searchOnSubmitUrls.first())
        assertFalse(readOnly.setSearchOnSubmit(a, onSubmit = false))
        assertEquals(stored1, stores.getValue(1).value)
    }
}
