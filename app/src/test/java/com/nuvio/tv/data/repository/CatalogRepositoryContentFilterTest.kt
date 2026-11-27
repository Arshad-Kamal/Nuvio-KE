package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.core.contentfilter.ActiveContentFilter
import com.nuvio.tv.core.contentfilter.ActiveFilter
import com.nuvio.tv.core.contentfilter.TagState
import com.nuvio.tv.core.contentfilter.TitleRef
import com.nuvio.tv.core.contentfilter.TitleTagLookup
import com.nuvio.tv.core.di.NetworkModule
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.profile.ActiveRatingCap
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.dto.CatalogAppExtrasDto
import com.nuvio.tv.data.remote.dto.CatalogResponseDto
import com.nuvio.tv.data.remote.dto.MetaPreviewDto
import com.nuvio.tv.domain.contentfilter.ContentFilter
import com.nuvio.tv.domain.contentfilter.FilterPack
import com.nuvio.tv.domain.contentfilter.PackWord
import com.nuvio.tv.domain.contentfilter.TitleTags
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentRatingCap
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Response

/** Spec 117 P5 + Spec 122 V2: the content-filter gate in CatalogRepositoryImpl.getCatalog. */
class CatalogRepositoryContentFilterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val wireJson = NetworkModule.provideWireJson()

    private var cap: ContentRatingCap? = null
    private val activeCap = mockk<ActiveRatingCap> { coEvery { current() } answers { cap } }

    private val horror = FilterPack("p5", words = listOf(PackWord("zombie")))
    private var active: ActiveFilter? = ActiveFilter(ContentFilter(listOf(horror)), preview = false)
    private val activeFilter = mockk<ActiveContentFilter> { coEvery { current() } answers { active } }

    // Lookup fake: state per id (default words-only), a clock each wait advances by min(waitCost, budget).
    private val states = HashMap<String, TagState>()
    private var clock = 0L
    private var waitCost = 0L
    private val waits = mutableListOf<Pair<Set<String>, Long>>()
    private var onWait: () -> Unit = {}
    private val lookup = mockk<TitleTagLookup> {
        every { state(any()) } answers { states[firstArg<TitleRef>().id] ?: TagState.WordsOnly }
        every { tmdbKey(any()) } returns null
        coEvery { awaitDecided(any(), any(), any()) } coAnswers {
            val budget = thirdArg<Long>()
            waits += firstArg<Collection<TitleRef>>().mapTo(HashSet()) { it.id } to budget
            if (budget > 0) {
                clock += minOf(waitCost, budget)
                onWait()
            }
        }
    }

    private fun item(id: String, name: String, rating: String? = null) = MetaPreviewDto(
        id = id, type = "movie", name = name, appExtras = rating?.let { CatalogAppExtrasDto(certification = it) }
    )

    private fun payload(vararg items: MetaPreviewDto) = CatalogResponseDto(metas = items.toList())

    private fun pagedApi(vararg pages: Pair<Int, CatalogResponseDto>) = mockk<AddonApi> {
        pages.forEach { (pageSkip, body) ->
            val path = if (pageSkip == 0) "top.json" else "top/skip=$pageSkip.json"
            coEvery { getCatalog("https://addon.example/catalog/movie/$path") } returns Response.success(body)
        }
    }

    private fun repository(api: AddonApi): CatalogRepositoryImpl {
        val context = mockk<Context>(relaxed = true)
        every { context.cacheDir } returns tmp.root
        return CatalogRepositoryImpl(
            context = context,
            api = api,
            healthStore = mockk(relaxed = true),
            wireJson = wireJson,
            activeRatingCap = activeCap,
            ratingFilter = CatalogRatingFilter(mockk()),
            contentFilter = CatalogContentFilter(activeFilter, lookup) { clock }
        )
    }

    private suspend fun CatalogRepositoryImpl.rows(skip: Int = 0, packGate: Boolean = true): List<CatalogRow> =
        getCatalog(
            addonBaseUrl = "https://addon.example", addonId = "addon", addonName = "Addon", catalogId = "top",
            catalogName = "Top", type = "movie", skip = skip, skipStep = 100, extraArgs = emptyMap(),
            supportsSkip = true, packGate = packGate
        ).toList().filterIsInstance<NetworkResult.Success<CatalogRow>>().map { it.data }

    private fun CatalogRow.ids() = items.map { it.id }

    private fun writeDiskRow(body: CatalogResponseDto) {
        File(tmp.root, "catalog_rows_v1.json").writeText(
            JSONObject().put(
                "https://addon.example/catalog/movie/top.json",
                JSONObject().put("b", wireJson.encodeToString(CatalogResponseDto.serializer(), body))
                    .put("t", System.currentTimeMillis())
            ).toString()
        )
    }

    private fun tags(vararg keywords: String) =
        TitleTags(emptySet(), keywords.map { 9_000 + KEYWORD_IDS.indexOf(it) }.toSet(), keywords.toList(), "en", false)

    private val zombie = item("tt1", "Zombie Town")
    private val toy = item("tt2", "Toy Story")
    private val paddington = item("tt3", "Paddington")

    @Test
    fun `hits and undecided titles hide, one emission per page`() = runTest {
        states["tt2"] = TagState.Undecided
        val rows = repository(pagedApi(100 to payload(zombie, toy, paddington))).rows(skip = 100)
        assertEquals(listOf(listOf("tt3")), rows.map { it.ids() })
        assertEquals(listOf(setOf("tt2") to CatalogContentFilter.BUDGET_MS), waits)
    }

    @Test
    fun `a title whose tags arrive within the budget is judged on them`() = runTest {
        states["tt2"] = TagState.Undecided
        states["tt3"] = TagState.Undecided
        onWait = {
            states["tt2"] = TagState.Tagged(tags("family"))
            states["tt3"] = TagState.Tagged(tags("zombie"))
        }
        val row = repository(pagedApi(100 to payload(toy, paddington))).rows(skip = 100).single()
        assertEquals(listOf("tt2"), row.ids())
    }

    @Test
    fun `home loads skip the packs`() = runTest {
        states["tt2"] = TagState.Undecided
        val row = repository(pagedApi(100 to payload(zombie, toy))).rows(skip = 100, packGate = false).single()
        assertEquals(listOf("tt1", "tt2"), row.ids())
        coVerify(exactly = 0) { activeFilter.current() }
        assertTrue(waits.isEmpty())
    }

    @Test
    fun `preview keeps hits marked and still hides undecided titles`() = runTest {
        active = ActiveFilter(ContentFilter(listOf(horror)), preview = true)
        states["tt2"] = TagState.Undecided
        val row = repository(pagedApi(100 to payload(zombie, toy, paddington))).rows(skip = 100).single()
        assertEquals(listOf("tt1", "tt3"), row.ids())
        assertEquals(listOf(listOf("p5"), emptyList()), row.items.map { it.filterHits })
    }

    @Test
    fun `preview of a profile with nothing on shows every title unmarked and looks nothing up`() = runTest {
        active = ActiveFilter(filter = null, preview = true)
        states["tt2"] = TagState.Undecided
        val row = repository(pagedApi(100 to payload(zombie, toy))).rows(skip = 100).single()
        assertEquals(listOf("tt1", "tt2"), row.ids())
        assertTrue(row.items.all { it.filterHits.isEmpty() })
        assertTrue(waits.isEmpty())
    }

    @Test
    fun `a disk row is gated without waiting, the fresh row waits`() = runTest {
        states["tt2"] = TagState.Undecided
        writeDiskRow(payload(toy, paddington))
        val rows = repository(pagedApi(0 to payload(toy, paddington, zombie, item("tt4", "Bluey")))).rows()
        assertEquals(listOf(listOf("tt3"), listOf("tt3", "tt4")), rows.map { it.ids() })
        assertEquals(listOf(0L, CatalogContentFilter.BUDGET_MS), waits.map { it.second })
    }

    @Test
    fun `an identical fresh row is re-gated when the disk emission held titles`() = runTest {
        states["tt2"] = TagState.Undecided
        onWait = { states["tt2"] = TagState.WordsOnly }
        writeDiskRow(payload(toy, paddington))
        val rows = repository(pagedApi(0 to payload(toy, paddington))).rows()
        assertEquals(listOf(listOf("tt3"), listOf("tt2", "tt3")), rows.map { it.ids() })
    }

    @Test
    fun `an identical fresh row is dropped when the disk emission held nothing`() = runTest {
        writeDiskRow(payload(zombie, paddington))
        val api = mockk<AddonApi> {
            coEvery { getCatalog(any()) } coAnswers {
                // A re-gate of this row would now hide tt3 and emit again.
                states["tt3"] = TagState.Undecided
                Response.success(payload(zombie, paddington))
            }
        }
        val rows = repository(api).rows()
        assertEquals(listOf(listOf("tt3")), rows.map { it.ids() })
    }

    @Test
    fun `a held disk row is re-gated with the wait when the revalidation fails`() = runTest {
        states["tt2"] = TagState.Undecided
        onWait = { states["tt2"] = TagState.WordsOnly }
        writeDiskRow(payload(toy, paddington))
        val api = mockk<AddonApi> { coEvery { getCatalog(any()) } throws IOException("offline") }
        val rows = repository(api).rows()
        assertEquals(listOf(listOf("tt3"), listOf("tt2", "tt3")), rows.map { it.ids() })
        assertEquals(listOf(0L, CatalogContentFilter.BUDGET_MS), waits.map { it.second })
    }

    @Test
    fun `previewing, an identical fresh row is re-gated while a mark may still grow`() = runTest {
        val crime = FilterPack("p12", words = listOf(PackWord("mafia")))
        active = ActiveFilter(ContentFilter(listOf(horror, crime)), preview = true)
        states["tt1"] = TagState.Undecided
        onWait = { states["tt1"] = TagState.Tagged(tags("mafia")) }
        writeDiskRow(payload(zombie, paddington))
        val rows = repository(pagedApi(0 to payload(zombie, paddington))).rows()
        assertEquals(listOf(listOf(listOf("p5"), emptyList()), listOf(listOf("p5", "p12"), emptyList())),
            rows.map { row -> row.items.map { it.filterHits } })
    }

    @Test
    fun `not previewing, a word hit waiting for tags is final and not waited on`() = runTest {
        states["tt1"] = TagState.Undecided
        val row = repository(pagedApi(100 to payload(zombie, paddington))).rows(skip = 100).single()
        assertEquals(listOf("tt3"), row.ids())
        assertTrue(waits.isEmpty())
    }

    @Test
    fun `a memory-tier row waits like a network row`() = runTest {
        states["tt2"] = TagState.Undecided
        val api = pagedApi(100 to payload(toy, paddington))
        val repository = repository(api)
        repository.rows(skip = 100)
        repository.rows(skip = 100)
        assertEquals(listOf(CatalogContentFilter.BUDGET_MS, CatalogContentFilter.BUDGET_MS), waits.map { it.second })
        coVerify(exactly = 1) { api.getCatalog(any()) }
    }

    @Test
    fun `a filter that cannot be read fails the load and never shows an unfiltered row`() = runTest {
        coEvery { activeFilter.current() } throws IllegalStateException("broken pack file")
        val seen = mutableListOf<NetworkResult<CatalogRow>>()
        val failure = runCatching {
            repository(pagedApi(100 to payload(zombie))).getCatalog(
                addonBaseUrl = "https://addon.example", addonId = "addon", addonName = "Addon", catalogId = "top",
                catalogName = "Top", type = "movie", skip = 100, skipStep = 100, supportsSkip = true
            ).collect { seen += it }
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(seen.none { it is NetworkResult.Success })
    }

    @Test
    fun `an identical fresh row is dropped when its re-gate shows the same titles`() = runTest {
        states["tt2"] = TagState.Undecided
        writeDiskRow(payload(toy, paddington))
        val rows = repository(pagedApi(0 to payload(toy, paddington))).rows()
        assertEquals(listOf(listOf("tt3")), rows.map { it.ids() })
        assertEquals(listOf(0L, CatalogContentFilter.BUDGET_MS), waits.map { it.second })
    }

    @Test
    fun `the budget is one total for every page of a load`() = runTest {
        waitCost = 2_000
        listOf("a", "b", "c", "d").forEach { states["tt$it"] = TagState.Undecided }
        val api = pagedApi(
            100 to payload(item("tta", "A")),
            101 to payload(item("ttb", "B")),
            102 to payload(item("ttc", "C")),
            103 to payload(item("ttd", "D"))
        )
        val row = repository(api).rows(skip = 100).single()
        assertEquals(emptyList<String>(), row.ids())
        assertEquals(listOf(3_000L, 1_000L, 0L, 0L), waits.map { it.second })
        coVerify(exactly = 4) { api.getCatalog(any()) }
    }

    @Test
    fun `a load-more page the packs empty advances to the next page`() = runTest {
        val api = pagedApi(100 to payload(zombie), 101 to payload(paddington))
        val row = repository(api).rows(skip = 100).single()
        assertEquals(listOf("tt3"), row.ids())
        assertEquals(102, row.nextSkip)
    }

    @Test
    fun `page 1 is not advanced`() = runTest {
        val api = pagedApi(0 to payload(zombie), 1 to payload(paddington))
        val rows = repository(api).rows()
        assertEquals(listOf(emptyList<String>()), rows.map { it.ids() })
        coVerify(exactly = 1) { api.getCatalog(any()) }
    }

    @Test
    fun `a previewed hit keeps its page, so it does not advance`() = runTest {
        active = ActiveFilter(ContentFilter(listOf(horror)), preview = true)
        val api = pagedApi(100 to payload(zombie), 101 to payload(paddington))
        val row = repository(api).rows(skip = 100).single()
        assertEquals(listOf("tt1"), row.ids())
        coVerify(exactly = 1) { api.getCatalog(any()) }
    }

    @Test
    fun `a capped title is never looked up, shown or previewed`() = runTest {
        cap = ContentRatingCap.OLDER_KIDS
        active = ActiveFilter(ContentFilter(listOf(horror)), preview = true)
        states["ttr"] = TagState.Undecided
        states["ttg"] = TagState.Undecided
        val api = pagedApi(100 to payload(item("ttr", "Zombie R", "R"), item("ttg", "Zombie G", "G")))
        val row = repository(api).rows(skip = 100).single()
        assertEquals(listOf("ttg"), row.ids())
        assertEquals(listOf(setOf("ttg")), waits.map { it.first })
    }

    private companion object {
        // One id per keyword name, as TMDB has: the matcher caches a keyword's name by its id.
        val KEYWORD_IDS = listOf("family", "zombie", "mafia", "war")
    }
}
