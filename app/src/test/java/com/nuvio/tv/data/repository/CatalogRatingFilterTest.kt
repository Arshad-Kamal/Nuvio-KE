package com.nuvio.tv.data.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentRatingCap
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.repository.MetaRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 64 K1b: the search rescue's failure and time bounds (virtual time). */
class CatalogRatingFilterTest {
    private val metaRepository = mockk<MetaRepository>()
    private val filter = CatalogRatingFilter(metaRepository)

    private fun series(
        id: String,
        rating: String? = null,
        nonUs: Map<String, String> = emptyMap(),
        type: ContentType = ContentType.SERIES
    ) = MetaPreview(
        id = id, type = type, name = id, poster = null, posterShape = PosterShape.POSTER,
        background = null, logo = null, description = null, releaseInfo = null, imdbRating = null,
        genres = emptyList(), ageRating = rating, nonUsRatings = nonUs
    )

    private fun metaOf(rating: String?, nonUs: Map<String, String>) =
        flowOf(NetworkResult.Success(mockk<Meta> { every { ageRating } returns rating; every { nonUsRatings } returns nonUs }))

    /** Older kids by three board families (Spec 102 R3). */
    private val olderKidsBoards = mapOf("GB" to "PG", "DE" to "6", "KR" to "7")

    private fun row(vararg items: MetaPreview) = CatalogRow(
        addonId = "a", addonName = "A", addonBaseUrl = "https://addon.example", catalogId = "search",
        catalogName = "Search", type = ContentType.SERIES, items = items.toList()
    )

    private fun rated(rating: String) = metaOf(rating, emptyMap())

    @Test
    fun `no cap returns the row untouched`() = runTest {
        val row = row(series("a"))
        assertSame(row, filter.filter(row, cap = null, lookUpUnratedSeries = true))
    }

    @Test
    fun `a lookup that throws hides only that title`() = runTest {
        coEvery { metaRepository.getMeta(any(), any(), "boom") } returns flow { throw IOException("offline") }
        coEvery { metaRepository.getMeta(any(), any(), "ok") } returns rated("TV-Y")
        val out = filter.filter(row(series("boom"), series("ok")), ContentRatingCap.ALL_AGES, lookUpUnratedSeries = true)
        assertEquals(listOf("ok"), out.items.map { it.id })
    }

    @Test
    fun `lookups stop at the budget and the unresolved are hidden`() = runTest {
        coEvery { metaRepository.getMeta(any(), any(), "slow") } returns flow { awaitCancellation() }
        coEvery { metaRepository.getMeta(any(), any(), "fast") } returns rated("TV-PG")
        val out = filter.filter(row(series("slow"), series("fast")), ContentRatingCap.OLDER_KIDS, lookUpUnratedSeries = true)
        assertEquals(listOf("fast"), out.items.map { it.id })
        assertTrue("stopped at the 6 s budget, not later", currentTime in 6_000L..6_001L)
    }

    @Test
    fun `concurrent rows share six lookup permits`() = runTest {
        var inFlight = 0
        var peak = 0
        coEvery { metaRepository.getMeta(any(), any(), any()) } returns flow {
            inFlight++
            peak = maxOf(peak, inFlight)
            delay(100)
            inFlight--
            emit(rated("TV-Y").first())
        }
        val rowA = row(*Array(5) { series("a$it") })
        val rowB = row(*Array(5) { series("b$it") })
        val (outA, outB) = listOf(rowA, rowB)
            .map { async { filter.filter(it, ContentRatingCap.ALL_AGES, lookUpUnratedSeries = true) } }
            .awaitAll()
        assertEquals(6, peak)
        assertEquals(5, outA.items.size)
        assertEquals(5, outB.items.size)
    }

