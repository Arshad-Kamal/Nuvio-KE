package com.nuvio.tv.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Test

/** Home's row order with collections and MDBList rows as free rows (Spec 105 S5b1). */
class HomeCatalogOrderTest {

    private val defaultOrder = listOf("a_movie_top", "a_series_top", "b_movie_new")
    private val owners = mapOf("a_movie_top" to "a", "a_series_top" to "a", "b_movie_new" to "b")

    private fun order(
        saved: List<String>,
        mdbList: List<String> = emptyList(),
        disabled: Set<String> = emptySet(),
        follow: Boolean = false,
        collections: List<String> = emptyList()
    ) = resolveHomeCatalogOrder(defaultOrder, collections, mdbList, saved, disabled, follow, owners)

    @Test
    fun `MDBList keys not saved yet come first in both modes`() {
        val mdbList = listOf("mdblist_watchlist", "mdblist_7")
        val expected = mdbList + defaultOrder
        assertEquals(expected, order(saved = emptyList(), mdbList = mdbList))
        assertEquals(expected, order(saved = emptyList(), mdbList = mdbList, follow = true))
        assertEquals(expected, order(saved = listOf("a_movie_top"), mdbList = mdbList, follow = true))
    }

    @Test
    fun `a saved MDBList key keeps its slot, in follow mode at a block boundary`() {
        val saved = listOf("a_movie_top", "mdblist_7", "a_series_top", "b_movie_new")
        assertEquals(saved, order(saved = saved, mdbList = listOf("mdblist_7")))
        assertEquals(
            listOf("a_movie_top", "a_series_top", "mdblist_7", "b_movie_new"),
            order(saved = saved, mdbList = listOf("mdblist_7"), follow = true)
        )
    }

    @Test
    fun `hidden MDBList keys and keys no longer available are left out, disabled collections stay`() {
        val saved = listOf("mdblist_7", "mdblist_8", "mdblist_9", "collection_1") + defaultOrder
        assertEquals(
            listOf("mdblist_7", "collection_1") + defaultOrder,
            order(saved = saved, mdbList = listOf("mdblist_7", "mdblist_8"), disabled = setOf("mdblist_8", "collection_1"), collections = listOf("collection_1"))
        )
    }

    @Test
    fun `more than ten arriving at once hide those past ten before any admission is stored`() {
        val arriving = listOf("mdblist_watchlist") + (1..11).map { "mdblist_$it" }
        assertEquals(arriving.take(10) + defaultOrder, order(saved = defaultOrder, mdbList = arriving))
    }

    @Test
    fun `an addon whose id is mdblist stays an addon catalog`() {
        val addonOrder = listOf("mdblist_movie_top", "a_movie_top")
        val result = resolveHomeCatalogOrder(
            addonOrder, emptyList(), emptyList(), listOf("a_movie_top", "mdblist_movie_top"), emptySet(), true,
            mapOf("mdblist_movie_top" to "mdblist", "a_movie_top" to "a")
        )
        assertEquals(addonOrder, result)
    }
}
