package com.nuvio.tv.ui.screens.addon

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.HomeCatalogSettingsSyncService
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.TestPreferencesDataStore
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.UserProfile
import com.nuvio.tv.domain.repository.AddonRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * L5-10/29/51/73: catalog moves and toggles re-derive from the stored keys inside one
 * DataStore edit, so two presses made before the first write re-emits both apply.
 * FR-01/04: stored keys of catalogs the screen does not render (disabled addon, manifest
 * still loading) survive a press. FR-24: one sync push per actual write, none otherwise.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogOrderViewModelPressTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val disabledKey = stringPreferencesKey("disabled_home_catalog_keys")
    private val one = "addon_movie_one"
    private val two = "addon_movie_two"
    private val three = "addon_movie_three"

    /** A catalog of an addon the screen does not render (disabled or manifest loading). */
    private val unrendered = "other_movie_x"

    /** Pre-key-migration disabled key for catalog two (base url + type + id + name). */
    private val legacyTwo = "https://addon.example_movie_two_two"

    private val store = TestPreferencesDataStore(
        preferencesOf(disabledKey to """["$one"]""")
    )
    private val layout: LayoutPreferenceDataStore
    private val sync = mockk<HomeCatalogSettingsSyncService>(relaxed = true)

    init {
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } returns store
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns MutableStateFlow(1)
        every { profileManager.profiles } returns MutableStateFlow(
            listOf(UserProfile(id = 1, name = "Parent", avatarColorHex = "#000000"))
        )
        layout = LayoutPreferenceDataStore(factory, profileManager)
    }

    private fun viewModel(collectionList: List<Collection> = emptyList()): CatalogOrderViewModel {
        val collections = mockk<CollectionsDataStore>()
        every { collections.collections } returns flowOf(collectionList)
        return CatalogOrderViewModel(
            addonRepository = SingleAddonRepository(),
            collectionsDataStore = collections,
            layoutPreferenceDataStore = layout,
            homeCatalogSettingsSyncService = sync
        )
    }

    @Test
    fun `two rapid moves both apply`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.moveDown(one)
        viewModel.moveDown(one)
        advanceUntilIdle()

        assertEquals(listOf(two, three, one), layout.homeCatalogOrderKeys.first())
        verify(exactly = 2) { sync.triggerPush() }
    }

    @Test
    fun `a move past the top writes nothing and pushes nothing`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        val before = store.value

        viewModel.moveUp(one)
        advanceUntilIdle()

        assertEquals(before, store.value)
        verify(exactly = 0) { sync.triggerPush() }
    }

    @Test
    fun `a move keeps an unrendered saved order key in its slot`() = runTest(mainDispatcherRule.dispatcher) {
        layout.setHomeCatalogOrderKeys(listOf(one, unrendered, two, three))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.moveDown(one)
        advanceUntilIdle()

        assertEquals(listOf(two, unrendered, one, three), layout.homeCatalogOrderKeys.first())
        verify(exactly = 1) { sync.triggerPush() }
    }

    @Test
    fun `toggling a catalog twice restores the stored disabled keys`() = runTest(mainDispatcherRule.dispatcher) {
        val original = store.value[disabledKey]
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.toggleCatalogEnabled(two)
        viewModel.toggleCatalogEnabled(two)
        advanceUntilIdle()

        assertEquals(original, store.value[disabledKey])
        verify(exactly = 2) { sync.triggerPush() }
    }

    @Test
    fun `a toggle appends the disabled key after the rendered ones`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.toggleCatalogEnabled(three)
        advanceUntilIdle()

        assertEquals("""["$one","$three"]""", store.value[disabledKey])
        verify(exactly = 1) { sync.triggerPush() }
    }

    @Test
    fun `a toggle keeps an unrendered disabled key`() = runTest(mainDispatcherRule.dispatcher) {
        layout.setDisabledHomeCatalogKeys(listOf(unrendered, one))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.toggleCatalogEnabled(three)
        advanceUntilIdle()

        assertEquals("""["$unrendered","$one","$three"]""", store.value[disabledKey])
        verify(exactly = 1) { sync.triggerPush() }
    }

    @Test
    fun `a toggle on a legacy-key disabled catalog clears the legacy key`() = runTest(mainDispatcherRule.dispatcher) {
        layout.setDisabledHomeCatalogKeys(listOf(legacyTwo))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.toggleCatalogEnabled(two)
        advanceUntilIdle()

        assertEquals(emptyList<String>(), layout.disabledHomeCatalogKeys.first().toList())
    }

    @Test
    fun `a toggle elsewhere migrates a legacy disabled key to the current key`() = runTest(mainDispatcherRule.dispatcher) {
        layout.setDisabledHomeCatalogKeys(listOf(legacyTwo))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.toggleCatalogEnabled(three)
        advanceUntilIdle()

        assertEquals(listOf(two, three), layout.disabledHomeCatalogKeys.first().toList())
    }

    @Test
    fun `a move appends rendered keys the saved order lacked after the unrendered slot`() = runTest(mainDispatcherRule.dispatcher) {
        layout.setHomeCatalogOrderKeys(listOf(unrendered, two))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.moveDown(two)
        advanceUntilIdle()

        assertEquals(listOf(unrendered, one, two, three), layout.homeCatalogOrderKeys.first())
    }

    @Test
    fun `follow mode keeps an unrendered saved order key when a collection moves`() = runTest(mainDispatcherRule.dispatcher) {
        layout.setFollowAddonsOrder(true)
        val collection = "collection_c"
        layout.setHomeCatalogOrderKeys(listOf(one, two, three, unrendered, collection))
        val viewModel = viewModel(listOf(Collection(id = "c", title = "C")))
        advanceUntilIdle()

        viewModel.moveUp(collection)
        advanceUntilIdle()

        val saved = layout.homeCatalogOrderKeys.first()
        assertEquals(listOf(collection, one, two, three), saved.filterNot { it == unrendered })
        assertEquals(3, saved.indexOf(unrendered))
    }

    @Test
    fun `follow mode moves a collection above the addon block`() = runTest(mainDispatcherRule.dispatcher) {
        layout.setFollowAddonsOrder(true)
        val collection = "collection_c"
        val viewModel = viewModel(listOf(Collection(id = "c", title = "C")))
        advanceUntilIdle()

        viewModel.moveUp(collection)
        advanceUntilIdle()
        // Already above every addon block: no move, no write, no push.
        viewModel.moveUp(collection)
        advanceUntilIdle()

        assertEquals(listOf(collection, one, two, three), layout.homeCatalogOrderKeys.first())
        verify(exactly = 1) { sync.triggerPush() }
    }

    @Test
    fun `rename writes once and pushes once, its own name resets, collections are skipped`() =
        runTest(mainDispatcherRule.dispatcher) {
            val collection = "collection_c"
            val viewModel = viewModel(listOf(Collection(id = "c", title = "C")))
            advanceUntilIdle()

            viewModel.renameCatalog(one, " Top picks ")
            viewModel.renameCatalog(one, "Top picks") // unchanged: no write, no push
            viewModel.renameCatalog(collection, "Folder") // Home ignores collection titles
            advanceUntilIdle()

            assertEquals(mapOf(one to "Top picks"), layout.customCatalogTitles.first())
            val renamed = viewModel.uiState.value.items.first { it.key == one }
            assertEquals("Top picks", renamed.catalogName)
            assertEquals("one", renamed.originalName)
            assertTrue(renamed.isRenamed)
            verify(exactly = 1) { sync.triggerPush() }

            viewModel.renameCatalog(one, "one")
            advanceUntilIdle()

            assertEquals(emptyMap<String, String>(), layout.customCatalogTitles.first())
            verify(exactly = 2) { sync.triggerPush() }
        }

    @Test
    fun `follow mode pins addon catalogs and collection rows carry their folder count`() =
        runTest(mainDispatcherRule.dispatcher) {
            layout.setFollowAddonsOrder(true)
            val viewModel = viewModel(listOf(Collection(id = "c", title = "C")))
            advanceUntilIdle()

            val items = viewModel.uiState.value.items.associateBy { it.key }
            assertTrue(items.getValue(one).pinnedByFollow)
            assertFalse(items.getValue(one).isCollection)
            assertFalse(items.getValue("collection_c").pinnedByFollow)
            assertEquals(0, items.getValue("collection_c").folderCount)
        }

    private class SingleAddonRepository : AddonRepository {
        private val addon = Addon(
            id = "addon",
            name = "Addon",
            version = "1",
            description = null,
            logo = null,
            baseUrl = "https://addon.example",
            catalogs = listOf("one", "two", "three").map { id ->
                CatalogDescriptor(type = ContentType.MOVIE, id = id, name = id)
            },
            types = listOf(ContentType.MOVIE),
            resources = emptyList()
        )

        override fun getInstalledAddons(): Flow<List<Addon>> = flowOf(listOf(addon))
        override suspend fun fetchAddon(baseUrl: String): NetworkResult<Addon> = error("unused")
        override suspend fun addAddon(url: String) = error("unused")
        override suspend fun removeAddon(url: String) = error("unused")
        override suspend fun spliceAddonOrder(urls: List<String>, removedUrls: kotlin.collections.Collection<String>) = error("unused")
        override suspend fun moveAddon(url: String, direction: Int) = error("unused")
        override suspend fun setAddonEnabled(url: String, enabled: Boolean) = error("unused")
    }
}
