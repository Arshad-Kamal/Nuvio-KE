package com.nuvio.tv.core.scout

import com.nuvio.tv.data.remote.api.TmdbRecommendationResult
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoutPicksTest {

    private val none = ScoutExclusions(emptySet(), emptySet())
    private val sintel = ScoutSeed("tt1", tv = false, name = "Sintel", weight = 1.0)
    private val steel = ScoutSeed("tt2", tv = false, name = "Tears of Steel", weight = 1.0)

    @Test
    fun `a title suggested by two seeds beats one suggested once`() {
        val picks = scoutRank(
            listOf(sintel to listOf(rec(10, "Spring"), rec(11, "Shared")), steel to listOf(rec(11, "Shared"))),
            none
        )

        assertEquals(listOf(11, 10), picks.map { it.tmdbId })
    }

    @Test
    fun `the reason is the seed that gave the biggest share`() {
        val picks = scoutRank(
            listOf(
                sintel to (1..20).map { rec(100 + it, "Filler $it") } + rec(11, "Shared"),
                steel to listOf(rec(11, "Shared"))
            ),
            none
        )

        assertEquals("Tears of Steel", picks.single { it.tmdbId == 11 }.reason)
    }

    @Test
    fun `watched titles low votes and low ratings are left out`() {
        val picks = scoutRank(
            listOf(
                sintel to listOf(
                    rec(1, "Seen by id"),
                    rec(2, "Seen By Title!"),
                    rec(3, "Few votes", votes = 10),
                    rec(4, "Poor", rating = 5.0),
                    rec(5, "Fine")
                )
            ),
            ScoutExclusions(keys = setOf(scoutKey(1, tv = false)), titles = setOf(scoutTitleKey("seen by title", tv = false)))
        )

        assertEquals(listOf(5), picks.map { it.tmdbId })
    }

    @Test
    fun `a negative seed cancels a weak pick`() {
        val dropped = ScoutSeed("tt9", tv = false, name = "Dropped", weight = -1.0)
        val picks = scoutRank(listOf(sintel to listOf(rec(10, "Both")), dropped to listOf(rec(10, "Both"))), none)

        assertTrue(picks.isEmpty())
    }

    @Test
    fun `media type decides the key and an untyped result takes the seed's type`() {
        val show = ScoutSeed("tt3", tv = true, name = "Cosmos", weight = 1.0)
        val picks = scoutRank(listOf(show to listOf(rec(7, "Untyped"), rec(8, "Film", mediaType = "movie"))), none)

        assertEquals(setOf("tv:7", "movie:8"), picks.map { it.key }.toSet())
    }

    @Test
    fun `neighbours with the same reason are split up when another can go between`() {
        val picks = scoutRank(
            listOf(
                sintel to listOf(rec(1, "A1"), rec(2, "A2"), rec(3, "A3")),
                // Fourth place, so by score alone B1 would come last.
                steel to listOf(rec(90, "X"), rec(91, "Y"), rec(92, "Z"), rec(4, "B1"))
            ),
            ScoutExclusions(keys = setOf(scoutKey(90, false), scoutKey(91, false), scoutKey(92, false)), titles = emptySet())
        )

        assertEquals(listOf(1, 4, 2, 3), picks.map { it.tmdbId })
    }

    @Test
    fun `quality floor edges`() {
        val picks = scoutRank(
            listOf(
                sintel to listOf(
                    rec(1, "Exactly 50 votes and 6", votes = 50, rating = 6.0),
                    rec(2, "No rating", rating = null),
                    rec(3, "No votes", votes = null),
                    rec(4, "49 votes", votes = 49)
                )
            ),
            none
        )

        assertEquals(setOf(1, 2), picks.map { it.tmdbId }.toSet())
    }

    @Test
    fun `the list stops at the limit`() {
        val picks = scoutRank(listOf(sintel to (1..30).map { rec(it, "T$it") }), none, limit = 5)

        assertEquals(5, picks.size)
    }

    @Test
    fun `a pick round-trips through kotlinx serialization`() {
        val pick = scoutRank(listOf(sintel to listOf(rec(10, "Spring", year = "2019-04-04"))), none).single()

        assertEquals(pick, Json.decodeFromString<ScoutPick>(Json.encodeToString(pick)))
        assertEquals("2019", pick.year)
    }

    @Test
    fun `a new rating replaces every rating of its title and keeps the ids only they knew`() {
        val older = ScoutRatingEntry("tt1", false, "Sintel", ScoutRating.LIKE, 1, imdbId = "tt1")
        val newer = ScoutRatingEntry("kitsu:1", false, "Sintel", ScoutRating.LOVE, 2, tmdbId = 45745)
        val other = ScoutRatingEntry("tt2", false, "Spring", ScoutRating.LIKE, 3, imdbId = "tt2", tmdbId = 2)
        val now = ScoutRatingEntry("tmdb:45745", false, "Sintel", ScoutRating.NOT_FOR_ME, 4, imdbId = "tt1")

        val next = scoutRatingsWith(listOf(older, newer, other), scoutContentKeys("tmdb:45745", false), now)

        assertEquals(listOf(other, now.copy(tmdbId = 45745)), next)
        assertEquals(listOf(other), scoutRatingsWith(next, scoutContentKeys("tt1", false), null))
        // A show and a film with one TMDB number are two titles.
        assertEquals(2, scoutRatingsWith(listOf(other), setOf(scoutKey(2, true)), now.copy(tv = true, contentId = "tmdb:2", imdbId = null)).size)
    }

    private fun rec(
        id: Int,
        title: String,
        votes: Int? = 500,
        rating: Double? = 7.0,
        mediaType: String? = null,
        year: String? = null
    ) = TmdbRecommendationResult(
        id = id, title = title, mediaType = mediaType, posterPath = "/p$id.jpg",
        releaseDate = year, voteAverage = rating, voteCount = votes
    )
}
