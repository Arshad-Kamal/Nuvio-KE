package com.nuvio.tv.ui.screens.collection

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.nuvio.tv.core.sync.CollectionSyncService
import com.nuvio.tv.core.tmdb.TmdbCollectionSourceResolver
import com.nuvio.tv.core.tmdb.TmdbSourceImportMetadata
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonCatalogCollectionSource
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogExtra
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.CollectionFolder
import com.nuvio.tv.domain.model.FolderViewMode
import com.nuvio.tv.domain.model.TmdbCollectionSource
import com.nuvio.tv.domain.model.TmdbCollectionSourceType
import com.nuvio.tv.domain.repository.AddonRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CollectionEditorViewModelTest {

    private val mainDispatcher = StandardTestDispatcher()
    private val collectionsDataStore = mockk<CollectionsDataStore>(relaxed = true)
    private val resolver = mockk<TmdbCollectionSourceResolver>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        var nextId = 0
        every { collectionsDataStore.generateId() } answers { "id-${nextId++}" }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** L4-24/86: a double-pressed Save persisted (and pushed) twice and popped twice. */
    @Test
    fun `double save persists once and completes once`() = runTest {
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.addFolder()
        viewModel.toggleCatalogSource(
            AvailableCatalog(
                addonId = "addon",
                addonName = "Addon",
                type = "movie",
                catalogId = "popular",
                catalogName = "Popular"
            )
        )
        viewModel.saveFolderEdit()
        var completions = 0

        viewModel.save { completions++ }
        viewModel.save { completions++ }
        advanceUntilIdle()

        coVerify(exactly = 1) { collectionsDataStore.addCollection(any()) }
        assertEquals(1, completions)
    }

    /**
     * L4-87: the TMDB fetch result used to land in whatever folder was open on completion.
     * Each async entry point captures the launch folder on its own, so all three are run.
     */
    @Test
    fun `tmdb source fetched for one folder is dropped when another folder is open`() = runTest {
        for ((entryPoint, startAdd) in tmdbEntryPoints) {
            val gate = CompletableDeferred<Unit>()
            stubCollectionFetch(gate)
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.addFolder()
            startAdd(viewModel)
            runCurrent()
            // The fetch is still gated: nothing was added synchronously.
            assertTrue(entryPoint, viewModel.uiState.value.editingFolder?.sources.orEmpty().isEmpty())

            viewModel.cancelFolderEdit()
            viewModel.addFolder()
            val secondFolderId = viewModel.uiState.value.editingFolder?.id
            gate.complete(Unit)
            advanceUntilIdle()

            val open = viewModel.uiState.value.editingFolder
            assertEquals(entryPoint, secondFolderId, open?.id)
            assertTrue(entryPoint, open?.sources.orEmpty().isEmpty())
        }
    }

    @Test
    fun `tmdb source fetched for the still open folder is added to it`() = runTest {
        for ((entryPoint, startAdd) in tmdbEntryPoints) {
            val gate = CompletableDeferred<Unit>()
            stubCollectionFetch(gate)
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.addFolder()
            startAdd(viewModel)
            runCurrent()

            gate.complete(Unit)
            advanceUntilIdle()

            val source = viewModel.uiState.value.editingFolder?.sources?.single() as TmdbCollectionSource
            assertEquals(entryPoint, 10, source.tmdbId)
        }
    }

    /**
     * L5-04 (KT-CONC-03): a cancelled metadata import used to be caught by runCatching and
     * carried on as a null-metadata add (or a search error). Cancellation must end the job.
     */
    @Test
    fun `cancelled tmdb metadata import adds nothing and reports no error`() = runTest {
        for ((entryPoint, startAdd) in tmdbEntryPoints) {
            every { resolver.parseTmdbId("10") } returns 10
            coEvery { resolver.collectionImportMetadata(10) } throws CancellationException("cleared")
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.addFolder()

            startAdd(viewModel)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(entryPoint, state.editingFolder?.sources.orEmpty().isEmpty())
            assertNull(entryPoint, state.tmdbSearchError)
        }
    }

    /**
     * Spec 28 28k deferral: Back mid-fetch then Edit on another source used to let the late
     * result replace that source. Closing the panel cancels the add.
     */
    @Test
    fun `a tmdb add left by Back does not land in the next edit`() = runTest {
        val gate = CompletableDeferred<Unit>()
        stubCollectionFetch(gate)
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.addFolder()
        viewModel.addTmdbSources(listOf(discoverSource("First"), discoverSource("Second")))
        val stored = viewModel.uiState.value.editingFolder?.sources.orEmpty()
        assertEquals(2, stored.size)

        viewModel.editTmdbSource(collectionSourceKey(stored[0]))
        viewModel.setTmdbBuilderMode(TmdbBuilderMode.COLLECTION)
        viewModel.setTmdbInput("10")
        viewModel.addTmdbSourceFromInput()
        runCurrent()
        viewModel.hideTmdbSourcePicker()
        viewModel.editTmdbSource(collectionSourceKey(stored[1]))
        gate.complete(Unit)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(stored, state.editingFolder?.sources)
        assertTrue(state.showTmdbSourcePicker)
        assertEquals(1, state.editingTmdbSourceIndex)
    }

    /** Spec 28 28n review: a direct add (Custom) while a fetching add runs is ignored too. */
    @Test
    fun `a direct tmdb add while a fetch runs is ignored`() = runTest {
        val gate = CompletableDeferred<Unit>()
        stubCollectionFetch(gate)
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.addFolder()
        startCollectionSourceAdd(viewModel)
        runCurrent()

        viewModel.setTmdbTitleInput("Custom")
        viewModel.addDiscoverSource()
        assertTrue(viewModel.uiState.value.editingFolder?.sources.orEmpty().isEmpty())
        gate.complete(Unit)
        advanceUntilIdle()

        val source = viewModel.uiState.value.editingFolder?.sources?.single() as TmdbCollectionSource
        assertEquals(TmdbCollectionSourceType.COLLECTION, source.sourceType)
    }

    /** Spec 28 Q4: the stored null vs the form's "" and FOLLOW_LAYOUT shown as Rows are no edits. */
    @Test
    fun `opening a stored collection and folder is not an unsaved change`() = runTest {
        val viewModel = newViewModel(stored = storedCollection())
        advanceUntilIdle()
        assertFalse(viewModel.hasUnsavedCollectionChanges())

        viewModel.setViewMode(FolderViewMode.ROWS)
        viewModel.setBackdropImageUrl("")
        assertFalse(viewModel.hasUnsavedCollectionChanges())

        viewModel.editFolder("f1")
        viewModel.switchToImageMode()
        viewModel.updateFolderHeroBackdropUrl("")
        assertFalse(viewModel.hasUnsavedFolderChanges())
        viewModel.saveFolderEdit()
        assertFalse(viewModel.hasUnsavedCollectionChanges())
    }

    @Test
    fun `collection edits are unsaved until reverted`() = runTest {
        val viewModel = newViewModel(stored = storedCollection())
        advanceUntilIdle()

        viewModel.setTitle("Renamed")
        assertTrue(viewModel.hasUnsavedCollectionChanges())
        viewModel.setTitle("Pixar")
        assertFalse(viewModel.hasUnsavedCollectionChanges())

        viewModel.setViewMode(FolderViewMode.TABBED_GRID)
        assertTrue(viewModel.hasUnsavedCollectionChanges())
    }

    @Test
    fun `a saved folder edit dirties the collection, a cancelled one does not`() = runTest {
        val viewModel = newViewModel(stored = storedCollection())
        advanceUntilIdle()

        viewModel.editFolder("f1")
        viewModel.updateFolderTitle("Shorts")
        assertTrue(viewModel.hasUnsavedFolderChanges())
        viewModel.cancelFolderEdit()
        assertFalse(viewModel.hasUnsavedFolderChanges())
        assertFalse(viewModel.hasUnsavedCollectionChanges())

        viewModel.editFolder("f1")
        viewModel.updateFolderTitle("Shorts")
        viewModel.saveFolderEdit()
        assertTrue(viewModel.hasUnsavedCollectionChanges())
    }

    @Test
    fun `a new collection is clean until edited and a new folder until filled`() = runTest {
        val viewModel = newViewModel()
        advanceUntilIdle()
        assertFalse(viewModel.hasUnsavedCollectionChanges())

        viewModel.addFolder()
        assertFalse(viewModel.hasUnsavedFolderChanges())
        viewModel.updateFolderCoverEmoji("🎬")
        assertTrue(viewModel.hasUnsavedFolderChanges())

        viewModel.setPinToTop(true)
        assertTrue(viewModel.hasUnsavedCollectionChanges())
    }

    @Test
    fun `saving is reported from the first save call`() = runTest {
        val viewModel = newViewModel(stored = storedCollection())
        advanceUntilIdle()
        assertFalse(viewModel.isSaving)

        viewModel.save {}

        assertTrue(viewModel.isSaving)
    }

    @Test
    fun `picking Rows keeps a stored Follow Home Layout, Tabs replaces it`() = runTest {
        val viewModel = newViewModel(stored = storedCollection())
        advanceUntilIdle()

        viewModel.setViewMode(FolderViewMode.ROWS)
        assertEquals(FolderViewMode.FOLLOW_LAYOUT, viewModel.uiState.value.viewMode)

        viewModel.setViewMode(FolderViewMode.TABBED_GRID)
        viewModel.setViewMode(FolderViewMode.ROWS)
        assertEquals(FolderViewMode.ROWS, viewModel.uiState.value.viewMode)
    }

    @Test
    fun `folders move by id and stop at the edges`() = runTest {
        val stored = storedCollection().let { collection ->
            collection.copy(folders = collection.folders + CollectionFolder(id = "f2", title = "Shorts"))
        }
        val viewModel = newViewModel(stored = stored)
        advanceUntilIdle()
        fun ids() = viewModel.uiState.value.folders.map { it.id }

        viewModel.moveFolderUp("f1")
        assertEquals(listOf("f1", "f2"), ids())
        viewModel.moveFolderDown("f1")
        viewModel.moveFolderDown("f1")
        assertEquals(listOf("f2", "f1"), ids())
        viewModel.moveFolderUp("f1")
        assertEquals(listOf("f1", "f2"), ids())
    }

    @Test
    fun `sources move and are removed by key and stop at the edges`() = runTest {
        val tmdb = collectionSource()
        val addon = AddonCatalogCollectionSource(addonId = "a", type = "movie", catalogId = "top")
        val stored = storedCollection().let { collection ->
            collection.copy(folders = collection.folders.map { it.copy(sources = listOf(tmdb, addon)) })
        }
        val viewModel = newViewModel(stored = stored)
        advanceUntilIdle()
        viewModel.editFolder("f1")
        fun keys() = viewModel.uiState.value.editingFolder!!.sources.map(::collectionSourceKey)

        viewModel.moveCatalogSourceUp(collectionSourceKey(tmdb))
        assertEquals(listOf(collectionSourceKey(tmdb), collectionSourceKey(addon)), keys())
        viewModel.moveCatalogSourceDown(collectionSourceKey(tmdb))
        viewModel.moveCatalogSourceDown(collectionSourceKey(tmdb))
        assertEquals(listOf(collectionSourceKey(addon), collectionSourceKey(tmdb)), keys())

        // Edit resolves the key to where the source is now.
        viewModel.editTmdbSource(collectionSourceKey(tmdb))
        assertEquals(1, viewModel.uiState.value.editingTmdbSourceIndex)
        viewModel.hideTmdbSourcePicker()

        viewModel.removeCatalogSource("missing")
        viewModel.removeCatalogSource(collectionSourceKey(tmdb))
        assertEquals(listOf(collectionSourceKey(addon)), keys())
    }

    @Test
    fun `a genre change returns the source's new key`() = runTest {
        val addon = Addon(
            id = "a",
            name = "Addon",
            version = "1",
            description = null,
            logo = null,
            baseUrl = "https://addon.example",
            catalogs = listOf(
                CatalogDescriptor(
                    type = ContentType.MOVIE,
                    id = "top",
                    name = "Top",
                    extra = listOf(CatalogExtra(name = "genre", isRequired = true, options = listOf("Action", "Drama")))
                )
            ),
            types = listOf(ContentType.MOVIE),
            resources = emptyList()
        )
        val viewModel = newViewModel(addons = listOf(addon))
        advanceUntilIdle()
        viewModel.addFolder()
        viewModel.toggleCatalogSource(viewModel.uiState.value.availableCatalogs.single())
        val source = viewModel.uiState.value.editingFolder!!.sources.single() as AddonCatalogCollectionSource
        assertEquals("Action", source.genre)

        val newKey = viewModel.updateCatalogSourceGenre(collectionSourceKey(source), "Drama")
        val updated = viewModel.uiState.value.editingFolder!!.sources.single()
        assertEquals(collectionSourceKey(updated), newKey)
        assertEquals("Drama", (updated as AddonCatalogCollectionSource).genre)
        // A required genre cannot be cleared; an unknown key changes nothing.
        viewModel.updateCatalogSourceGenre(newKey!!, null)
        assertEquals("Action", (viewModel.uiState.value.editingFolder!!.sources.single() as AddonCatalogCollectionSource).genre)
        assertNull(viewModel.updateCatalogSourceGenre("missing", "Drama"))
    }

    @Test
    fun `the Card shape setting reaches the state`() = runTest {
        val viewModel = newViewModel(landscape = true)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.landscapePosters)
    }

    private fun storedCollection() = Collection(
        id = "c1",
        title = "Pixar",
        backdropImageUrl = null,
        viewMode = FolderViewMode.FOLLOW_LAYOUT,
        folders = listOf(
            CollectionFolder(id = "f1", title = "Features", sources = listOf(collectionSource()))
        )
    )

    /** The three async TMDB add paths, each resolving collection 10 through the gated stub. */
    private val tmdbEntryPoints: List<Pair<String, (CollectionEditorViewModel) -> Unit>> = listOf(
        "addTmdbSourceFromInput" to { viewModel: CollectionEditorViewModel -> startCollectionSourceAdd(viewModel) },
        "addTmdbSource" to { viewModel: CollectionEditorViewModel -> viewModel.addTmdbSource(collectionSource()) },
        "addTmdbSources" to { viewModel: CollectionEditorViewModel -> viewModel.addTmdbSources(listOf(collectionSource())) }
    )

    private fun collectionSource() = TmdbCollectionSource(
        sourceType = TmdbCollectionSourceType.COLLECTION,
        title = "Saga",
        tmdbId = 10
    )

    private fun discoverSource(title: String) = TmdbCollectionSource(
        sourceType = TmdbCollectionSourceType.DISCOVER,
        title = title
    )

    private fun stubCollectionFetch(gate: CompletableDeferred<Unit>) {
        every { resolver.parseTmdbId("10") } returns 10
        coEvery { resolver.collectionImportMetadata(10) } coAnswers {
            gate.await()
            TmdbSourceImportMetadata(title = "Saga")
        }
    }

    private fun startCollectionSourceAdd(viewModel: CollectionEditorViewModel) {
        viewModel.showTmdbSourcePicker()
        viewModel.setTmdbBuilderMode(TmdbBuilderMode.COLLECTION)
        viewModel.setTmdbInput("10")
        viewModel.addTmdbSourceFromInput()
    }

    private fun newViewModel(
        stored: Collection? = null,
        landscape: Boolean = false,
        addons: List<Addon> = emptyList()
    ): CollectionEditorViewModel {
        val addonRepository = mockk<AddonRepository>()
        val layoutPreferences = mockk<LayoutPreferenceDataStore>()
        every { layoutPreferences.modernLandscapePostersEnabled } returns flowOf(landscape)
        every { addonRepository.getInstalledAddons() } returns flowOf(addons)
        if (stored != null) every { collectionsDataStore.collections } returns flowOf(listOf(stored))
        return CollectionEditorViewModel(
            appContext = mockk<Context>(relaxed = true),
            savedStateHandle = SavedStateHandle(listOfNotNull(stored?.let { "collectionId" to it.id }).toMap()),
            collectionsDataStore = collectionsDataStore,
            addonRepository = addonRepository,
            tmdbCollectionSourceResolver = resolver,
            collectionSyncService = mockk<CollectionSyncService>(relaxed = true),
            layoutPreferenceDataStore = layoutPreferences
        )
    }
}
