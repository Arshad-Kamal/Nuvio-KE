package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.domain.contentfilter.ContentFilterSettings
import com.nuvio.tv.domain.contentfilter.OwnPack
import com.nuvio.tv.domain.contentfilter.OwnTag
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Spec 117 S2: per-profile content filters round-trip, clear, and read as Unreadable on junk or a bad file. */
class ProfileContentFilterStoreTest {

    private val stores = mutableMapOf<Int, DataStore<Preferences>>()
    private val factory = mockk<ProfileDataStoreFactory>().also { f ->
        every { f.get(any(), ProfileContentFilterStore.FEATURE) } answers { stores.getOrPut(firstArg()) { TestPreferencesDataStore() } }
    }
    private val store = ProfileContentFilterStore(factory)

    private val settings = ContentFilterSettings(
        enabledPacks = setOf("horror", "own:1"),
        myWords = listOf("dragon ball", "wwe"),
        ownPacks = listOf(OwnPack("own:1", "Grandma's list", listOf("kiss"), listOf(27), listOf("Horror"), listOf(OwnTag(1299, "monster"))))
    )

    @Test
    fun `settings round-trip per profile and other profiles read empty`() = runTest {
        assertEquals(empty, store.settingsFor(2).first())
        assertTrue(store.write(2, settings))
        assertEquals(ContentFilterRead.Stored(settings), store.settingsFor(2).first())
        assertEquals(empty, store.settingsFor(1).first())
    }

    @Test
    fun `writing empty settings removes the key`() = runTest {
        assertTrue(store.write(2, settings))
        assertTrue(store.write(2, ContentFilterSettings()))
        assertEquals(empty, store.settingsFor(2).first())
        assertEquals(0, (stores.getValue(2) as TestPreferencesDataStore).value.asMap().size)
    }

    @Test
    fun `unknown keys in the stored value are ignored`() {
        val prefs = mutablePreferencesOf(KEY to """{"enabledPacks":["horror"],"regex":"x+","future":{"a":1}}""")
        assertEquals(ContentFilterRead.Stored(ContentFilterSettings(enabledPacks = setOf("horror"))), ProfileContentFilterStore.decode(prefs))
    }

    @Test
    fun `an undecodable value reads as unreadable`() {
        listOf("not json", """{"enabledPacks":"horror"}""", """{"ownPacks":[{"name":"no id"}]}""").forEach { raw ->
            assertEquals(raw, ContentFilterRead.Unreadable, ProfileContentFilterStore.decode(mutablePreferencesOf(KEY to raw)))
        }
    }

    @Test
    fun `an oversized value reads as unreadable without decoding`() {
        val raw = """{"myWords":["${"a".repeat(ProfileContentFilterStore.MAX_STORED_CHARS)}"]}"""
        assertEquals(ContentFilterRead.Unreadable, ProfileContentFilterStore.decode(mutablePreferencesOf(KEY to raw)))
    }

    @Test
    fun `an unreadable file emits unreadable once and a failed write reports false`() = runTest {
        stores[4] = object : TestPreferencesDataStore() {
            override val data = flow<Preferences> { throw IOException("unreadable") }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                throw IOException("disk full")
        }
        assertEquals(listOf(ContentFilterRead.Unreadable), store.settingsFor(4).toList())
        assertFalse(store.write(4, settings))
    }

    @Test
    fun `clearPreviewOf clears only the primary's preview of that profile`() = runTest {
        assertTrue(store.write(1, ContentFilterSettings(enabledPacks = setOf("horror"), previewProfileId = 3)))
        assertTrue(store.clearPreviewOf(2))
        assertEquals(3, (store.settingsFor(1).first() as ContentFilterRead.Stored).settings.previewProfileId)
        assertTrue(store.clearPreviewOf(3))
        assertEquals(ContentFilterRead.Stored(ContentFilterSettings(enabledPacks = setOf("horror"))), store.settingsFor(1).first())
    }

    @Test
    fun `clearPreviewOf leaves an undecodable value alone and reports a failed write`() = runTest {
        val junk = TestPreferencesDataStore(mutablePreferencesOf(KEY to "not json"))
        stores[1] = junk
        assertTrue(store.clearPreviewOf(3))
        assertEquals("not json", junk.value[KEY])
        stores[1] = object : TestPreferencesDataStore() {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                throw IOException("disk full")
        }
        assertFalse(store.clearPreviewOf(3))
    }

    @Test
    fun `writeEdited keeps the stored preview, drops the edit's own, and writes over an undecodable value`() = runTest {
        assertTrue(store.write(1, ContentFilterSettings(enabledPacks = setOf("horror"), previewProfileId = 3)))
        // The editor opened before preview was turned off elsewhere: its stale value never comes back.
        assertTrue(store.writeEdited(1, settings.copy(previewProfileId = 2)))
        assertEquals(ContentFilterRead.Stored(settings.copy(previewProfileId = 3)), store.settingsFor(1).first())
        assertTrue(store.clearPreviewOf(3))
        assertTrue(store.writeEdited(1, settings.copy(previewProfileId = 3)))
        assertEquals(ContentFilterRead.Stored(settings), store.settingsFor(1).first())

        stores[2] = TestPreferencesDataStore(mutablePreferencesOf(KEY to "not json"))
        assertTrue(store.writeEdited(2, settings))
        assertEquals(ContentFilterRead.Stored(settings), store.settingsFor(2).first())
        stores[4] = object : TestPreferencesDataStore() {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                throw IOException("disk full")
        }
        assertFalse(store.writeEdited(4, settings))
    }

    @Test
    fun `setPreview changes only the primary's preview id and refuses an unreadable value`() = runTest {
        assertTrue(store.write(1, settings))
        assertTrue(store.setPreview(3))
        assertEquals(ContentFilterRead.Stored(settings.copy(previewProfileId = 3)), store.settingsFor(1).first())
        assertEquals("other profiles untouched", empty, store.settingsFor(3).first())
        assertTrue(store.setPreview(null))
        assertEquals(ContentFilterRead.Stored(settings), store.settingsFor(1).first())

        stores.remove(1)
        assertTrue(store.setPreview(1))
        assertTrue(store.setPreview(null))
        assertEquals("back to empty settings removes the key", 0, (stores.getValue(1) as TestPreferencesDataStore).value.asMap().size)

        val junk = TestPreferencesDataStore(mutablePreferencesOf(KEY to "not json"))
        stores[1] = junk
        assertFalse(store.setPreview(3))
        assertEquals("not json", junk.value[KEY])
        stores[1] = object : TestPreferencesDataStore() {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                throw IOException("disk full")
        }
        assertFalse(store.setPreview(3))
    }

    private companion object {
        val KEY = stringPreferencesKey("settings")
        val empty = ContentFilterRead.Stored(ContentFilterSettings())
    }
}
