package com.nuvio.tv.core.scout

import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoutSignalsTest {

    private val now = 100L * DAY

    @Test
    fun `finished titles seed newest first with recency decay`() {
        val seeds = scoutSeeds(
            watched = listOf(film("tt1", "Old", at = now - 10 * DAY), film("tt2", "New", at = now - DAY)),
            progress = emptyList(),
            now = now
        )

        assertEquals(listOf("tt2", "tt1"), seeds.map { it.contentId })
        assertEquals(SCOUT_FINISHED, seeds[0].weight, 1e-9)
        assertEquals(SCOUT_FINISHED * 0.97, seeds[1].weight, 1e-9)
    }

    @Test
    fun `a film played again after finishing counts as rewatched`() {
        val seeds = scoutSeeds(
            watched = listOf(film("tt1", "Loved", at = now - 5 * DAY), film("tt2", "Once", at = now - DAY)),
            progress = listOf(progress("tt1", "Loved", at = now - 2 * DAY, share = 0.5f)),
            now = now
        )

        assertEquals("tt1", seeds.first().contentId)
        assertEquals(SCOUT_REWATCHED * 0.97, seeds.first().weight, 1e-9)
    }

    @Test
    fun `a show dropped after two episodes long ago is negative and sorts last`() {
        val seeds = scoutSeeds(
            watched = listOf(
                episode("tt9", "Dropped", 1, 1, at = now - 30 * DAY),
                episode("tt9", "Dropped", 1, 2, at = now - 30 * DAY),
                film("tt1", "Kept", at = now - 40 * DAY)
            ),
            progress = emptyList(),
            now = now
        )

        assertEquals(listOf("tt1", "tt9"), seeds.map { it.contentId })
        assertTrue(seeds.last().weight < 0)
    }

    @Test
    fun `a show still being played is not dropped`() {
        val seeds = scoutSeeds(
            watched = listOf(episode("tt9", "Slow", 1, 1, at = now - 30 * DAY)),
            progress = listOf(progress("tt9", "Slow", at = now - DAY, share = 0.4f, type = "series", season = 1, episode = 2)),
            now = now
        )

        assertEquals(1, seeds.size)
        assertTrue(seeds.single().weight > 0)
    }

    @Test
    fun `with few finished titles what is being watched fills in`() {
        val seeds = scoutSeeds(
            watched = listOf(film("tt1", "Done", at = now - DAY)),
            progress = listOf(progress("tt5", "Halfway", at = now - DAY, share = 0.5f)),
            now = now
        )

        assertEquals(listOf("tt1", "tt5"), seeds.map { it.contentId })
        assertEquals(SCOUT_WATCHING, seeds[1].weight, 1e-9)
    }

    @Test
    fun `a film left early two weeks ago is negative`() {
        val seeds = scoutSeeds(
            watched = listOf(film("tt1", "Done", at = now - DAY)),
            progress = listOf(progress("tt7", "Quit", at = now - 20 * DAY, share = 0.1f)),
            now = now
        )

        assertEquals(SCOUT_STOPPED, seeds.single { it.contentId == "tt7" }.weight, 1e-9)
    }

    @Test
    fun `seeds are capped per sign`() {
        val films = (1..10).map { film("tt$it", "F$it", at = now - it * DAY) }
        val quits = (1..5).map { progress("tq$it", "Q$it", at = now - 20 * DAY, share = 0.05f) }

        val seeds = scoutSeeds(films, quits, now)

        assertEquals(SCOUT_MAX_POSITIVE_SEEDS, seeds.count { it.weight > 0 })
        assertEquals(SCOUT_MAX_NEGATIVE_SEEDS, seeds.count { it.weight < 0 })
    }

    @Test
    fun `a completed row after finishing is not a rewatch however late`() {
        // markAsCompleted stamps the progress row, completed, a moment after the watched item.
        val seeds = scoutSeeds(
            watched = listOf(film("tt1", "Once", at = now - 3 * DAY)),
            progress = listOf(progress("tt1", "Once", at = now - DAY, share = 1f)),
            now = now
        )

        assertEquals(SCOUT_FINISHED, seeds.single().weight, 1e-9)
    }

    @Test
    fun `an unfinished row within the hour after finishing is not a rewatch`() {
        val seeds = scoutSeeds(
            watched = listOf(film("tt1", "Once", at = now - DAY)),
            progress = listOf(progress("tt1", "Once", at = now - DAY + 5, share = 0.5f)),
            now = now
        )

        assertEquals(SCOUT_FINISHED, seeds.single().weight, 1e-9)
    }

    @Test
    fun `untitled progress rows still count as evidence`() {
        val seeds = scoutSeeds(
            watched = listOf(film("tt1", "Loved", at = now - 5 * DAY), episode("tt9", "Slow", 1, 1, at = now - 30 * DAY)),
            progress = listOf(
                progress("tt1", "", at = now - 2 * DAY, share = 0.5f),
                progress("tt9", "", at = now - DAY, share = 0.4f, type = "series", season = 1, episode = 2)
            ),
            now = now
        )

        assertEquals(SCOUT_REWATCHED, seeds.single { it.contentId == "tt1" }.weight, 1e-9)
        assertTrue(seeds.single { it.contentId == "tt9" }.weight > 0)
    }

    @Test
    fun `untitled history never seeds`() {
        val seeds = scoutSeeds(
            watched = listOf(film("tt1", "", at = now - DAY), film("tt2", "Named", at = now - DAY)),
            progress = listOf(progress("tt3", " ", at = now - DAY, share = 0.5f)),
            now = now
        )

        assertEquals(listOf("tt2"), seeds.filter { it.weight > 0 }.map { it.contentId })
    }

    @Test
    fun `three finished titles leave what is being watched out`() {
        val seeds = scoutSeeds(
            watched = (1..3).map { film("tt$it", "F$it", at = now - it * DAY) },
            progress = listOf(progress("tt5", "Halfway", at = now - DAY, share = 0.5f)),
            now = now
        )

        assertEquals(listOf("tt1", "tt2", "tt3"), seeds.map { it.contentId })
    }

    @Test
    fun `an untitled row still counts and the newest titled row names the seed`() {
        val seeds = scoutSeeds(
            watched = listOf(
                episode("tt9", "Dropped", 1, 1, at = now - 40 * DAY),
                episode("tt9", "", 1, 2, at = now - 30 * DAY),
                episode("tt9", "", 1, 3, at = now - 30 * DAY)
            ),
            progress = listOf(
                progress("tt5", "Halfway", at = now - 2 * DAY, share = 0.5f),
                progress("tt5", "", at = now - DAY, share = 0.6f)
            ),
            now = now
        )

        // Three finished episodes: not dropped after two.
        assertEquals("Dropped", seeds.single { it.contentId == "tt9" }.name)
        assertTrue(seeds.single { it.contentId == "tt9" }.weight > 0)
        assertEquals("Halfway", seeds.single { it.contentId == "tt5" }.name)
    }

    @Test
    fun `no history no seeds`() {
        assertTrue(scoutSeeds(emptyList(), emptyList(), now).isEmpty())
    }

    // Spec 100 S4: detail ratings (Arc weights: Love 6, Like 3, Not for me -3).

    @Test
    fun `a rating replaces what the history says, with the same recency decay`() {
        val seeds = scoutSeeds(
            listOf(film("tt1", "Sintel", now - 1), film("tt2", "Spring", now - 2), film("tt3", "Cosmos", now - 3)),
            emptyList(),
            now,
            listOf(rating("tt2", "Spring", ScoutRating.LOVE), rating("tt3", "Cosmos", ScoutRating.NOT_FOR_ME))
        )

        assertEquals(listOf("tt2", "tt1", "tt3"), seeds.map { it.contentId })
        assertEquals(6.0 * 0.97, seeds[0].weight, 1e-9)
        assertEquals(-3.0 * 0.97 * 0.97, seeds[2].weight, 1e-9)
        assertTrue(seeds[0].rated && !seeds[1].rated && seeds[2].rated)
    }

    @Test
    fun `a rated title with no history seeds at full weight and beats what is being watched`() {
        val seeds = scoutSeeds(
            emptyList(),
            listOf(progress("tt9", "Agent 327", now - DAY, 0.5f), progress("tt8", "Caminandes", now - DAY, 0.5f)),
            now,
            listOf(rating("tt5", "Big Buck Bunny", ScoutRating.LIKE), rating("tt8", "Caminandes", ScoutRating.LOVE, at = 2))
        )

        assertEquals(listOf("tt8" to 6.0, "tt5" to 3.0, "tt9" to SCOUT_WATCHING), seeds.map { it.contentId to it.weight })
    }

    @Test
    fun `not for me outranks a dropped show among the negatives`() {
        val dropped = (1..3).map { episode("tt$it", "Show $it", 1, 1, now - 30 * DAY) }
        val seeds = scoutSeeds(dropped, emptyList(), now, listOf(rating("tt7", "Morevna", ScoutRating.NOT_FOR_ME)))

        assertEquals("tt7", seeds.first().contentId)
        assertEquals(SCOUT_MAX_NEGATIVE_SEEDS, seeds.size)
    }

    @Test
    fun `a rating names a title its history left untitled and a blank rated-only title never seeds`() {
        val seeds = scoutSeeds(
            listOf(film("tt1", "", now - 1)),
            emptyList(),
            now,
            listOf(rating("tt1", "Sintel", ScoutRating.LIKE), rating("tt2", " ", ScoutRating.LOVE))
        )

        assertEquals(listOf("Sintel"), seeds.map { it.name })
    }

    @Test
    fun `a Not for me deep in the history still beats dropped shows`() {
        // -3 * 0.97^39 = -0.91, weaker than a quit film's flat -1: by weight alone the quits would lead.
        val films = (0..39).map { film("tt$it", "F$it", now - 1 - it) }
        val quits = (50..52).map { progress("tt$it", "Q$it", now - 30 * DAY, 0.1f) }
        val seeds = scoutSeeds(films, quits, now, listOf(rating("tt39", "F39", ScoutRating.NOT_FOR_ME)))

        assertEquals("tt39", seeds.first { it.weight < 0 }.contentId)
    }

    @Test
    fun `a rating matches history under any id it stored, never by title`() {
        val seeds = scoutSeeds(
            listOf(film("tt1", "Sintel", now - 1), film("tt2", "Spring", now - 2), film("tmdb:3", "Cosmos", now - 3)),
            emptyList(),
            now,
            listOf(
                // Saved on a tmdb: page whose meta named tt1: the history's tt1 is this title.
                rating("tmdb:45745", "Sintel", ScoutRating.NOT_FOR_ME).copy(imdbId = "tt1", tmdbId = 45745),
                // Same name as tt2, but no shared id: another title.
                rating("tmdb:9", "Spring", ScoutRating.LOVE),
                // A stored TMDB id meets the history's tmdb:3 of the same type.
                rating("tt3", "Cosmos Laundromat", ScoutRating.LIKE).copy(tmdbId = 3)
            )
        )

        assertEquals(listOf("tmdb:9", "tmdb:3", "tt2", "tt1"), seeds.map { it.contentId })
        listOf(6.0, 3.0 * 0.97 * 0.97, 0.97, -3.0).zip(seeds) { want, seed -> assertEquals(want, seed.weight, 1e-9) }
        assertEquals(listOf(null, 3, null, 45745), seeds.map { it.tmdbId })
    }

    @Test
    fun `an untitled rating still replaces the progress signal`() {
        val seeds = scoutSeeds(emptyList(), listOf(progress("tt9", "Agent 327", now - DAY, 0.5f)), now, listOf(rating("tt9", "", ScoutRating.NOT_FOR_ME)))

        assertTrue(seeds.isEmpty())
    }

    private fun rating(id: String, name: String, rating: ScoutRating, at: Long = 1, tv: Boolean = false) =
        ScoutRatingEntry(contentId = id, tv = tv, name = name, rating = rating, ratedAt = at)

    private fun film(id: String, title: String, at: Long) =
        WatchedItem(contentId = id, contentType = "movie", title = title, watchedAt = at)

    private fun episode(id: String, title: String, season: Int, episode: Int, at: Long) =
        WatchedItem(contentId = id, contentType = "series", title = title, season = season, episode = episode, watchedAt = at)

    private fun progress(
        id: String,
        name: String,
        at: Long,
        share: Float,
        type: String = "movie",
        season: Int? = null,
        episode: Int? = null
    ) = WatchProgress(
        contentId = id, contentType = type, name = name, poster = null, backdrop = null, logo = null,
        videoId = id, season = season, episode = episode, episodeTitle = null,
        position = (share * 1000).toLong(), duration = 1000, lastWatched = at
    )

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }
}
