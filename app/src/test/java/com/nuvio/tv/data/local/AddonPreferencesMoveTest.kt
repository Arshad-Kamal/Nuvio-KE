package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.UserProfile
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L5-68: an addon move edits the STORED list in one DataStore edit, so an addon the
 * manager screen has not rendered yet (manifest still loading) is never dropped, and
 * each move applies on top of the previous one. Stored bytes match what setAddonOrder writes.
 * L5-b (QB2): the phone config page's change splices into the stored list the same way.
 */
class AddonPreferencesMoveTest {
    private val orderKey = stringPreferencesKey("installed_addon_urls_ordered")
    private val statesKey = stringPreferencesKey("installed_addon_enabled_states")
    private val a = "https://a.example"
    private val b = "https://b.example"
    private val c = "https://c.example"

    private val stores = mutableMapOf<Int, TestPreferencesDataStore>()

    private fun preferencesWith(urls: List<String>, states: Map<String, Boolean>): AddonPreferences {
        stores[1] = TestPreferencesDataStore(
            preferencesOf(
                orderKey to encodeStringList(urls),
                statesKey to encodeBooleanMap(states)
            )
        )
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } answers { stores.getValue(firstArg()) }
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns MutableStateFlow(1)
        every { profileManager.profiles } returns MutableStateFlow(
            listOf(UserProfile(id = 1, name = "Parent", avatarColorHex = "#000000"))
        )
        return AddonPreferences(factory, profileManager)
    }

    @Test
    fun `a move keeps a stored addon the UI list does not show`() = runTest {
        // The screen showed only [a, b]: c's manifest was not cached yet.
        val preferences = preferencesWith(listOf(a, b, c), mapOf(a to true, b to true, c to false))

        assertTrue(preferences.moveAddon(b, direction = -1))

        assertEquals(listOf(b, a, c), preferences.getInstalledAddonUrls(1))
        assertEquals(mapOf(b to true, a to true, c to false), preferences.getAddonEnabledStates(1))
    }

    @Test
    fun `sequential moves compose`() = runTest {
        val preferences = preferencesWith(listOf(a, b, c), emptyMap())

        preferences.moveAddon(c, direction = -1)
        preferences.moveAddon(c, direction = -1)

        assertEquals(listOf(c, a, b), preferences.getInstalledAddonUrls(1))
    }

    @Test
    fun `a move past the edge or of an unknown url writes nothing`() = runTest {
        val preferences = preferencesWith(listOf(a, b), mapOf(a to true, b to true))
        val before = stores.getValue(1).value

        assertFalse(preferences.moveAddon(a, direction = -1))
        assertFalse(preferences.moveAddon("https://unknown.example", direction = 1))

        assertEquals(before, stores.getValue(1).value)
    }

    @Test
    fun `a move stores the same bytes as setAddonOrder with the moved list`() = runTest {
        val states = mapOf(a to true, b to false, c to true)
        val moved = preferencesWith(listOf(a, b, c), states)
        moved.moveAddon(a, direction = 1)
        val viaMove = stores.getValue(1).value

        val ordered = preferencesWith(listOf(a, b, c), states)
        ordered.setAddonOrder(listOf(b, a, c))
        val viaSetOrder = stores.getValue(1).value

        assertEquals(viaSetOrder[orderKey], viaMove[orderKey])
        assertEquals(viaSetOrder[statesKey], viaMove[statesKey])
        // Golden bytes: both paths share one writer, so pin the stored JSON itself.
        assertEquals("""["$b","$a","$c"]""", viaMove[orderKey])
        assertEquals("""{"$b":false,"$a":true,"$c":true}""", viaMove[statesKey])
    }

    @Test
    fun `a QR page change keeps a stored addon the page never showed, with its state`() = runTest {
        // The page showed [a, b]; c's manifest had not loaded. The phone moved b up.
        val preferences = preferencesWith(listOf(a, c, b), mapOf(a to true, b to true, c to false))

        assertTrue(preferences.spliceAddonOrder(listOf(b, a), removedUrls = emptyList()))

        assertEquals(listOf(b, c, a), preferences.getInstalledAddonUrls(1))
        assertEquals(mapOf(b to true, c to false, a to true), preferences.getAddonEnabledStates(1))

        val before = stores.getValue(1).value
        assertFalse(preferences.spliceAddonOrder(listOf(b, a), removedUrls = emptyList()))
        assertEquals(before, stores.getValue(1).value)
    }

    @Test
    fun `splice fills page slots in proposed order, drops removed, overflow follows`() {
        val x = "https://x.example"
        val d = "https://d.example"
        assertEquals(
            listOf(c, x, a, d),
            splicedAddonOrder(stored = listOf(a, x, b, c), proposed = listOf(c, a, d), removed = listOf(b))
        )
        // Matching ignores case; the proposed spelling is stored.
        assertEquals(
            listOf(x, "https://A.example"),
            splicedAddonOrder(stored = listOf(x, a), proposed = listOf("https://A.example"), removed = emptyList())
        )
        assertEquals(listOf(x), splicedAddonOrder(stored = listOf(a, x), proposed = emptyList(), removed = listOf(a)))
        // A removed spelling with a manifest suffix still matches.
        assertEquals(listOf(x), splicedAddonOrder(listOf(a, x), emptyList(), removed = listOf("$a/manifest.json")))
        // Removed but also proposed: the proposal keeps it.
        assertEquals(listOf(b, a), splicedAddonOrder(listOf(a, b), proposed = listOf(b, a), removed = listOf(a)))
        // Duplicate proposed spellings take one slot.
        assertEquals(listOf(a, b), splicedAddonOrder(listOf(a, b), listOf(a, "https://A.example", b), emptyList()))
    }
}
