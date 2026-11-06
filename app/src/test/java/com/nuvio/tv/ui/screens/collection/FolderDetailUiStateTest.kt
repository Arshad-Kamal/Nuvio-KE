package com.nuvio.tv.ui.screens.collection

import com.nuvio.tv.domain.model.FolderViewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the follow-layout all-failed classifier (ke13 blank-tiles report,
 * 2026-09-03): the follow-layout rebuild skips errored tabs, so with every
 * source failed it publishes no rows and the screen must branch on this state.
 */
class FolderDetailUiStateTest {

    private fun tab(
        isLoading: Boolean = false,
        error: String? = null,
        isAllTab: Boolean = false,
        hasLoadedContent: Boolean = false
    ) = FolderTab(
        label = "source",
        isLoading = isLoading,
        error = error,
        isAllTab = isAllTab,
        hasLoadedContent = hasLoadedContent
    )

    @Test
    fun allSourceTabsTerminalWithErrorMeansAllFailed() {
        val state = FolderDetailUiState(
            tabs = listOf(
                tab(isAllTab = true, isLoading = true), // aggregate tab still waiting
                tab(error = "host unreachable"),
                tab(error = "http 404")
            )
        )
        assertTrue(state.allSourcesFailed)
    }

    @Test
    fun anyTabStillLoadingMeansNotAllFailed() {
        val state = FolderDetailUiState(
            tabs = listOf(tab(error = "boom"), tab(isLoading = true))
        )
        assertFalse(state.allSourcesFailed)
    }

    @Test
    fun paginationErrorOnLoadedContentMeansNotAllFailed() {
        // TMDB/Trakt append failures set `error` on tabs that already hold loaded
        // items (review M1, 2026-09-03): the grid keeps its content instead of
        // being wiped by the all-failed state.
        val state = FolderDetailUiState(
            tabs = listOf(
                tab(error = "timeout", hasLoadedContent = true),
                tab(error = "host unreachable")
            )
        )
        assertFalse(state.allSourcesFailed)
    }

    @Test
    fun anyTabLoadedSuccessfullyMeansNotAllFailed() {
        // A successful tab — even one that loaded zero items — keeps the grid
        // on its normal render path.
        val state = FolderDetailUiState(
            tabs = listOf(tab(error = "boom"), tab())
        )
        assertFalse(state.allSourcesFailed)
    }

    @Test
    fun emptyTabListMeansNotAllFailed() {
        // Folder lookup / empty folder has its own state; the classifier must
        // not fire before tabs exist.
        assertFalse(FolderDetailUiState().allSourcesFailed)
    }

    @Test
    fun storedRowsAndFollowLayoutBothDisplayAsRows() {
        // Spec 28 Q1: two display modes; the stored value is kept.
        assertEquals(FolderViewMode.TABBED_GRID, FolderViewMode.TABBED_GRID.displayMode)
        assertEquals(FolderViewMode.ROWS, FolderViewMode.ROWS.displayMode)
        assertEquals(FolderViewMode.ROWS, FolderViewMode.FOLLOW_LAYOUT.displayMode)
    }

    @Test
    fun onlyTheAllTabPresentMeansNotAllFailed() {
        // The All tab is an aggregate, not a source; it never counts.
        val state = FolderDetailUiState(
            tabs = listOf(tab(isAllTab = true, isLoading = true))
        )
        assertFalse(state.allSourcesFailed)
    }
}
