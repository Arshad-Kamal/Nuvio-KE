package com.nuvio.tv.ui.screens.collection

import com.nuvio.tv.core.tmdb.rowCatalogId
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonCatalogCollectionSource
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.CollectionFolder
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.TmdbCollectionSource
import com.nuvio.tv.domain.model.TmdbCollectionSourceType
import com.nuvio.tv.domain.model.TraktCollectionSource
import com.nuvio.tv.domain.model.stableKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L4-01/44/92: source-tab construction (no load-time seeding) and follow-layout row
 * selection (one placeholder builder).
 * L4-36/84: the All-tab rebuild, now a pure state transform run inside _uiState.update{}.
 */
class FolderDetailRowsTest {

    private val addonSource = AddonCatalogCollectionSource(addonId = "addon", type = "movie", catalogId = "top")
    private val tmdbSource = TmdbCollectionSource(sourceType = TmdbCollectionSourceType.LIST, title = "List", tmdbId = 42)

    private fun item(id: String) = MetaPreview(
        id = id,
        type = ContentType.MOVIE,
        name = id,
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList()
    )

    private fun row(
        catalogId: String,
        vararg ids: String,
        isLoading: Boolean = false,
        hasMore: Boolean = false
    ) = CatalogRow(
        addonId = "addon",
        addonName = "Addon",
        addonBaseUrl = "https://addon.example/",
        catalogId = catalogId,
        catalogName = catalogId,
        type = ContentType.MOVIE,
        items = ids.map(::item),
        isLoading = isLoading,
        hasMore = hasMore
    )

    // ---- source tabs ----

    private val addon = Addon(
        id = "addon",
        name = "Addon",
        version = "1.0.0",
        description = null,
        logo = null,
        baseUrl = "https://addon.example/",
        catalogs = listOf(CatalogDescriptor(type = ContentType.MOVIE, id = "top", name = "Top")),
        types = listOf(ContentType.MOVIE),
        resources = listOf(AddonResource(name = "catalog", types = emptyList(), idPrefixes = null))
    )

    private fun sourceTabs() = buildSourceTabs(
        sources = listOf(addonSource, tmdbSource, TraktCollectionSource(title = "Trakt", traktListId = 7)),
        addons = listOf(addon),
        addonTabLabels = { _, catalogName -> (catalogName ?: "?") to "Movies" },
        tmdbTypeLabel = { "TMDB" }
    )

    @Test
    fun `every source tab starts loading with no catalog row`() {
        // L4-01: a row seeded at load outlived a failed source as an endless shimmer.
        val tabs = sourceTabs()

        assertEquals(2, tabs.size)
        assertTrue(tabs.all { it.catalogRow == null })
        assertTrue(tabs.all { it.isLoading })
    }

    @Test
    fun `trakt sources build no tab`() {
        // Spec 28 Q3: the Trakt integration was removed, so its sources never load.
        assertEquals(listOf(addonSource, tmdbSource), sourceTabs().map { it.source })
    }

    @Test
    fun `addon source tab resolves its label and the declared addon base url`() {
        val tab = sourceTabs().first()

        assertEquals("Top", tab.label)
        assertEquals("https://addon.example/", tab.addonBaseUrl)
    }

    // ---- follow layout rows ----

    @Test
    fun `failed source tab without a row gets no follow-layout row`() {
        val failed = FolderTab(label = "broken", rawType = "movie", source = addonSource, isLoading = false, error = "http 500")
        val loaded = FolderTab(label = "ok", rawType = "movie", source = addonSource, isLoading = false, catalogRow = row("ok", "a"), hasLoadedContent = true)

        val rows = followLayoutRows(listOf(failed, loaded))

        assertEquals(listOf(loaded.catalogRow), rows)
    }

    @Test
    fun `loading tab gets one shimmer row of eight placeholder items`() {
        val loading = FolderTab(label = "Top", rawType = "movie", source = addonSource, isLoading = true)

        val rows = followLayoutRows(listOf(loading))

        assertEquals(1, rows.size)
        assertTrue(rows[0].isLoading)
        assertEquals(8, rows[0].items.size)
        assertTrue(rows[0].items.all { it.id.startsWith("__placeholder_top_") })
    }

