package com.nuvio.tv.ui.screens.settings.trackers

import com.nuvio.tv.data.mdblist.MdbListHomeIndex
import com.nuvio.tv.data.mdblist.MdbListHomeList
import com.nuvio.tv.data.mdblist.MdbListHomeListKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec 105 S5b2 D33: the Lists on Home count takes every MDBList Home row, the watchlist included. */
class ListsOnHomeTest {

    private val index = MdbListHomeIndex(
        watchlistTitle = "Watchlist",
        lists = listOf(
            MdbListHomeList(MdbListHomeListKind.OWN, 1, "Own", 3),
            MdbListHomeList(MdbListHomeListKind.LIKED, 2, "Liked", 9),
            MdbListHomeList(MdbListHomeListKind.EXTERNAL, 1, "Imported", 4)
        )
    )

    @Test
    fun `hidden MDBList rows, the watchlist included, are not counted as shown`() {
        val ui = listsOnHomeUi(index, listOf("mdblist_watchlist", "mdblist_ext_1", "addon_movie_top"))
        assertEquals(ListsOnHomeUi(shown = 2, total = 4), ui)
    }

    @Test
    fun `nothing hidden shows every row`() {
        assertEquals(ListsOnHomeUi(shown = 4, total = 4), listsOnHomeUi(index, emptyList()))
    }
}
