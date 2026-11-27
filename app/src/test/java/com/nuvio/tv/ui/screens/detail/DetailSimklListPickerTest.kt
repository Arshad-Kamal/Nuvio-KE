package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.core.tracking.TrackingListStatus
import com.nuvio.tv.core.tracking.TrackingListStatus.COMPLETED
import com.nuvio.tv.core.tracking.TrackingListStatus.DROPPED
import com.nuvio.tv.core.tracking.TrackingListStatus.ON_HOLD
import com.nuvio.tv.core.tracking.TrackingListStatus.PLAN_TO_WATCH
import com.nuvio.tv.core.tracking.TrackingListStatus.WATCHING
import com.nuvio.tv.data.simkl.SimklListing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 106 F1, F1b, F2: which lists the picker offers, where it opens, and what the Remove confirm names. */
class DetailSimklListPickerTest {
    private fun picker(list: TrackingListStatus?, isMovie: Boolean = false, focusRemove: Boolean = false) =
        SimklListPickerUi(isMovie, SimklListing(list), focusRemove = focusRemove)

    @Test
    fun `a series gets all five lists in Simkl's order, a film three`() {
        assertEquals(listOf(WATCHING, PLAN_TO_WATCH, ON_HOLD, COMPLETED, DROPPED), simklPickerLists(isMovie = false))
        assertEquals(listOf(PLAN_TO_WATCH, COMPLETED, DROPPED), simklPickerLists(isMovie = true))
    }

    @Test
    fun `the picker opens on the current list, else Plan to watch, or on Remove after a Cancel`() {
        assertEquals(ON_HOLD, simklPickerFocus(picker(ON_HOLD)))
        assertEquals(PLAN_TO_WATCH, simklPickerFocus(picker(null, isMovie = true)))
        // A film Simkl lists as Watching (no such row for films) opens on Plan to watch.
        assertEquals(PLAN_TO_WATCH, simklPickerFocus(picker(WATCHING, isMovie = true)))
        assertNull(simklPickerFocus(picker(COMPLETED, focusRemove = true)))
        // Nothing to remove: no Remove row to land on.
        assertEquals(PLAN_TO_WATCH, simklPickerFocus(picker(null, focusRemove = true)))
    }

    @Test
    fun `the confirm names each loss only when there is one`() {
        fun body(watched: Int, rating: Int?, isMovie: Boolean = false) =
            simklRemovalBody(SimklListing(WATCHING, watched, rating), isMovie)
        assertEquals(SimklRemovalBody.EPISODES_AND_RATING, body(2, 8))
        assertEquals(SimklRemovalBody.EPISODES, body(2, null))
        assertEquals(SimklRemovalBody.RATING, body(0, 8))
        assertEquals(SimklRemovalBody.FILM_AND_RATING, body(1, 8, isMovie = true))
        assertEquals(SimklRemovalBody.FILM, body(1, null, isMovie = true))
        assertEquals(SimklRemovalBody.LIST, body(0, null))
    }

    @Test
    fun `Dropped is offered to keep what goes, unless nothing goes or it is on Dropped`() {
        assertTrue(simklRemovalKeepHint(SimklListing(COMPLETED, watched = 3), isMovie = false))
        assertFalse(simklRemovalKeepHint(SimklListing(WATCHING), isMovie = false))
        assertFalse(simklRemovalKeepHint(SimklListing(DROPPED, watched = 3, rating = 6), isMovie = false))
    }

    @Test
    fun `only a loss beyond a bare Plan to watch asks first`() {
        assertFalse(SimklListing(null).removalNeedsConfirm)
        assertFalse(SimklListing(PLAN_TO_WATCH).removalNeedsConfirm)
        assertTrue(SimklListing(PLAN_TO_WATCH, rating = 5).removalNeedsConfirm)
        assertTrue(SimklListing(PLAN_TO_WATCH, watched = 1).removalNeedsConfirm)
        assertTrue(SimklListing(DROPPED).removalNeedsConfirm)
    }
}
