package com.nuvio.tv.data.local

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

/**
 * L9-05/67: a sync apply for profile P writes P's addon store even while another profile
 * is active; a read-only (usesPrimaryAddons) profile never writes. L9-08: one resolver.
 * Spec 31 P-Addons-3: rename writes the whole name map once per change.
 */
class AddonPreferencesProfileTest {
    private val activeProfileId = MutableStateFlow(1)
    private val profiles = MutableStateFlow(
        listOf(
            UserProfile(id = 1, name = "Parent", avatarColorHex = "#000000"),
            UserProfile(id = 2, name = "Own addons", avatarColorHex = "#000000"),
            UserProfile(id = 3, name = "Shares primary", avatarColorHex = "#000000", usesPrimaryAddons = true)
        )
    )
    private val stores = mutableMapOf<Int, TestPreferencesDataStore>()
    private val preferences: AddonPreferences

    init {
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } answers {
            synchronized(stores) { stores.getOrPut(firstArg()) { TestPreferencesDataStore() } }
        }
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns activeProfileId
        every { profileManager.profiles } returns profiles
        preferences = AddonPreferences(factory, profileManager)
    }

    @Test
    fun `explicit-profile writes land in that profile while another is active`() = runTest {
        activeProfileId.value = 1
        val defaults = preferences.getInstalledAddonUrls(1)

        assertTrue(preferences.setAddonOrder(listOf("https://b.example", "https://a.example"), profileId = 2))
        preferences.setUserSetNames(mapOf("https://a.example" to "A"), profileId = 2)
        preferences.setAddonEnabledStates(mapOf("https://b.example" to false), profileId = 2)

        assertEquals(listOf("https://b.example", "https://a.example"), preferences.getInstalledAddonUrls(2))
        assertEquals(defaults, preferences.getInstalledAddonUrls(1))
        assertEquals(emptyMap<String, String>(), preferences.userSetNames.first())
        activeProfileId.value = 2
        assertEquals(mapOf("https://a.example" to "A"), preferences.userSetNames.first())
        assertEquals(false, preferences.addonEnabledStates.first()["https://b.example"])
    }

    @Test
    fun `a read-only profile reads profile 1 and never writes`() = runTest {
        preferences.setAddonOrder(listOf("https://parent.example"), profileId = 1)

        assertFalse(preferences.setAddonOrder(listOf("https://child.example"), profileId = 3))
        preferences.setUserSetNames(mapOf("https://parent.example" to "renamed"), profileId = 3)

        assertEquals(listOf("https://parent.example"), preferences.getInstalledAddonUrls(3))
        assertEquals(listOf("https://parent.example"), preferences.getInstalledAddonUrls(1))
        activeProfileId.value = 3
        assertEquals(emptyMap<String, String>(), preferences.userSetNames.first())
        // The phone QR splice (active-profile write) is gated the same way.
        assertFalse(preferences.spliceAddonOrder(listOf("https://child.example"), removedUrls = emptyList()))
        assertEquals(listOf("https://parent.example"), preferences.getInstalledAddonUrls(1))
    }

    @Test
    fun `rename sets, trims, and removes on blank or the manifest name`() = runTest {
        val url = "https://a.example"
        preferences.setUserSetNames(mapOf("https://b.example" to "B"), profileId = 1)

        assertTrue(preferences.setUserSetName(url, "  Mine ", manifestName = "A"))
        assertEquals(mapOf("https://b.example" to "B", url to "Mine"), preferences.userSetNames.first())
        assertFalse(preferences.setUserSetName(url, "Mine", manifestName = "A")) // no change

        assertTrue(preferences.setUserSetName(url, "A", manifestName = "A"))
        assertEquals(mapOf("https://b.example" to "B"), preferences.userSetNames.first())

        preferences.setUserSetName(url, "Mine", manifestName = "A")
        assertTrue(preferences.setUserSetName(url, "   ", manifestName = "A"))
        assertEquals(mapOf("https://b.example" to "B"), preferences.userSetNames.first())
    }

    @Test
    fun `a read-only profile cannot rename`() = runTest {
        activeProfileId.value = 3
        assertFalse(preferences.setUserSetName("https://a.example", "Mine", manifestName = "A"))
        assertEquals(emptyMap<String, String>(), preferences.getUserSetNames(1))
    }

    @Test
    fun `addon owner resolution`() {
        val list = profiles.value
        assertEquals(1, addonOwnerProfileId(list, 1))
        assertEquals(2, addonOwnerProfileId(list, 2))
        assertEquals(1, addonOwnerProfileId(list, 3))
        assertEquals(4, addonOwnerProfileId(list, 4)) // unknown id: its own store
        assertFalse(isAddonReadOnly(list.map { it.copy(usesPrimaryAddons = true) }, 1))
    }
}
