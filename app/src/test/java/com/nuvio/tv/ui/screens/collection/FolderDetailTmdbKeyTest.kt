package com.nuvio.tv.ui.screens.collection

import androidx.lifecycle.SavedStateHandle
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.tmdb.TmdbApiKeyProvider
import com.nuvio.tv.core.tmdb.TmdbCollectionSourceResolver
import com.nuvio.tv.core.tmdb.TmdbKeyState
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.CollectionFolder
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.TmdbCollectionSource
import com.nuvio.tv.domain.model.TmdbCollectionSourceType
import com.nuvio.tv.ui.screens.home.layoutPrefsStoreEmitting
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Spec 52 T1: a TMDB tab that failed for want of a key shows it, and only such tabs reload once one is saved. */
class FolderDetailTmdbKeyTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val needsKey = TmdbCollectionSource(sourceType = TmdbCollectionSourceType.LIST, title = "List", tmdbId = 44)
    private val broken = TmdbCollectionSource(sourceType = TmdbCollectionSourceType.COLLECTION, title = "Saga", tmdbId = 10)
    private val keyState = MutableStateFlow<TmdbKeyState?>(TmdbKeyState(userKey = "", effectiveKey = ""))
    private val resolver = mockk<TmdbCollectionSourceResolver> {
        every { resolve(needsKey, 1) } returnsMany listOf(
            flowOf(NetworkResult.Error("Needs a TMDB key", code = NetworkResult.NO_TMDB_KEY_CODE)),
            flowOf(NetworkResult.Success(row()))
        )
        every { resolve(broken, 1) } returns flowOf(NetworkResult.Error("http 500"))
    }

    @Test
    fun `a no-key tab shows the key message and reloads once a key is saved, other failures stay`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = viewModel()

            val settled = viewModel.uiState.first { state -> state.tabs.size == 3 && state.tabs.none { it.isLoading } }
            val (all, list, saga) = settled.tabs
            assertEquals(FolderTabState.NO_KEY, list.tabState())
            assertEquals(FolderTabState.FAILED, saga.tabState())
            assertTrue("every source failed and one needs the key", all.needsTmdbKey)

            keyState.value = TmdbKeyState(userKey = "user-key", effectiveKey = "user-key")
            val reloaded = viewModel.uiState.first { it.tabs[1].catalogRow != null }
            assertEquals(FolderTabState.GRID, reloaded.tabs[1].tabState())
            assertFalse(reloaded.tabs[1].needsTmdbKey)
            assertEquals(FolderTabState.FAILED, reloaded.tabs[2].tabState())
            verify(exactly = 2) { resolver.resolve(needsKey, 1) }
            verify(exactly = 1) { resolver.resolve(broken, 1) }
        }

    private fun viewModel() = FolderDetailViewModel(
        appContext = mockk(relaxed = true),
        savedStateHandle = SavedStateHandle(mapOf("collectionId" to "c", "folderId" to "f")),
        collectionsDataStore = mockk<CollectionsDataStore> {
            every { collections } returns flowOf(
                listOf(
                    Collection(
                        id = "c",
                        title = "Collection",
                        folders = listOf(CollectionFolder(id = "f", title = "Folder", sources = listOf(needsKey, broken)))
                    )
                )
            )
        },
        addonRepository = mockk { every { getInstalledAddons() } returns flowOf(emptyList()) },
        catalogRepository = mockk(relaxed = true),
        layoutPreferenceDataStore = layoutPrefsStoreEmitting(),
        watchProgressRepository = mockk(relaxed = true) {
            every { observeWatchedMovieIds() } returns MutableStateFlow(emptySet())
        },
        watchedSeriesStateHolder = mockk(relaxed = true) {
            every { fullyWatchedSeriesIds } returns MutableStateFlow(emptySet())
        },
        metaRepository = mockk(relaxed = true),
        trailerService = mockk(relaxed = true),
        tmdbCollectionSourceResolver = resolver,
        tmdbApiKeys = mockk<TmdbApiKeyProvider> { every { state } returns keyState },
        posterOptions = mockk(relaxed = true)
    )

    private fun row() = CatalogRow(
        addonId = "tmdb",
        addonName = "TMDB",
        addonBaseUrl = "",
        catalogId = "tmdb_list_44",
        catalogName = "List",
        type = ContentType.MOVIE,
        items = listOf(
            MetaPreview(
                id = "tmdb:1", type = ContentType.MOVIE, name = "Spring", poster = null,
                posterShape = PosterShape.POSTER, background = null, logo = null, description = null,
                releaseInfo = "2019", imdbRating = null, genres = emptyList()
            )
        )
    )
}
