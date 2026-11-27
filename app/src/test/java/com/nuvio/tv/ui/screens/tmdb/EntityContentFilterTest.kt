package com.nuvio.tv.ui.screens.tmdb

import androidx.lifecycle.SavedStateHandle
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.contentfilter.TagState
import com.nuvio.tv.core.tmdb.TmdbEntityBrowseData
import com.nuvio.tv.core.tmdb.TmdbEntityHeader
import com.nuvio.tv.core.tmdb.TmdbEntityKind
import com.nuvio.tv.core.tmdb.TmdbEntityMediaType
import com.nuvio.tv.core.tmdb.TmdbEntityRail
import com.nuvio.tv.core.tmdb.TmdbEntityRailPageResult
import com.nuvio.tv.core.tmdb.TmdbEntityRailType
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.repository.GateFixture
import com.nuvio.tv.data.repository.tmdbPreview
import com.nuvio.tv.domain.contentfilter.FilterPack
import com.nuvio.tv.domain.contentfilter.PackWord
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.TmdbSettings
import com.nuvio.tv.ui.screens.home.layoutPrefsStoreEmitting
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/** Spec 117 S5 (P6): entity page rails through the content filter's gate. */
class EntityContentFilterTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val fixture = GateFixture(listOf(FilterPack("p5", words = listOf(PackWord("zombie")))))

    private val header = TmdbEntityHeader(2, TmdbEntityKind.COMPANY, "Blender Studio", null, null, null, null)

    private fun rail(type: TmdbEntityRailType, vararg items: MetaPreview, hasMore: Boolean = false) =
        TmdbEntityRail(TmdbEntityMediaType.MOVIE, type, items.toList(), hasMore = hasMore)

    @Test
    fun `rails are filtered with one wait and a rail the filter empties is dropped`() = runTest {
        fixture.states["tmdb:3"] = TagState.Undecided
        val data = TmdbEntityBrowseData(
            header,
            listOf(
                rail(TmdbEntityRailType.POPULAR, tmdbPreview(1, "Zombie One"), tmdbPreview(2, "Spring")),
                rail(TmdbEntityRailType.TOP_RATED, tmdbPreview(4, "Zombie Two"), hasMore = true),
                rail(TmdbEntityRailType.RECENT, tmdbPreview(3, "Frozen"), tmdbPreview(2, "Spring"))
            )
        )

        val shown = data.filteredBy(fixture.gate())

        assertEquals(listOf(TmdbEntityRailType.POPULAR, TmdbEntityRailType.RECENT), shown.rails.map { it.railType })
        assertEquals(listOf(listOf("tmdb:2"), listOf("tmdb:2")), shown.rails.map { rail -> rail.items.map { it.id } })
        assertEquals(listOf(listOf("tmdb:3")), fixture.waits.map { it.first })
    }

    private fun metadata(vararg pages: Pair<Int, TmdbEntityRailPageResult>) = mockk<TmdbMetadataService> {
        coEvery { fetchEntityBrowse(any(), any(), any(), any(), any()) } returns TmdbEntityBrowseData(
            header,
            listOf(rail(TmdbEntityRailType.POPULAR, tmdbPreview(1, "Spring"), tmdbPreview(9, "Zombie"), hasMore = true))
        )
        pages.forEach { (page, result) ->
            coEvery { fetchEntityRailPage(any(), any(), any(), any(), any(), page) } returns result
        }
    }

    private fun zombiePage(page: Int) = page to TmdbEntityRailPageResult(listOf(tmdbPreview(page * 10, "Zombie $page")), hasMore = true)

    @Test
    fun `the page load filters rails and a load-more advances past pages the filter empties`() =
        runTest(mainDispatcherRule.dispatcher) {
            val rail = loadMore(metadata(zombiePage(2), 3 to TmdbEntityRailPageResult(listOf(tmdbPreview(30, "Paddington")), hasMore = false)))

            assertEquals(listOf("tmdb:1", "tmdb:30"), rail.items.map { it.id })
            assertEquals(3, rail.currentPage)
            assertFalse(rail.hasMore)
        }

    @Test
    fun `a rail still empty after the advance ends`() = runTest(mainDispatcherRule.dispatcher) {
        val rail = loadMore(metadata(zombiePage(2), zombiePage(3), zombiePage(4), zombiePage(5)))

        assertEquals(listOf("tmdb:1"), rail.items.map { it.id })
        assertEquals(5, rail.currentPage)
        assertFalse("the source has more, but nothing showed (P5)", rail.hasMore)
    }

    private suspend fun kotlinx.coroutines.test.TestScope.loadMore(metadata: TmdbMetadataService): TmdbEntityRail {
        val viewModel = TmdbEntityBrowseViewModel(
            context = mockk(relaxed = true),
            tmdbMetadataService = metadata,
            tmdbSettingsDataStore = mockk<TmdbSettingsDataStore> {
                every { settings } returns MutableStateFlow(TmdbSettings(language = "en"))
            },
            tmdbApiKeys = mockk(relaxed = true) {
                every { state } returns MutableStateFlow(com.nuvio.tv.core.tmdb.TmdbKeyState("", "built-in"))
            },
            layoutPreferenceDataStore = layoutPrefsStoreEmitting(),
            watchProgressRepository = mockk(relaxed = true) {
                every { observeWatchedMovieIds() } returns MutableStateFlow(emptySet())
            },
            watchedSeriesStateHolder = mockk(relaxed = true) {
                every { fullyWatchedSeriesIds } returns MutableStateFlow(emptySet())
            },
            posterOptions = mockk(relaxed = true),
            contentFilter = fixture.contentFilter,
            savedStateHandle = SavedStateHandle(mapOf("entityKind" to "company", "entityId" to 2, "entityName" to "Blender"))
        )

        val loaded = viewModel.uiState.first { it is TmdbEntityBrowseUiState.Success } as TmdbEntityBrowseUiState.Success
        assertEquals(listOf("tmdb:1"), loaded.data.rails.single().items.map { it.id })

        viewModel.loadMoreForCatalog(
            entityRailCatalogId(TmdbEntityMediaType.MOVIE, TmdbEntityRailType.POPULAR),
            ENTITY_ROWS_ADDON_ID
        )
        advanceUntilIdle()

        return (viewModel.uiState.value as TmdbEntityBrowseUiState.Success).data.rails.single()
    }
}
