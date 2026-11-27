package com.nuvio.tv.data.repository

import com.nuvio.tv.core.contentfilter.TagState
import com.nuvio.tv.domain.contentfilter.FilterPack
import com.nuvio.tv.domain.contentfilter.PackWord
import com.nuvio.tv.domain.contentfilter.TitleTags
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 117 S5 (P6): the gate's several-list wait, load-more advance and TMDB list hints. */
class CatalogContentFilterGateTest {
    private val horror = FilterPack("p5", words = listOf(PackWord("zombie")), genreIds = setOf(27))
    private val sexual = FilterPack("p1", adult = true)
    private val fixture = GateFixture(listOf(horror, sexual))

    private fun ids(items: List<com.nuvio.tv.domain.model.MetaPreview>) = items.map { it.id }

    @Test
    fun `applyAll waits once for every list's undecided titles, in list order, then judges each list`() = runTest {
        fixture.states["tmdb:2"] = TagState.Undecided
        fixture.states["tmdb:4"] = TagState.Undecided
        fixture.states["tmdb:5"] = TagState.Undecided
        fixture.onWait = {
            fixture.states["tmdb:2"] = TagState.Tagged(TitleTags(setOf(27), emptySet(), emptyList(), "en", false))
            fixture.states["tmdb:4"] = TagState.Tagged(TitleTags(setOf(35), emptySet(), emptyList(), "en", false))
        }
        val shown = fixture.gate().applyAll(
            listOf(
                listOf(tmdbPreview(1, "Zombie Town"), tmdbPreview(2, "Night"), tmdbPreview(3, "Paddington")),
                listOf(tmdbPreview(4, "Toy Story"), tmdbPreview(5, "Frozen"), tmdbPreview(2, "Night"))
            )
        )

        assertEquals(listOf(listOf("tmdb:3"), listOf("tmdb:4")), shown.map(::ids))
        // One wait, each title once, the full budget; the word hit (1) never waits.
        assertEquals(listOf(listOf("tmdb:2", "tmdb:4", "tmdb:5") to CatalogContentFilter.BUDGET_MS), fixture.waits)
    }

    @Test
    fun `a list row's genre ids and adult flag hide a title before its lookup`() = runTest {
        fixture.states["tmdb:1"] = TagState.Undecided
        fixture.states["tmdb:2"] = TagState.Undecided
        val shown = fixture.gate().applyAll(
            listOf(listOf(tmdbPreview(1, "Night", genreIds = listOf(27)), tmdbPreview(2, "Untitled", adult = true)))
        )

        assertTrue(shown.single().isEmpty())
        assertTrue("decided hits never wait", fixture.waits.isEmpty())
    }

    @Test
    fun `previewing, list hints mark the title and it still waits for more packs`() = runTest {
        val preview = GateFixture(listOf(horror, sexual), preview = true)
        preview.states["tmdb:1"] = TagState.Undecided
        preview.onWait = {
            preview.states["tmdb:1"] = TagState.Tagged(TitleTags(setOf(27), emptySet(), emptyList(), "en", true))
        }
        val shown = preview.gate().applyAll(listOf(listOf(tmdbPreview(1, "Night", genreIds = listOf(27)))))

        assertEquals(listOf("p5", "p1"), shown.single().single().filterHits)
        assertEquals(listOf("tmdb:1"), preview.waits.single().first)
    }

    // A page: its number, its titles, whether the source has more.
    private data class Page(val number: Int, val items: List<com.nuvio.tv.domain.model.MetaPreview>, val hasMore: Boolean)

    private suspend fun loadMore(pages: Map<Int, Page>, first: Int): Pair<Page, List<String>> {
        val (page, shown) = fixture.gate().showLoadMore(
            first = pages.getValue(first),
            itemsOf = Page::items,
            hasMore = Page::hasMore,
            fetchNext = { pages.getValue(it.number + 1) }
        )
        return page to ids(shown)
    }

    private fun hidden(number: Int, hasMore: Boolean = true) =
        Page(number, listOf(tmdbPreview(number * 10, "Zombie $number")), hasMore)

    @Test
    fun `a load-more page the filter empties advances to the first page that shows something`() = runTest {
        val pages = mapOf(2 to hidden(2), 3 to hidden(3), 4 to Page(4, listOf(tmdbPreview(40, "Paddington")), true))

        val (landed, shown) = loadMore(pages, first = 2)

        assertEquals(4, landed.number)
        assertEquals(listOf("tmdb:40"), shown)
    }

    @Test
    fun `the advance stops after three more pages`() = runTest {
        val pages = (2..6).associateWith { hidden(it) }

        val (landed, shown) = loadMore(pages, first = 2)

        assertEquals(5, landed.number)
        assertTrue(shown.isEmpty())
    }

    @Test
    fun `the advance stops where the source ends`() = runTest {
        val pages = mapOf(2 to hidden(2), 3 to hidden(3, hasMore = false))

        val (landed, shown) = loadMore(pages, first = 2)

        assertEquals(3, landed.number)
        assertTrue(shown.isEmpty())
    }
}
