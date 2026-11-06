package com.nuvio.tv.data.repository

import com.nuvio.tv.domain.model.LibraryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T7 pins for the local-library logo-hydration planner (the mark-then-take-20
 * policy that used to live inline in the fire-and-forget launch in
 * LibraryRepositoryImpl.libraryItems): marking before launching is what makes
 * hydration idempotent, and the batch cap is what bounds the work per
 * emission.
 */
class LibraryLogoHydrationPlannerTest {

    @Test
    fun `plans logo-less unmarked entries capped at the batch size`() {
        val items = (0 until 45).map { entry("i$it") } + entry("with-logo", logo = "https://art")

        val first = planLogoHydrationBatch(items, emptySet())
        assertEquals((0 until 20).map { "i$it" }, first.map { it.id })

        // Re-planning with the first batch marked (the wiring marks before
        // launching) picks up exactly the next slice; the logo-bearing entry
        // is never planned.
        val second = planLogoHydrationBatch(items, first.map { it.id }.toSet())
        assertEquals((20 until 40).map { "i$it" }, second.map { it.id })

        val third = planLogoHydrationBatch(items, (first + second).map { it.id }.toSet())
        assertEquals((40 until 45).map { "i$it" }, third.map { it.id })
    }

    @Test
    fun `marked ids are never re-planned even without a successful write`() {
        val items = listOf(entry("a"), entry("b"))

        // No logo ever lands, but the marks stay — that is what keeps a
        // logo-less entry from being re-queried on every re-emission.
        val marked = setOf("a", "b")

        assertTrue(planLogoHydrationBatch(items, marked).isEmpty())
    }

    private fun entry(id: String, logo: String? = null): LibraryEntry = LibraryEntry(
        id = id,
        type = "movie",
        name = id,
        poster = null,
        background = null,
        logo = logo,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList(),
        addonBaseUrl = null
    )
}
