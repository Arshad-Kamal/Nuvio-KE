package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.data.mdblist.MdbListRatingSource
import com.nuvio.tv.data.mdblist.MdbListRatingSource.IMDB
import com.nuvio.tv.data.mdblist.MdbListRatingSource.LETTERBOXD
import com.nuvio.tv.data.mdblist.MdbListRatingSource.METACRITIC
import com.nuvio.tv.data.mdblist.MdbListRatingSource.TMDB
import com.nuvio.tv.data.mdblist.MdbListRatingSource.TOMATOES
import com.nuvio.tv.data.mdblist.MdbListScores
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 105 S4 C1: the ratings line's state and score text. */
class DetailMdbListRatingsTest {
    private val matrix = "tt0133093" to "movie"
    private val scores = MdbListScores(mapOf(IMDB to 8.7, TOMATOES to 83.0, TMDB to 82.0))

    @Test
    fun `the line shows only sources with a score, in the chosen order`() {
        val ui = DetailMdbListRatingsUi(listOf(TMDB, LETTERBOXD, IMDB), matrix, loaded = true, scores = scores)
        assertEquals(listOf(TMDB to 82.0, IMDB to 8.7), ui.shown)
        assertTrue(ui.reserved)
        assertTrue(DetailMdbListRatingsUi(listOf(IMDB), matrix).shown.isEmpty())
        assertFalse(DetailMdbListRatingsUi(emptyList(), matrix, loaded = true, scores = scores).reserved)
        assertTrue(DetailMdbListRatingsUi(emptyList(), matrix, loaded = true, scores = scores).shown.isEmpty())
    }

    @Test
    fun `new sources keep the same title's answer and drop another's`() {
        val ui = DetailMdbListRatingsUi(listOf(IMDB), matrix, loaded = true, scores = scores)
        val same = ui.withSources(listOf(TMDB), matrix)
        assertSame(scores, same.scores)
        assertTrue(same.loaded)
        val other = ui.withSources(listOf(IMDB), "tt0110912" to "movie")
        assertNull(other.scores)
        assertFalse(other.loaded)
        assertEquals("tt0110912" to "movie", other.titleKey)
    }

    @Test
    fun `an answer lands only on the title it was for, and an empty one still counts as loaded`() {
        val ui = DetailMdbListRatingsUi(listOf(IMDB), matrix)
        assertSame(scores, ui.withScores(matrix, scores).scores)
        assertSame(ui, ui.withScores("tt0110912" to "movie", scores))
        val none = ui.withScores(matrix, null)
        assertTrue(none.loaded)
        assertTrue(none.shown.isEmpty())
    }

    @Test
    fun `scores read as the mockup draws them`() {
        assertEquals("6.1", formatMdbListScore(IMDB, 6.1))
        assertEquals("46%", formatMdbListScore(TOMATOES, 46.0))
        assertEquals("52%", formatMdbListScore(MdbListRatingSource.POPCORN, 51.6))
        assertEquals("52", formatMdbListScore(METACRITIC, 52.0))
        assertEquals("2.9", formatMdbListScore(LETTERBOXD, 2.94))
        assertEquals("6.4", formatMdbListScore(TMDB, 64.0))
        assertEquals("6.4", formatMdbListScore(TMDB, 6.4))
        assertEquals("71%", formatMdbListScore(MdbListRatingSource.TRAKT, 71.0))
        assertEquals("8.6", formatMdbListScore(MdbListRatingSource.MAL, 8.62))
    }
}
