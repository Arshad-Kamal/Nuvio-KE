package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.core.contentfilter.ActiveFilter
import com.nuvio.tv.core.contentfilter.Fetch
import com.nuvio.tv.core.contentfilter.FetchedTags
import com.nuvio.tv.core.contentfilter.TagState
import com.nuvio.tv.core.contentfilter.TitleKind
import com.nuvio.tv.core.contentfilter.TitleRef
import com.nuvio.tv.core.contentfilter.TitleTagCache
import com.nuvio.tv.core.contentfilter.TitleTagLookup
import com.nuvio.tv.core.contentfilter.TitleTagSource
import com.nuvio.tv.core.tmdb.TmdbKeyState
import com.nuvio.tv.domain.contentfilter.BuiltInPacks
import com.nuvio.tv.domain.contentfilter.ContentFilter
import com.nuvio.tv.domain.contentfilter.ContentFilterSettings
import com.nuvio.tv.domain.contentfilter.FilterPack
import com.nuvio.tv.domain.contentfilter.PackWord
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.legacyKey
import com.nuvio.tv.domain.model.stableKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Spec 117 P5b + Spec 122 V2: Home's draw filter, its placeholder, enrichment and hero guards. */
class HomeDrawFilterTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val withKey = TmdbKeyState(userKey = "", effectiveKey = "k")
    private val noKey = TmdbKeyState(userKey = "", effectiveKey = "")

    private val horror = FilterPack(
        "horror", words = listOf(PackWord("zombie")), genreIds = setOf(27), genreNames = setOf("horror"),
        keywordIds = setOf(12339)
    )
    private val gambling = FilterPack("gambling", words = listOf(PackWord("casino")))

    /** Tags by TMDB id; an id with a gate waits until the test completes it. */
    private class FakeSource : TitleTagSource {
        val tags = HashMap<Int, FetchedTags>()
        val gates = HashMap<Int, CompletableDeferred<Unit>>()
        val fetched = mutableListOf<Int>()

        override suspend fun tags(kind: TitleKind, tmdbId: Int, apiKey: String): Fetch<FetchedTags> {
            fetched += tmdbId
            gates[tmdbId]?.await()
            return Fetch.Ok(tags[tmdbId] ?: CLEAN)
        }

        override suspend fun find(imdbId: String, kind: TitleKind, apiKey: String): Fetch<Int> = Fetch.NotFound
        override suspend fun probe(apiKey: String): Fetch<Unit> = Fetch.Ok(Unit)
    }

    private class Harness(val lookup: TitleTagLookup, val source: FakeSource, val key: MutableStateFlow<TmdbKeyState?>, val cache: TitleTagCache, val ticks: MutableList<Long>)

    private fun TestScope.harness(key: TmdbKeyState? = withKey): Harness {
        val keyState = MutableStateFlow(key)
        val clock = { testScheduler.currentTime }
        val cache = TitleTagCache(
            File(folder.root, "tags.json"), clock, backgroundScope, StandardTestDispatcher(testScheduler), writeDelayMs = 10
        )
        val source = FakeSource()
        val lookup = TitleTagLookup(source, keyState, cache, backgroundScope, clock, workerCount = 1)
        val ticks = mutableListOf<Long>()
        backgroundScope.launch { lookup.changes.collect { ticks += testScheduler.currentTime } }
        runCurrent()
        return Harness(lookup, source, keyState, cache, ticks)
    }

    private fun item(tmdbId: Int, name: String, genres: List<String> = emptyList(), description: String? = null) =
        MetaPreview(
            id = "tmdb:$tmdbId", type = ContentType.MOVIE, name = name, poster = null, posterShape = PosterShape.POSTER,
            background = "https://img/$tmdbId.jpg", logo = null, description = description, releaseInfo = null,
            imdbRating = null, genres = genres
        )

    private fun row(catalogId: String, vararg items: MetaPreview) = CatalogRow(
        addonId = "addon", addonName = "Addon", addonBaseUrl = "https://addon.example", catalogId = catalogId,
        catalogName = "top $catalogId", type = ContentType.MOVIE, items = items.toList()
    )

    private fun filter(vararg packs: FilterPack, preview: Boolean = false, lookup: TitleTagLookup) =
        HomeDrawFilter.of(ActiveFilter(ContentFilter(packs.toList()), preview), lookup)!!

    private fun CatalogRow.ids() = items.map { it.id }

    @Test
    fun `an undecided title is hidden and queued, then shown once its tags land`() = runTest {
        val h = harness()
        h.source.gates[1] = CompletableDeferred()
        val rows = listOf(row("a", item(1, "Paddington")))
        val draw = filter(horror, lookup = h.lookup)

        val first = draw.draw(rows)
        assertEquals(emptyList<String>(), first.rows.single().ids())
        assertEquals(setOf(rows.single().legacyKey()), first.heldRowKeys)
        runCurrent()
        assertEquals(listOf(1), h.source.fetched)

        h.ticks.clear()
        h.source.gates.getValue(1).complete(Unit)
        advanceTimeBy(2_000)
        assertTrue("a stored tag ticks, which redraws Home", h.ticks.isNotEmpty())
        val second = draw.draw(rows)
        assertEquals(listOf("tmdb:1"), second.rows.single().ids())
        assertTrue(second.heldRowKeys.isEmpty())
    }

    @Test
    fun `a title whose tags hit stays hidden once they land`() = runTest {
        val h = harness()
        h.source.tags[2] = FetchedTags(emptyList(), listOf(12339 to "slasher"), "en", adult = false)
        val rows = listOf(row("a", item(2, "Scream")))
        val draw = filter(horror, lookup = h.lookup)
        draw.draw(rows)
        advanceTimeBy(2_000)
        assertEquals(emptyList<String>(), draw.draw(rows).rows.single().ids())
        assertTrue("a row of hits only is not held", draw.draw(rows).heldRowKeys.isEmpty())
    }

    @Test
    fun `before the key store is read every untagged title is held`() = runTest {
        val h = harness(key = null)
        val rows = listOf(row("a", item(1, "Paddington"), item(3, "Casino Royale")))
        val drawn = filter(gambling, lookup = h.lookup).draw(rows)
        assertEquals(emptyList<String>(), drawn.rows.single().ids())
        assertEquals(setOf(rows.single().legacyKey()), drawn.heldRowKeys)
        assertEquals(TagState.Undecided, h.lookup.state(TitleRef.of("movie", "tmdb:1")!!))
    }

    @Test
    fun `with no key titles are judged on words alone, and a key added re-judges them`() = runTest {
        val h = harness(key = noKey)
        val rows = listOf(row("a", item(1, "Paddington"), item(3, "Casino Royale")))
        val draw = filter(gambling, lookup = h.lookup)
        assertEquals(listOf("tmdb:1"), draw.draw(rows).rows.single().ids())

        h.ticks.clear()
        h.key.value = withKey
        runCurrent()
        advanceTimeBy(2_000)
        assertTrue("a key change ticks", h.ticks.isNotEmpty())
        h.source.gates[1] = CompletableDeferred()
        assertEquals(emptyList<String>(), draw.draw(rows).rows.single().ids())
        runCurrent()
        h.source.gates.getValue(1).complete(Unit)
        advanceTimeBy(2_000)
        assertEquals(listOf("tmdb:1"), draw.draw(rows).rows.single().ids())
    }

    @Test
    fun `each copy of one id is judged on its own genres`() = runTest {
        val h = harness()
        h.cache.putTags(TitleKind.MOVIE, 4, CLEAN)
        val rows = listOf(
            row("a", item(4, "The Others", genres = listOf("Horror"))),
            row("b", item(4, "The Others", genres = listOf("Drama")))
        )
        val drawn = filter(horror, lookup = h.lookup).draw(rows)
        assertEquals(listOf(emptyList(), listOf("tmdb:4")), drawn.rows.map { it.ids() })
    }

    @Test
    fun `the first rows queue at draw priority, later rows in the background`() = runTest {
        val h = harness()
        h.source.gates[100] = CompletableDeferred() // holds the single worker
        val draw = filter(horror, lookup = h.lookup)
        draw.draw(listOf(row("busy", item(100, "Busy"))))
        runCurrent()
        // Rows 9 and 10 (ids 1, 2) queue at BACKGROUND behind eight decided rows.
        val decided = (201..208).map { id -> h.cache.putTags(TitleKind.MOVIE, id, CLEAN); row("d$id", item(id, "Title $id")) }
        draw.draw(decided + row("late1", item(1, "Late one")) + row("late2", item(2, "Late two")))
        // A first row's title (id 3), queued after them at DRAW, is fetched first.
        draw.draw(listOf(row("first", item(3, "First"))))
        h.source.gates.getValue(100).complete(Unit)
        advanceTimeBy(2_000)
        assertEquals(listOf(100, 3, 1, 2), h.source.fetched)
    }

    @Test
    fun `only titles whose tags could change what is drawn are queued`() = runTest {
        val h = harness()
        h.source.gates[100] = CompletableDeferred() // holds the single worker
        filter(horror, lookup = h.lookup).draw(listOf(row("busy", item(100, "Busy"))))
        runCurrent()
        // Not previewing, a word hit hides whatever its tags add: never queued.
        filter(gambling, lookup = h.lookup).draw(listOf(row("a", item(3, "Casino Royale"))))
        // Previewing, its tags may add packs to its mark: queued.
        filter(gambling, preview = true, lookup = h.lookup).draw(listOf(row("b", item(7, "Casino Night"))))
        h.source.gates.getValue(100).complete(Unit)
        advanceTimeBy(2_000)
        assertEquals(listOf(100, 7), h.source.fetched)
    }

    @Test
    fun `a pack edit applies at the next draw`() = runTest {
        val h = harness()
        h.cache.putTags(TitleKind.MOVIE, 3, CLEAN)
        val rows = listOf(row("a", item(3, "Casino Royale")))
        assertEquals(listOf("tmdb:3"), filter(horror, lookup = h.lookup).draw(rows).rows.single().ids())
        assertEquals(emptyList<String>(), filter(horror, gambling, lookup = h.lookup).draw(rows).rows.single().ids())
    }

    @Test
    fun `the hero picks only drawn titles and skips rows the filter emptied`() = runTest {
        val h = harness()
        listOf(1, 3, 8, 9, 10).forEach { h.cache.putTags(TitleKind.MOVIE, it, CLEAN) }
        val rows = listOf(
            row("a", item(3, "Casino Royale")), // emptied by the filter: no hero slot
            row("b", item(1, "Paddington"), item(3, "Casino Royale")),
            row("c", item(8, "Up")),
            row("d", item(9, "Cars"), item(10, "Coco"))
        )
        val drawn = drawCatalogRows(rows, filter(gambling, lookup = h.lookup))
        // Three rows share the 7 slots (3, 2, 2), so row d gives both titles; were the emptied row
        // counted (2, 2, 2, 1), d would give one.
        assertEquals(setOf("tmdb:1", "tmdb:8", "tmdb:9", "tmdb:10"), drawn.heroItems.map { it.id }.toSet())
        assertEquals(
            listOf(emptyList(), listOf("tmdb:1"), listOf("tmdb:8"), listOf("tmdb:9", "tmdb:10")),
            drawn.displayRows.map { it.ids() }
        )
        val unfiltered = drawCatalogRows(rows, null)
        assertTrue("tmdb:3" in unfiltered.heroItems.map { it.id })
    }

    @Test
    fun `MDBList rows take no hero slot, filtered or not`() = runTest {
        val h = harness()
        listOf(1, 2).forEach { h.cache.putTags(TitleKind.MOVIE, it, CLEAN) }
        val mdbList = (1..6).map {
            row("mdblist_$it", item(100 + it, "List $it")).copy(addonId = "mdblist", addonBaseUrl = "")
        }
        val rows = mdbList + row("a", item(1, "Paddington"), item(2, "Up"))
        assertEquals(listOf("tmdb:1", "tmdb:2"), drawCatalogRows(rows, null).heroItems.map { it.id }.sorted())
        assertEquals(listOf("tmdb:1", "tmdb:2"), drawCatalogRows(rows, filter(gambling, lookup = h.lookup)).heroItems.map { it.id }.sorted())
    }

    @Test
    fun `while previewing a hit is drawn marked and an undecided title still hidden`() = runTest {
        val h = harness()
        h.source.gates[1] = CompletableDeferred()
        h.cache.putTags(TitleKind.MOVIE, 3, CLEAN)
        val rows = listOf(row("a", item(1, "Paddington"), item(3, "Casino Royale")))
        val drawn = filter(gambling, preview = true, lookup = h.lookup).draw(rows).rows.single()
        assertEquals(listOf("tmdb:3"), drawn.ids())
        assertEquals(listOf("gambling"), drawn.items.single().filterHits)
    }

    @Test
    fun `an unchanged row keeps its instance`() = runTest {
        val h = harness()
        h.cache.putTags(TitleKind.MOVIE, 1, CLEAN)
        val rows = listOf(row("a", item(1, "Paddington")))
        assertSame(rows.single(), filter(gambling, lookup = h.lookup).draw(rows).rows.single())
    }

    @Test
    fun `a held row draws as a placeholder under its keys until a title shows`() = runTest {
        val h = harness()
        h.source.gates[1] = CompletableDeferred()
        val stored = row("a", item(1, "Paddington")).copy(catalogName = "my picks")
        val draw = filter(horror, lookup = h.lookup)
        fun homeRow() = draw.draw(listOf(stored)).let { drawn ->
            catalogHomeRow(drawn.rows.single(), stored.legacyKey() in drawn.heldRowKeys, null, "Movies", "Series")
        }
        val placeholder = homeRow() as HomeRow.PlaceholderCatalog
        assertEquals("addon_movie_a", placeholder.catalogKey)
        assertEquals("the real row's LazyColumn key", stored.stableKey(), placeholder.stableCatalogKey)
        assertEquals("My picks - Movies", placeholder.displayTitle)
        assertEquals("my picks", placeholder.catalogName)
        runCurrent()
        h.source.gates.getValue(1).complete(Unit)
        advanceTimeBy(2_000)
        assertEquals(listOf("tmdb:1"), (homeRow() as HomeRow.Catalog).row.ids())
    }

    @Test
    fun `a row of hits only is dropped, an unloaded catalog keeps its own placeholder`() = runTest {
        val h = harness()
        h.cache.putTags(TitleKind.MOVIE, 3, CLEAN)
        val drawn = filter(gambling, lookup = h.lookup).draw(listOf(row("a", item(3, "Casino Royale"))))
        assertTrue(drawn.heldRowKeys.isEmpty())
        assertEquals(null, catalogHomeRow(drawn.rows.single(), held = false, null, "Movies", "Series"))
        val lazy = HomeViewModel.PlaceholderDescriptor(
            "addon_movie_b", "addon", "Addon", "https://addon.example", "b", "top b", "movie", "Top b - Movies"
        )
        assertEquals("addon_movie_b", (catalogHomeRow(null, false, lazy, "Movies", "Series") as HomeRow.PlaceholderCatalog).catalogKey)
    }

    @Test
    fun `an enrichment that makes a title a hit is left to a redraw, which removes it, with no hero`() = runTest {
        val h = harness()
        h.cache.putTags(TitleKind.MOVIE, 5, CLEAN)
        val draw = filter(horror, lookup = h.lookup)
        val stored = listOf(row("a", item(5, "The Thing")))
        val drawnRows = draw.draw(stored).rows
        assertEquals(listOf("tmdb:5"), drawnRows.single().ids())

        val merge: (MetaPreview) -> MetaPreview = { it.copy(genres = listOf("Horror")) }
        val enrichedStore = stored.map { r -> r.copy(items = r.items.map(merge)) }
        val outcome = enrichedOutcome(enrichedStore.single().items, drawnRows, "tmdb:5", draw, merge)
        assertEquals(EnrichedOutcome(EnrichedPatch.Redraw, hero = null), outcome)
        assertEquals(emptyList<String>(), draw.draw(enrichedStore).rows.single().ids())

        val noFilter = enrichedOutcome(enrichedStore.single().items, drawnRows, "tmdb:5", null, merge)
        assertEquals(listOf("Horror"), (noFilter.patch as EnrichedPatch.Patched).rows.single().items.single().genres)
        assertEquals(enrichedStore.single().items.single(), noFilter.hero)
    }

    @Test
    fun `a copy hidden before the enrichment asks for no redraw, and the kept copy heroes`() = runTest {
        val h = harness()
        h.cache.putTags(TitleKind.MOVIE, 4, CLEAN)
        val draw = filter(horror, lookup = h.lookup)
        val a = item(4, "The Others", genres = listOf("Horror"))
        val b = item(4, "The Others", genres = listOf("Drama"))
        val drawnRows = draw.draw(listOf(row("a", a), row("b", b))).rows
        val describe: (MetaPreview) -> MetaPreview = { it.copy(description = "A house.") }
        val outcome = enrichedOutcome(listOf(a, b).map(describe), drawnRows, "tmdb:4", draw, describe)
        assertEquals(listOf(emptyList(), listOf("A house.")), (outcome.patch as EnrichedPatch.Patched).rows.map { r -> r.items.map { it.description } })
        assertEquals(describe(b), outcome.hero)

        val horrify: (MetaPreview) -> MetaPreview = { it.copy(genres = listOf("Horror")) }
        val hidden = enrichedOutcome(listOf(a, b).map(horrify), drawnRows, "tmdb:4", draw, horrify)
        assertEquals(EnrichedOutcome(EnrichedPatch.Redraw, hero = null), hidden)
    }

    @Test
    fun `an enrichment with no stored copy patches the drawn rows and heroes nothing`() = runTest {
        val h = harness()
        h.cache.putTags(TitleKind.MOVIE, 1, CLEAN)
        val draw = filter(horror, lookup = h.lookup)
        val drawnRows = draw.draw(listOf(row("a", item(1, "Paddington")))).rows
        val outcome = enrichedOutcome(emptyList(), drawnRows, "tmdb:1", draw) { it.copy(description = "A bear.") }
        assertEquals("A bear.", (outcome.patch as EnrichedPatch.Patched).rows.single().items.single().description)
        assertEquals(null, outcome.hero)
    }

    @Test
    fun `a clean enrichment patches the drawn copy and heroes the stored one`() = runTest {
        val h = harness()
        h.cache.putTags(TitleKind.MOVIE, 1, CLEAN)
        val draw = filter(horror, lookup = h.lookup)
        val stored = item(1, "Paddington")
        val drawnRows = draw.draw(listOf(row("a", stored))).rows
        val merge: (MetaPreview) -> MetaPreview = { it.copy(description = "A bear in London.") }
        val outcome = enrichedOutcome(listOf(merge(stored)), drawnRows, "tmdb:1", draw, merge)
        assertEquals("A bear in London.", (outcome.patch as EnrichedPatch.Patched).rows.single().items.single().description)
        assertEquals(merge(stored), outcome.hero)
        assertEquals(EnrichedPatch.Unchanged, patchEnrichedItem(drawnRows, "tmdb:1", draw) { it })
    }

    @Test
    fun `while previewing a hit stays kept, and a changed mark asks for a redraw`() = runTest {
        val h = harness()
        h.cache.putTags(TitleKind.MOVIE, 3, CLEAN)
        val draw = filter(horror, gambling, preview = true, lookup = h.lookup)
        val stored = item(3, "Casino Royale")
        val drawnRows = draw.draw(listOf(row("a", stored))).rows
        assertEquals(listOf("gambling"), drawnRows.single().items.single().filterHits)

        val sameMark: (MetaPreview) -> MetaPreview = { it.copy(description = "Bond plays cards.") }
        val kept = enrichedOutcome(listOf(sameMark(stored)), drawnRows, "tmdb:3", draw, sameMark)
        assertEquals(listOf("gambling"), (kept.patch as EnrichedPatch.Patched).rows.single().items.single().filterHits)
        assertEquals("a previewed hit counts as kept for the hero", sameMark(stored), kept.hero)

        val grows: (MetaPreview) -> MetaPreview = { it.copy(description = "A zombie at the casino.") }
        assertEquals(EnrichedPatch.Redraw, enrichedOutcome(listOf(grows(stored)), drawnRows, "tmdb:3", draw, grows).patch)
    }

    @Test
    fun `a previewed mark that shrinks to clean asks for a redraw, which drops the mark`() = runTest {
        val h = harness()
        h.cache.putTags(TitleKind.MOVIE, 3, CLEAN)
        val draw = filter(gambling, preview = true, lookup = h.lookup)
        val drawnRows = draw.draw(listOf(row("a", item(3, "Casino Royale")))).rows
        val renamed: (MetaPreview) -> MetaPreview = { it.copy(name = "Royale") }
        assertEquals(EnrichedPatch.Redraw, patchEnrichedItem(drawnRows, "tmdb:3", draw, renamed))
        // The stale mark on a copy that is now clean is cleared, not kept.
        assertEquals(emptyList<String>(), draw.shown(renamed(drawnRows.single().items.single()))!!.filterHits)
    }

    @Test
    fun `the prefetched hero copy is a kept stored copy, else nothing`() = runTest {
        val h = harness()
        h.cache.putTags(TitleKind.MOVIE, 1, CLEAN)
        h.cache.putTags(TitleKind.MOVIE, 3, CLEAN)
        val draw = filter(gambling, lookup = h.lookup)
        val paddington = item(1, "Paddington")
        assertEquals(paddington, prefetchedHeroCopy(listOf(paddington), paddington, draw))
        val hit = item(3, "Casino Royale")
        val clean = item(3, "Royale")
        assertEquals("the kept copy of another row", clean, prefetchedHeroCopy(listOf(hit, clean), clean, draw))
        assertEquals(null, prefetchedHeroCopy(listOf(hit), hit, draw))
        assertEquals(null, prefetchedHeroCopy(emptyList(), item(6, "Unknown"), draw))
        assertEquals(hit, prefetchedHeroCopy(listOf(hit), hit, null))
        assertEquals("no filter, nothing stored: the focused item, as before", hit, prefetchedHeroCopy(emptyList(), hit, null))
    }

    /**
     * 117/P3 S4 microbenchmark: a Home-sized pass, 40 rows x 100 items against ~300 words (the built-in
     * packs plus My words), tags cached. Reported, not asserted (shared desktop, R10-m6); target < 16 ms.
     */
    @Test
    fun `microbenchmark a Home-sized draw pass`() = runTest {
        val h = harness()
        val builtIns = BuiltInPacks.parse(File(PACK_FILE).readText())
        val builtInWords = builtIns.sumOf { it.words.size }
        val myWords = (0 until (300 - builtInWords).coerceAtLeast(0)).map { PackWord("customword$it") }
        val packs = builtIns + FilterPack(ContentFilterSettings.MY_WORDS_ID, myWords)
        val words = packs.sumOf { it.words.size }
        val description = "A retired detective returns to his home town to solve one last case, " +
            "uncovering old secrets among friends and family over a long cold winter by the sea."
        val rows = (0 until 40).map { r ->
            row("r$r", *Array(100) { i ->
                val id = r * 100 + i + 1
                h.cache.putTags(
                    TitleKind.MOVIE, id,
                    FetchedTags(listOf(18, 9648), (0 until 19).map { k -> 50_000 + id * 19 + k to "keyword $k plot" }, "en", false)
                )
                item(id, "Title number $id", genres = listOf("Drama", "Mystery"), description = description)
            })
        }
        val draw = HomeDrawFilter.of(ActiveFilter(ContentFilter(packs), preview = false), h.lookup)!!
        repeat(3) { draw.draw(rows) } // warm-up: JIT and the keyword-name cache
        val runs = 5
        val start = System.nanoTime()
        repeat(runs) { draw.draw(rows) }
        val perPassMs = (System.nanoTime() - start) / runs / 1_000_000.0
        println("Spec 117 S4b microbenchmark: 40x100 items, $words words: %.2f ms per draw pass".format(perPassMs))
        assertEquals(4_000, draw.draw(rows).rows.sumOf { it.items.size })
    }


    private companion object {
        val CLEAN = FetchedTags(emptyList(), emptyList(), "en", adult = false)
        const val PACK_FILE = "src/main/assets/content_filter_packs.json"
    }
}
