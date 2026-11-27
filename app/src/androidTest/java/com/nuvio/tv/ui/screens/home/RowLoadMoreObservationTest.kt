@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.nuvio.tv.ui.screens.home

import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.StableList
import com.nuvio.tv.ui.util.StableRef
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 117 S4c (R12-M2, R13-m4): the real [MarqueeRowSection] trigger observes its inputs. A
 * load-more page that adds no drawn card fires the next with no input, at most three pages; an
 * effect restart does not re-arm it, focus entering the row does. Four cards, so any laid-out card
 * is within the end distance (no screen-width dependence).
 *
 * Observed in real time on a plain activity: under the Compose test rule this row never reports
 * idle (pending recomposer work that never composes; the row and its cards compose 2-5 times, then
 * not at all, measured 2026-11-16), so the rule's idle waits time out.
 */
@RunWith(AndroidJUnit4::class)
class RowLoadMoreObservationTest {

    @get:Rule
    val activity = ActivityScenarioRule(ComponentActivity::class.java)

    private fun onMain(block: () -> Unit) = activity.scenario.onActivity { block() }

    /** Waits up to 5 s for [requests] to equal [expected], then 1 s more to see that it stays. */
    private fun awaitRequests(expected: List<Int>, message: String? = null) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && snapshot() != expected) Thread.sleep(50)
        Thread.sleep(1_000)
        assertEquals(message, expected, snapshot())
    }

    private val requests = mutableListOf<Int>()

    private fun snapshot(): List<Int> {
        var copy = emptyList<Int>()
        onMain { copy = requests.toList() }
        return copy
    }

    private val rowKey = "addon_top_movie"

    private fun row(page: Int) = HeroCarouselRow(
        key = rowKey,
        title = "Top",
        globalRowIndex = 0,
        items = StableList((0 until 4).map { i ->
            catalogCarouselItem(item(i), rowKey, "https://addon.example", useLandscapePosters = false, occurrence = 0)
        }),
        catalogId = "top",
        addonId = "addon",
        apiType = "movie",
        supportsSkip = true,
        hasMore = true,
        currentPage = page
    )

    private fun item(i: Int) = MetaPreview(
        id = "tt$i", type = ContentType.MOVIE, name = "Title $i", poster = null, posterShape = PosterShape.POSTER,
        background = null, logo = null, description = null, releaseInfo = null, imdbRating = null, genres = emptyList()
    )

    @Test
    fun aPageAddingNoCardFiresTheNextUntilThreeAndFocusEntryReArms() {
        val rowState = mutableStateOf(row(page = 0))
        val focus = MarqueeBandFocus().apply { activeRowKey.value = rowKey }
        val mount = mutableIntStateOf(0)
        activity.scenario.onActivity { it.setContent {
            NuvioTheme {
                // A new mount restarts the trigger's effect (as a shifted LazyColumn key does) with the same entry map.
                key(mount.intValue) { MarqueeRowSection(
                    row = rowState.value,
                    rowIndex = 0,
                    activeRowIndex = mutableStateOf(0),
                    isActiveRow = { focus.activeRowKey.value == rowKey },
                    rowFocusRequester = FocusRequester(),
                    itemFocusRequesters = StableRef(mutableMapOf()),
                    defaultBringIntoViewSpec = LocalBringIntoViewSpec.current,
                    focusStateCatalogRowScrollIndex = 0,
                    focusedItemByRow = StableRef(focusedByRow),
                    rowListStates = StableRef(rowListStates),
                    loadMoreEntries = StableRef(entries),
                    isVerticalRowsScrollingState = mutableStateOf(false),
                    focus = focus,
                    onRowItemFocused = { _, _, _ -> },
                    useLandscapePosters = false,
                    accent = { Color.White },
                    isCatalogItemWatched = { false },
                    onCatalogItemLongPress = { _, _ -> },
                    onNavigateToDetail = { _, _, _ -> },
                    onLoadMoreCatalog = { _, _, _ ->
                        // As a view model whose page added no drawn card: the page moves, the cards do not.
                        val current = rowState.value
                        requests += current.currentPage
                        rowState.value = row(page = current.currentPage + 1)
                    }
                ) }
            }
        } }
        awaitRequests(listOf(0, 1, 2))

        onMain { mount.intValue++ }
        awaitRequests(listOf(0, 1, 2), "a restart with unchanged inputs is no reset")

        onMain { focus.activeRowKey.value = "another_row" }
        Thread.sleep(500)
        onMain { focus.activeRowKey.value = rowKey }
        awaitRequests(listOf(0, 1, 2, 3, 4, 5))
    }

    private val focusedByRow = mutableStateMapOf<String, Int>()
    private val rowListStates = mutableStateMapOf<String, LazyListState>()
    private val entries = mutableStateMapOf<String, RowLoadMoreEntry>()
}