    @Test
    fun `addon shimmer row keys like the row its source loads`() {
        // Seeded rows carried the addon base URL; the follow-layout builder used "",
        // which re-keyed the row when real content replaced the shimmer.
        val loading = FolderTab(
            label = "Top",
            rawType = "movie",
            source = addonSource,
            isLoading = true,
            addonBaseUrl = "https://addon.example/"
        )
        val loaded = row("top", "a")

        val shimmer = followLayoutRows(listOf(loading)).single()

        assertEquals(loaded.stableKey(), shimmer.stableKey())
    }

    @Test
    fun `tmdb shimmer row uses the resolver's row catalog id`() {
        val loading = FolderTab(label = "List", rawType = "movie", source = tmdbSource, isLoading = true)

        val shimmer = followLayoutRows(listOf(loading)).single()

        assertEquals("tmdb", shimmer.addonId)
        assertEquals(tmdbSource.rowCatalogId(), shimmer.catalogId)
    }

    @Test
    fun `tmdb row catalog id format`() {
        assertEquals("tmdb_list_42_movie_popularity_desc", tmdbSource.rowCatalogId())
    }

    @Test
    fun `content-bearing tab keeps its row after a pagination error`() {
        val tab = FolderTab(
            label = "ok",
            source = tmdbSource,
            isLoading = false,
            error = "timeout",
            hasLoadedContent = true,
            catalogRow = row("ok", "a")
        )

        assertEquals(listOf(tab.catalogRow), followLayoutRows(listOf(tab)))
    }

    // ---- All tab ----

    private fun stateWith(allRow: CatalogRow?, vararg sourceTabs: FolderTab) = FolderDetailUiState(
        folder = CollectionFolder(id = "f", title = "F", sources = listOf(addonSource, tmdbSource)),
        tabs = listOf(FolderTab(label = "All", isAllTab = true, catalogRow = allRow, isLoading = allRow == null)) + sourceTabs
    )

    private fun loadedTab(row: CatalogRow) =
        FolderTab(label = row.catalogId, isLoading = false, catalogRow = row, hasLoadedContent = true)

    @Test
    fun `rebuild after a failed page clears the All tab loading flag`() {
        // A page error resets the source row to isLoading=false; the rebuild must follow.
        val state = stateWith(
            row("All", "a1", "b1", isLoading = true, hasMore = true),
            loadedTab(row("a", "a1", hasMore = true)),
            loadedTab(row("b", "b1"))
        )

        val allRow = state.withRebuiltAllTab().tabs[0].catalogRow

        assertFalse(allRow!!.isLoading)
        assertEquals(listOf("a1", "b1"), allRow.items.map { it.id })
        assertTrue(allRow.hasMore)
    }

    @Test
    fun `rebuild appends new round-robin items after the existing All items`() {
        val state = stateWith(
            row("All", "a1", "b1"),
            loadedTab(row("a", "a1", "a2", "a3")),
            loadedTab(row("b", "b1", "b2"))
        )

        val allRow = state.withRebuiltAllTab().tabs[0].catalogRow!!

        assertEquals(listOf("a1", "b1", "a2", "b2", "a3"), allRow.items.map { it.id })
        assertEquals("All", allRow.catalogName)
    }

    @Test
    fun `rebuild keeps the existing All items from the state it is given`() {
        // The merge reads the All row from the state passed in (inside update{}), so an
        // enrichment already applied to that row survives the rebuild.
        val enriched = item("a1").copy(description = "enriched")
        val allRow = row("All", "b1").copy(items = listOf(enriched))
        val state = stateWith(allRow, loadedTab(row("a", "a1")), loadedTab(row("b", "b1")))

        val rebuilt = state.withRebuiltAllTab().tabs[0].catalogRow!!

        assertSame(enriched, rebuilt.items.first())
    }

    @Test
    fun `rebuild while a source still loads only flags the All row`() {
        val state = stateWith(
            row("All", "a1"),
            loadedTab(row("a", "a1", "a2")),
            FolderTab(label = "b", isLoading = true)
        )

        val allRow = state.withRebuiltAllTab().tabs[0].catalogRow!!

        assertTrue(allRow.isLoading)
        assertEquals(listOf("a1"), allRow.items.map { it.id })
    }

