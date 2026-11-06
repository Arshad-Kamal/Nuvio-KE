package com.nuvio.tv.data.local

import android.content.Context
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.Collection
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * L4-23/24/85/86: collection reorder and import merge are read-modify-write inside the
 * store's edit{}, and add is an upsert, so no caller-side snapshot can resurrect,
 * drop or duplicate a row. The empty-list tests pin each mutation's stored-key rule
 * (FR-26: one edit helper, per-path behaviour unchanged).
 */
class CollectionsDataStoreMutationTest {

    @Test
    fun `move runs against the stored list so a deleted collection stays gone`() = runTest {
        val store = harness()
        store.setCollections(listOf(collection("a"), collection("b"), collection("c")))
        store.removeCollection("a")

        store.moveCollection("c", offset = -1)

        assertEquals(listOf("c", "b"), store.collections.first().map { it.id })
    }

    @Test
    fun `consecutive moves both apply`() = runTest {
        val store = harness()
        store.setCollections(listOf(collection("a"), collection("b"), collection("c")))

        store.moveCollection("c", offset = -1)
        store.moveCollection("c", offset = -1)

        assertEquals(listOf("c", "a", "b"), store.collections.first().map { it.id })
    }

    @Test
    fun `move past either end or of an unknown id leaves the order unchanged`() = runTest {
        val store = harness()
        store.setCollections(listOf(collection("a"), collection("b")))

        store.moveCollection("a", offset = -1)
        store.moveCollection("b", offset = 1)
        store.moveCollection("gone", offset = 1)

        assertEquals(listOf("a", "b"), store.collections.first().map { it.id })
    }

    @Test
    fun `adding the same id twice stores one collection with the latest content`() = runTest {
        val store = harness()

        store.addCollection(collection("a", title = "First"))
        store.addCollection(collection("a", title = "Second"))

        assertEquals(listOf("a" to "Second"), store.collections.first().map { it.id to it.title })
    }

    @Test
    fun `upsert replaces existing ids in place and appends new ones`() = runTest {
        val store = harness()
        store.setCollections(listOf(collection("a"), collection("b", title = "Old")))

        store.upsertCollections(listOf(collection("c"), collection("b", title = "New")))

        assertEquals(
            listOf("a" to "a", "b" to "New", "c" to "c"),
            store.collections.first().map { it.id to it.title }
        )
    }

    @Test
    fun `removing the last collection removes the stored key`() = runTest {
        val store = harness()
        store.setCollections(listOf(collection("a")))

        store.removeCollection("a")

        assertNull(store.exportCurrentProfileJson())
    }

    @Test
    fun `updating an empty store writes an empty array`() = runTest {
        val store = harness()

        store.updateCollection(collection("a"))

        assertEquals("[]", store.exportCurrentProfileJson())
    }

    @Test
    fun `empty upsert and unknown move on an empty store write nothing`() = runTest {
        val store = harness()

        store.upsertCollections(emptyList())
        store.moveCollection("a", offset = 1)

        assertNull(store.exportCurrentProfileJson())
    }

    private fun collection(id: String, title: String = id) = Collection(id = id, title = title)

    private fun harness(): CollectionsDataStore {
        val backing = TestPreferencesDataStore()
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } returns backing
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns MutableStateFlow(1)
        return CollectionsDataStore(
            appContext = mockk<Context>(relaxed = true),
            factory = factory,
            profileManager = profileManager
        )
    }
}
