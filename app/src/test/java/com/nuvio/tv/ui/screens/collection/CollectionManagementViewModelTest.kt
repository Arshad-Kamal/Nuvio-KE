package com.nuvio.tv.ui.screens.collection

import android.content.Context
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.CollectionSyncService
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.TestPreferencesDataStore
import com.nuvio.tv.domain.model.Collection
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class CollectionManagementViewModelTest {

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** L4-23/85: the move used to rebuild the list from the UI snapshot that still held "a". */
    @Test
    fun `move pressed right after a delete does not resurrect the deleted collection`() = runTest {
        val store = collectionsStore()
        store.setCollections(listOf(collection("a"), collection("b"), collection("c")))
        val viewModel = CollectionManagementViewModel(store, mockk<CollectionSyncService>(relaxed = true), mockk<Context>(relaxed = true))
        advanceUntilIdle()

        // Both presses land before the delete's DataStore emission reaches the UI state.
        viewModel.deleteCollection("a")
        viewModel.moveDown("b")
        advanceUntilIdle()

        assertEquals(listOf("c", "b"), store.collections.first().map { it.id })
    }

    /** L4-46 (KT-JSON-03): the URL-import body is capped before it reaches the parser. */
    @Test
    fun `capped string returns a body within the cap and rejects one past it`() {
        assertEquals("[1234]", "[1234]".toResponseBody().cappedString(maxBytes = 6))
        assertThrows(IOException::class.java) {
            "[12345]".toResponseBody().cappedString(maxBytes = 6)
        }
    }

    private fun collection(id: String) = Collection(id = id, title = id)

    private fun collectionsStore(): CollectionsDataStore {
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