    @Test
    fun `rebuild without an All tab leaves the state unchanged`() {
        val state = FolderDetailUiState(tabs = listOf(loadedTab(row("a", "a1"))))

        assertSame(state, state.withRebuiltAllTab())
    }

    @Test
    fun `All tab settles empty when every source settles without a row`() {
        val state = stateWith(
            null,
            FolderTab(label = "a", isLoading = false, error = "http 500"),
            FolderTab(label = "b", isLoading = false, hasLoadedContent = true)
        )

        val allTab = state.withRebuiltAllTab().tabs[0]

        assertFalse(allTab.isLoading)
        assertEquals(null, allTab.error)
        assertEquals(null, allTab.catalogRow)
    }

    @Test
    fun `All tab carries the first error when every source failed`() {
        val state = stateWith(
            null,
            FolderTab(label = "a", isLoading = false, error = "http 500"),
            FolderTab(label = "b", isLoading = false, error = "timeout")
        )

        val allTab = state.withRebuiltAllTab().tabs[0]

        assertFalse(allTab.isLoading)
        assertEquals("http 500", allTab.error)
    }

    @Test
    fun `All tab without rows keeps loading while a source loads`() {
        val state = stateWith(
            null,
            FolderTab(label = "a", isLoading = false, error = "http 500"),
            FolderTab(label = "b", isLoading = true)
        )

        assertSame(state, state.withRebuiltAllTab())
        assertTrue(state.tabs[0].isLoading)
    }

    @Test
    fun `All row stays while the only row source pages`() {
        val state = stateWith(
            row("All", "a1"),
            loadedTab(row("a", "a1", isLoading = true, hasMore = true)),
            FolderTab(label = "b", isLoading = false, error = "http 500")
        )

        val allRow = state.withRebuiltAllTab().tabs[0].catalogRow!!

        assertTrue(allRow.isLoading)
        assertEquals(listOf("a1"), allRow.items.map { it.id })
    }

    @Test
    fun `a merged All row clears an earlier All error`() {
        val state = stateWith(
            null,
            loadedTab(row("a", "a1")),
            FolderTab(label = "b", isLoading = false, error = "http 500")
        ).let { it.copy(tabs = listOf(it.tabs[0].copy(isLoading = false, error = "old")) + it.tabs.drop(1)) }

        val allTab = state.withRebuiltAllTab().tabs[0]

        assertEquals(null, allTab.error)
        assertEquals(listOf("a1"), allTab.catalogRow!!.items.map { it.id })
    }

    // ---- per-tab retry ----

    private val failedTab = FolderTab(label = "failed", source = addonSource, isLoading = false, error = "http 500")

    private fun retryState() = stateWith(
        null,
        failedTab,
        loadedTab(row("b", "b1")).copy(source = tmdbSource, error = "page 2 timeout"),
        FolderTab(label = "loading", source = addonSource, isLoading = true),
        failedTab.copy(label = "failed 2")
    )

    @Test
    fun `retry on the All tab covers every first-page failure`() {
        assertEquals(listOf(1, 4), retryState().retryableSourceTabIndices(0))
    }

    @Test
    fun `retry on a source tab covers only that tab when its first page failed`() {
        val state = retryState()

        assertEquals(listOf(1), state.retryableSourceTabIndices(1))
        // A tab holding a row (page error) or still loading is not retried.
        assertTrue(state.retryableSourceTabIndices(2).isEmpty())
        assertTrue(state.retryableSourceTabIndices(3).isEmpty())
        assertTrue(state.retryableSourceTabIndices(9).isEmpty())
    }

    @Test
    fun `reloading tabs reset to first-page loading and the All tab waits`() {
        val state = stateWith(
            null,
            failedTab,
            failedTab.copy(label = "failed 2")
        ).withRebuiltAllTab()
        assertEquals("http 500", state.tabs[0].error)

        val reloading = state.withSourceTabsReloading(listOf(1))

        val tab = reloading.tabs[1]
        assertTrue(tab.isLoading)
        assertEquals(null, tab.error)
        assertEquals(null, tab.catalogRow)
        assertEquals(state.tabs[2], reloading.tabs[2])
        assertTrue(reloading.tabs[0].isLoading)
        assertEquals(null, reloading.tabs[0].error)
    }

