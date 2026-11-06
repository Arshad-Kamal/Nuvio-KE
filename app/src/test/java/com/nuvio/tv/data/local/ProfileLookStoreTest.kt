package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.domain.model.ProfileLookChoice
import com.nuvio.tv.domain.model.ProfileLookEntry
import com.nuvio.tv.domain.model.ProfileLookPick
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Spec 44a: the per-profile look entry round-trips, clears, and reads junk as none. */
class ProfileLookStoreTest {

    private val stores = mutableMapOf<Int, DataStore<Preferences>>()
    private val factory = mockk<ProfileDataStoreFactory>().also { f ->
        every { f.get(any(), ProfileLookStore.FEATURE) } answers { stores.getOrPut(firstArg()) { TestPreferencesDataStore() } }
    }
    private val store = ProfileLookStore(factory)

    private val entry = ProfileLookEntry(
        avatar = ProfileLookChoice(ProfileLookPick.Bundled("fox"), name = "Sara", syncedKey = null),
        background = ProfileLookChoice(ProfileLookPick.Photo("background-1-a"), name = "Sara", syncedKey = "dunes")
    )

    @Test
    fun `an entry round-trips per profile, null snapshot fields included`() = runTest {
        assertNull(store.entryFor(2).first())
        assertTrue(store.write(2, entry))
        assertEquals(entry, store.entryFor(2).first())
        assertNull(store.entryFor(3).first())
    }

    @Test
    fun `writing a part-less entry or null clears it`() = runTest {
        store.write(2, entry)
        store.write(2, entry.copy(background = null))
        assertEquals(entry.copy(background = null), store.entryFor(2).first())
        store.write(2, null)
        assertNull(store.entryFor(2).first())
        assertEquals(0, (stores.getValue(2) as TestPreferencesDataStore).value.asMap().size)
    }

    @Test
    fun `an unknown pick kind or an empty value reads as no pick`() {
        val prefs = mutablePreferencesOf(
            stringPreferencesKey("avatar_pick") to "sticker:cat",
            stringPreferencesKey("background_pick") to "photo:"
        )
        assertNull(ProfileLookStore.decode(prefs))
        assertEquals(ProfileLookPick.Bundled("owl"), ProfileLookStore.decodePick(ProfileLookStore.encodePick(ProfileLookPick.Bundled("owl"))))
    }

    @Test
    fun `an unreadable file reads as none and a failed write reports false`() = runTest {
        stores[4] = object : TestPreferencesDataStore() {
            override val data = flow<Preferences> { throw IOException("unreadable") }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                throw IOException("disk full")
        }
        assertNull(store.entryFor(4).first())
        assertFalse(store.write(4, entry))
    }
}