    @Test
    fun `a lookup that completes without a result hides the title`() = runTest {
        coEvery { metaRepository.getMeta(any(), any(), any()) } returns flowOf(NetworkResult.Loading)
        val out = filter.filter(row(series("x")), ContentRatingCap.MATURE, lookUpUnratedSeries = true)
        assertTrue(out.items.isEmpty())
    }

    @Test
    fun `without a lookup, a title's own non-US ratings decide (Spec 102)`() = runTest {
        val out = filter.filter(
            row(
                series("film", nonUs = olderKidsBoards, type = ContentType.MOVIE),
                series("show", nonUs = olderKidsBoards),
                series("teen", nonUs = olderKidsBoards + ("FR" to "12")),
                series("two-boards", nonUs = mapOf("GB" to "PG", "DE" to "6"))
            ),
            ContentRatingCap.OLDER_KIDS,
            lookUpUnratedSeries = false
        )
        assertEquals(listOf("film", "show"), out.items.map { it.id })
    }

    @Test
    fun `R8 a search series counts its own non-US ratings only after a completed lookup`() = runTest {
        coEvery { metaRepository.getMeta(any(), any(), "slow") } returns flow { awaitCancellation() }
        coEvery { metaRepository.getMeta(any(), any(), "failed") } returns flow { throw IOException("offline") }
        coEvery { metaRepository.getMeta(any(), any(), "bare") } returns metaOf(null, emptyMap())
        val out = filter.filter(
            row(
                series("slow", nonUs = olderKidsBoards),
                series("failed", nonUs = olderKidsBoards),
                series("bare", nonUs = olderKidsBoards)
            ),
            ContentRatingCap.OLDER_KIDS,
            lookUpUnratedSeries = true
        )
        assertEquals(listOf("bare"), out.items.map { it.id })
    }

    @Test
    fun `R8 a meta US rating replaces the row's, and a meta without one adds its boards`() = runTest {
        coEvery { metaRepository.getMeta(any(), any(), "us-ma") } returns metaOf("TV-MA", emptyMap())
        coEvery { metaRepository.getMeta(any(), any(), "map-us") } returns metaOf(null, mapOf("US" to "TV-14"))
        coEvery { metaRepository.getMeta(any(), any(), "us-y") } returns metaOf("TV-Y", emptyMap())
        // One stricter board in the meta: on its own it places nothing, together it raises the row.
        coEvery { metaRepository.getMeta(any(), any(), "stricter") } returns metaOf(null, mapOf("KR" to "19"))
        coEvery { metaRepository.getMeta(any(), any(), "adds") } returns metaOf(null, mapOf("NL" to "6"))
        val out = filter.filter(
            row(
                series("us-ma", nonUs = olderKidsBoards),
                series("map-us", nonUs = olderKidsBoards),
                series("us-y", nonUs = mapOf("DE" to "16", "GB" to "15", "FR" to "16")),
                series("stricter", nonUs = olderKidsBoards),
                series("adds", nonUs = mapOf("GB" to "PG", "DE" to "6"))
            ),
            ContentRatingCap.OLDER_KIDS,
            lookUpUnratedSeries = true
        )
        assertEquals(listOf("us-y", "adds"), out.items.map { it.id })
        assertEquals("TV-Y", out.items.first().ageRating)
    }

    @Test
    fun `in search, a film keeps its own non-US ratings without a lookup`() = runTest {
        val out = filter.filter(
            row(series("film", nonUs = olderKidsBoards, type = ContentType.MOVIE)),
            ContentRatingCap.OLDER_KIDS,
            lookUpUnratedSeries = true
        )
        assertEquals(listOf("film"), out.items.map { it.id })
    }

    @Test
    fun `a series with a US code in its own map is not looked up`() = runTest {
        val out = filter.filter(
            row(series("mapped", nonUs = mapOf("US" to "TV-PG"))),
            ContentRatingCap.OLDER_KIDS,
            lookUpUnratedSeries = true
        )
        assertEquals(listOf("mapped"), out.items.map { it.id })
    }
}