    @Test
    fun `reloading no tabs leaves the state unchanged`() {
        val state = retryState()

        assertSame(state, state.withSourceTabsReloading(emptyList()))
    }

    // ---- rows mode states ----

    private fun rowsState(vararg sourceTabs: FolderTab) =
        FolderDetailUiState(tabs = listOf(FolderTab(label = "All", isAllTab = true)) + sourceTabs).rowsState()

    @Test
    fun `no source tabs is the no-catalogs state`() {
        assertEquals(FolderRowsState.NO_SOURCES, FolderDetailUiState().rowsState())
        assertEquals(FolderRowsState.NO_SOURCES, rowsState())
    }

    @Test
    fun `rows show once any source has items, even while others load or failed`() {
        assertEquals(
            FolderRowsState.ROWS,
            rowsState(loadedTab(row("a", "a1")), FolderTab(label = "b", isLoading = true), failedTab)
        )
    }

    @Test
    fun `skeleton while a source loads and none has items`() {
        assertEquals(
            FolderRowsState.LOADING,
            rowsState(loadedTab(row("a")), FolderTab(label = "b", isLoading = true), failedTab)
        )
    }

    @Test
    fun `settled without items is a failure when a first page failed, else empty`() {
        assertEquals(FolderRowsState.FAILED, rowsState(loadedTab(row("a")), failedTab))
        assertEquals(FolderRowsState.EMPTY, rowsState(loadedTab(row("a")), loadedTab(row("b"))))
    }

    // ---- Tabs mode: what a tab shows (28d) ----

    @Test
    fun `a tab with cards shows the grid, even under a page error or a page in flight`() {
        val tab = FolderTab(label = "Top", catalogRow = row("top", "a", isLoading = true), isLoading = false, error = "page 2 failed")
        assertEquals(FolderTabState.GRID, tab.tabState())
    }

    @Test
    fun `a tab without cards is loading, failed or empty`() {
        assertEquals(FolderTabState.LOADING, FolderTab(label = "Top").tabState())
        assertEquals(FolderTabState.FAILED, FolderTab(label = "Top", isLoading = false, error = "down").tabState())
        assertEquals(FolderTabState.EMPTY, FolderTab(label = "Top", catalogRow = row("top"), isLoading = false).tabState())
    }

    // ---- Spec 52 T1: TMDB sources with no key ----

    private val noKeyTab = FolderTab(
        label = "List", source = tmdbSource, isLoading = false, error = "Needs a TMDB key", needsTmdbKey = true
    )

    @Test
    fun `a TMDB tab with no key shows the key message, a later grid still wins`() {
        assertEquals(FolderTabState.NO_KEY, noKeyTab.tabState())
        assertTrue(noKeyTab.waitsForTmdbKey)
        assertEquals(FolderTabState.GRID, noKeyTab.copy(catalogRow = row("list", "a")).tabState())
    }

    @Test
    fun `rows mode shows the key message before a plain failure, rows still win`() {
        assertEquals(FolderRowsState.NO_KEY, rowsState(failedTab, noKeyTab))
        assertEquals(FolderRowsState.ROWS, rowsState(loadedTab(row("a", "a1")), noKeyTab))
        assertEquals(FolderRowsState.LOADING, rowsState(noKeyTab, FolderTab(label = "b", isLoading = true)))
    }

    @Test
    fun `the All tab needs the key only when every source failed and one needs it`() {
        assertTrue(stateWith(null, failedTab, noKeyTab).withRebuiltAllTab().tabs[0].needsTmdbKey)
        assertFalse(stateWith(null, failedTab, failedTab).withRebuiltAllTab().tabs[0].needsTmdbKey)
        assertFalse(
            stateWith(null, noKeyTab, FolderTab(label = "b", isLoading = true)).withRebuiltAllTab().tabs[0].needsTmdbKey
        )
    }

    @Test
    fun `reloading a tab clears its no-key flag`() {
        val reloaded = stateWith(null, noKeyTab, failedTab).withSourceTabsReloading(listOf(1)).tabs[1]
        assertTrue(reloaded.isLoading)
        assertFalse(reloaded.needsTmdbKey)
    }
}
