package com.nuvio.tv.ui.screens.home

import android.content.Context
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.ContinueWatchingItem
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The row model follows its source row: the page (117/S4c, the load-more key) and the marks (122/T2). */
class ModernHomePresentationRowTest {

    private val context = mockk<Context>(relaxed = true)

    private fun item(i: Int) = MetaPreview(
        id = "tt$i", type = ContentType.MOVIE, name = "Title $i", poster = null, posterShape = PosterShape.POSTER,
        background = null, logo = null, description = null, releaseInfo = null, imdbRating = null, genres = emptyList()
    )

    private fun row(page: Int, items: List<MetaPreview> = (0 until 5).map(::item)) = CatalogRow(
        addonId = "addon", addonName = "Addon", addonBaseUrl = "https://addon.example", catalogId = "top",
        catalogName = "Top", type = ContentType.MOVIE, items = items, currentPage = page,
        supportsSkip = true
    )

    private fun build(row: CatalogRow, cache: ModernCarouselRowBuildCache) = buildModernHomePresentation(
        input = ModernHomePresentationInput(
            homeRows = listOf(HomeRow.Catalog(row)),
            catalogRows = listOf(row),
            continueWatchingItems = emptyList(),
            upcomingItems = emptyList(),
            useLandscapePosters = false,
            showCatalogTypeSuffix = false,
            showAddonName = false,
            showFullReleaseDate = false,
            localeTag = ""
        ),
        cache = cache,
        context = context
    ).rows.list.single()

    @Test
    fun `currentPage reaches the row model and a page change with the same cards rebuilds it`() {
        val cache = ModernCarouselRowBuildCache()
        assertEquals(2, build(row(page = 2), cache).currentPage)
        // Same cards, next page (a page that added no card): the cached row must not be reused.
        assertEquals(3, build(row(page = 3), cache).currentPage)
    }

    @Test
    fun `a previewed hit's mark reaches the card and a mark change rebuilds the cached card`() {
        val cache = ModernCarouselRowBuildCache()
        val clean = (0 until 5).map(::item)
        assertEquals(emptyList<String>(), build(row(0, clean), cache).items.list[1].filterHits)
        val marked = clean.mapIndexed { i, it -> if (i == 1) it.copy(filterHits = listOf("war", "violence")) else it }
        assertEquals(listOf("war", "violence"), build(row(0, marked), cache).items.list[1].filterHits)
        assertEquals(emptyList<String>(), build(row(0, clean), cache).items.list[1].filterHits)
    }

    @Test
    fun `an MDBList row carries the glyph flag and always names MDBList, without a type suffix`() {
        val mdbList = row(page = 1).copy(addonId = "mdblist", addonName = "MDBList", addonBaseUrl = "", catalogId = "mdblist_7", catalogName = "weekend")
        val built = buildModernHomePresentation(
            input = ModernHomePresentationInput(
                homeRows = listOf(HomeRow.Catalog(mdbList), HomeRow.Catalog(row(page = 1))),
                catalogRows = listOf(mdbList, row(page = 1)),
                continueWatchingItems = emptyList(),
                upcomingItems = emptyList(),
                useLandscapePosters = false,
                showCatalogTypeSuffix = true,
                showAddonName = false,
                showFullReleaseDate = false,
                localeTag = ""
            ),
            cache = ModernCarouselRowBuildCache(),
            context = context
        ).rows.list
        assertEquals(listOf(true, false), built.map { it.mdbList })
        assertEquals("Weekend", built[0].title)
        assertEquals("MDBList", built[0].titleSuffix)
        assertEquals(null, built[1].titleSuffix)
        assertEquals("mdblist_7", built[0].catalogId)
    }

    private fun cw(hits: List<String>) = ContinueWatchingItem.InProgress(
        WatchProgress(
            contentId = "tt9", contentType = "movie", name = "Spring", poster = null, backdrop = null, logo = null,
            videoId = "tt9", season = null, episode = null, episodeTitle = null, position = 1, duration = 10,
            lastWatched = 1
        ),
        filterHits = hits
    )

    private fun buildCw(item: ContinueWatchingItem, cache: ModernCarouselRowBuildCache) = buildModernHomePresentation(
        input = ModernHomePresentationInput(
            homeRows = emptyList(),
            catalogRows = emptyList(),
            continueWatchingItems = listOf(item),
            upcomingItems = emptyList(),
            useLandscapePosters = false,
            showCatalogTypeSuffix = false,
            showAddonName = false,
            showFullReleaseDate = false,
            localeTag = ""
        ),
        cache = cache,
        context = context
    ).rows.list.single { it.key == MODERN_CONTINUE_WATCHING_ROW_KEY }.items.list.single()

    @Test
    fun `a Continue Watching mark reaches the card and a mark change rebuilds the cached row (122 V6)`() {
        val cache = ModernCarouselRowBuildCache()
        assertEquals(emptyList<String>(), buildCw(cw(emptyList()), cache).filterHits)
        assertEquals(listOf("p5"), buildCw(cw(listOf("p5")), cache).filterHits)
        assertEquals(emptyList<String>(), buildCw(cw(emptyList()), cache).filterHits)
    }

    @Test
    fun `Picked for you sits right below Continue Watching and each hero carries its reason (Spec 100)`() {
        // The same title as a card of the catalog row below: its reason must stay on the pick's card.
        val pick = com.nuvio.tv.core.scout.ScoutRowItem(item(1), reason = "Sintel")
        val rows = buildModernHomePresentation(
            input = ModernHomePresentationInput(
                homeRows = listOf(HomeRow.Catalog(row(0))),
                catalogRows = listOf(row(0)),
                continueWatchingItems = listOf(cw(emptyList())),
                upcomingItems = emptyList(),
                useLandscapePosters = false,
                showCatalogTypeSuffix = false,
                showAddonName = false,
                showFullReleaseDate = false,
                localeTag = "",
                scoutPicks = listOf(pick)
            ),
            cache = ModernCarouselRowBuildCache(),
            context = context
        ).rows.list

        assertEquals(MODERN_SCOUT_ROW_KEY, rows[1].key)
        val card = rows[1].items.list.single()
        assertEquals("Sintel", card.heroPreview.pickReason)
        assertEquals("tt1", (card.payload as ModernPayload.Catalog).itemId)
        assertTrue(rows[2].items.list.none { it.heroPreview.pickReason != null })
    }
}
