package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1b pins for the binge-group cache: the {"g","at"} envelope round-trip, the
 * legacy bare-string decode fallback, removal, and that a legacy bare value
 * (no parsable save time) evicts first past the cap.
 */
class BingeGroupCacheDataStoreTest {

    @Test
    fun `replace then get round-trips the group`() = runTest {
        val harness = harness()

        harness.store.replace("tt1", "group-a")

        assertEquals("group-a", harness.store.get("tt1"))
        // Stored as the timestamped envelope, not the bare string.
        assertTrue(harness.rawValue("tt1")?.startsWith("{") == true)
    }

    @Test
    fun `legacy bare group value still reads`() = runTest {
        val harness = harness()
        harness.seed("tt1", "group-a")

        assertEquals("group-a", harness.store.get("tt1"))
    }

    @Test
    fun `replace with null removes the entry`() = runTest {
        val harness = harness()
        harness.store.replace("tt1", "group-a")

        harness.store.replace("tt1", null)

        assertNull(harness.store.get("tt1"))
        assertNull(harness.rawValue("tt1"))
    }

    @Test
    fun `replace with a nameless group removes the entry (Spec 47 D2)`() = runTest {
        val harness = harness()
        harness.store.replace("tt1", "com.aiostreams.viren070|1080p|WEB-DL")

        harness.store.replace("tt1", "com.aiostreams.viren070|")

        assertNull(harness.store.get("tt1"))
        assertNull(harness.rawValue("tt1"))
    }

    @Test
    fun `a nameless group stored before Spec 47 reads as none`() = runTest {
        val harness = harness()
        harness.seed("tt1", "com.aiostreams.viren070|")
        harness.seed("tt2", """{"g":"com.aiostreams.viren070|","at":1}""")

        assertNull(harness.store.get("tt1"))
        assertNull(harness.store.get("tt2"))
    }

    @Test
    fun `legacy bare value without a save time evicts first`() = runTest {
        val harness = harness()
        // Bare value: savedAtMsOf() reads no "at", so it sorts oldest and the
        // cap breach must take it before any enveloped entry.
        harness.seed("zz-legacy", "group-a")
        repeat(500) { index -> harness.store.replace("filler$index", "g$index") }

        assertNull(harness.rawValue("zz-legacy"))
        assertEquals(500, harness.bingeKeyCount())
    }

    private fun harness(): Harness {
        val backing = TestPreferencesDataStore()
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } returns backing
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns MutableStateFlow(1)
        return Harness(BingeGroupCacheDataStore(factory, profileManager), backing)
    }

    private class Harness(val store: BingeGroupCacheDataStore, val backing: TestPreferencesDataStore) {
        suspend fun seed(contentId: String, bareValue: String) {
            backing.updateData { prefs ->
                prefs.toMutablePreferences().apply {
                    this[stringPreferencesKey("bg_$contentId")] = bareValue
                }.toPreferences()
            }
        }

        fun rawValue(contentId: String): String? = backing.value[stringPreferencesKey("bg_$contentId")]

        fun bingeKeyCount(): Int = backing.value.asMap().keys.count { it.name.startsWith("bg_") }
    }
}
