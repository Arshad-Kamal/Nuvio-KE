package com.nuvio.tv.data.repository

import com.nuvio.tv.domain.model.ContentRatingCap
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec 102: the age ratings two copies of one title in a page merge into (CatalogRepositoryImpl). */
class CatalogDuplicateRatingMergeTest {

    private fun copy(rating: String? = null, nonUs: Map<String, String> = emptyMap()) = MetaPreview(
        id = "tt1", type = ContentType.MOVIE, name = "Film", poster = null, posterShape = PosterShape.POSTER,
        background = null, logo = null, description = null, releaseInfo = null, imdbRating = null,
        genres = emptyList(), ageRating = rating, nonUsRatings = nonUs
    )

    private fun MetaPreview.tier() = ContentRatingCap.effectiveTierOf(ageRating, nonUsRatings)

    private val olderKidsBoards = mapOf("GB" to "PG", "DE" to "6", "KR" to "7")

    @Test
    fun `the stricter US code of either copy wins`() {
        assertEquals("R", copy("PG").withRatingsMerged(copy("R")).ageRating)
        assertEquals("R", copy("R").withRatingsMerged(copy("PG")).ageRating)
        assertEquals("R", copy("PG").withRatingsMerged(copy(nonUs = mapOf("US" to "R"))).ageRating)
    }

    @Test
    fun `a known US code beats a lenient copy's boards`() {
        // Red team 4305da59 m4: "NR" plus older-kids boards beside a copy rated R.
        val merged = copy("NR", olderKidsBoards).withRatingsMerged(copy("R"))
        assertEquals(ContentRatingCap.MATURE, merged.tier())
        assertEquals(emptyMap<String, String>(), merged.nonUsRatings)
    }

    @Test
    fun `without a US code both copies' boards count together`() {
        val merged = copy(nonUs = olderKidsBoards).withRatingsMerged(copy(nonUs = mapOf("FR" to "16")))
        assertEquals(ContentRatingCap.MATURE, merged.tier())
        // A one-board copy alone places nothing, but still raises the other copy.
        assertEquals(
            ContentRatingCap.ADULTS_ONLY,
            copy(nonUs = mapOf("KR" to "19")).withRatingsMerged(copy(nonUs = mapOf("GB" to "PG", "DE" to "6", "NL" to "6"))).tier()
        )
    }

    @Test
    fun `unknown ratings stay as they were`() {
        assertEquals("NR", copy("NR").withRatingsMerged(copy(null)).ageRating)
        assertEquals("NR", copy(null).withRatingsMerged(copy("NR")).ageRating)
        assertEquals(null, copy("NR").withRatingsMerged(copy(null)).tier())
    }
}
