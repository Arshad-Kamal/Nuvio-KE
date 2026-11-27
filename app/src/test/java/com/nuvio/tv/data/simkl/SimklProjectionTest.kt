package com.nuvio.tv.data.simkl

import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 106 S2a: what Continue Watching, Up next and the watched marks read from a Simkl snapshot. */
class SimklProjectionTest {

    private fun snapshot(entries: String = "{}", playback: String = "[]") = SimklSnapshot(
        entries = decodeAllItems(entries),
        playback = simklJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(SimklPlayback.serializer()), playback)
    )

    private fun showPlayback(simkl: Long, imdb: String, pausedAt: String, season: Int = 1, episode: Int = 2) =
        """{"id":$simkl$season$episode,"progress":42.5,"paused_at":"$pausedAt","episode":{"season":$season,"number":$episode},
            "show":{"title":"Show","ids":{"simkl":$simkl,"imdb":"$imdb"}}}"""

    @Test
    fun `a playback becomes a resume point under the title's IMDb id`() {
        val progress = projectSimkl(snapshot(playback = "[${showPlayback(1, "tt1", "2026-11-01T10:00:00Z")}]")).progress.single()
        assertEquals("tt1", progress.contentId)
        assertEquals("series", progress.contentType)
        assertEquals(1, progress.season)
        assertEquals(2, progress.episode)
        assertEquals("tt1:1:2", progress.videoId)
        assertEquals(42.5f, progress.progressPercent)
        assertEquals(WatchProgress.SOURCE_SIMKL_PLAYBACK, progress.source)
        assertEquals(112L, progress.simklPlaybackId)
        assertEquals(parseSimklTime("2026-11-01T10:00:00Z"), progress.lastWatched)
    }

    @Test
    fun `playbacks of titles on hold or dropped are not shown`() {
        val entries = """{"shows":[{"status":"hold","show":{"ids":{"simkl":1}}},{"status":"dropped","show":{"ids":{"simkl":2}}},
            {"status":"watching","show":{"ids":{"simkl":3}}}]}"""
        val playback = listOf(showPlayback(1, "tt1", "2026-11-01T10:00:00Z"), showPlayback(2, "tt2", "2026-11-01T10:00:00Z"),
            showPlayback(3, "tt3", "2026-11-01T10:00:00Z")).joinToString(prefix = "[", postfix = "]")
        assertEquals(listOf("tt3"), projectSimkl(snapshot(entries, playback)).progress.map { it.contentId })
    }

    @Test
    fun `a playback older than the completion is hidden, a later one is a rewatch and shows`() {
        val entries = """{"shows":[{"status":"completed","last_watched_at":"2026-11-01T12:00:00Z","show":{"ids":{"simkl":1}}},
            {"status":"completed","last_watched_at":"2026-11-01T08:00:00Z","show":{"ids":{"simkl":2}}}]}"""
        val playback = listOf(showPlayback(1, "tt1", "2026-11-01T10:00:00Z"), showPlayback(2, "tt2", "2026-11-01T10:00:00Z"))
            .joinToString(prefix = "[", postfix = "]")
        assertEquals(listOf("tt2"), projectSimkl(snapshot(entries, playback)).progress.map { it.contentId })
    }

    @Test
    fun `ids fall back from IMDb to TMDB, Kitsu, MAL and Simkl, and a JSON null is no id`() {
        fun media(ids: String) = simklJson.decodeFromString(SimklMedia.serializer(), """{"ids":$ids}""")
        assertEquals("tmdb:603", media("""{"simkl":1,"imdb":null,"tmdb":"603"}""").contentId())
        assertEquals("kitsu:7", media("""{"simkl":1,"kitsu":7,"mal":"9"}""").contentId())
        assertEquals("mal:9", media("""{"simkl":1,"mal":"9"}""").contentId())
        assertEquals("simkl:1", media("""{"simkl":"1","imdb":""}""").contentId())
        assertNull(media("""{"imdb":"null"}""").id("tmdb"))
        assertNull(parseSimklTime("+999999999-12-31T23:59:59Z"))
        assertNull(parseSimklTime("yesterday"))
        assertEquals(1_763_000_000_000L, parseSimklTime("2025-11-13T02:13:20Z"))
        assertEquals(1_763_000_000_500L, parseSimklTime("2025-11-13T02:13:20.5Z"))
        assertEquals(1_763_000_000_000L, parseSimklTime("2025-11-12T21:13:20-05:00"))
    }

    @Test
    fun `anime episodes use their TVDB numbering when Simkl gives one`() {
        val entries = """{"anime":[{"status":"watching","next_to_watch":"S01E05","show":{"title":"AoT S2","ids":{"simkl":5,"imdb":"tt2560140"}},
            "seasons":[{"number":1,"episodes":[{"number":4,"watched_at":"2026-11-01T10:00:00Z","tvdb":{"season":2,"episode":4}}]}]}]}"""
        val playback = """[{"id":1,"progress":10,"paused_at":"2026-11-01T11:00:00Z","episode":{"season":1,"number":5,"tvdb_season":2,"tvdb_number":5},
            "anime":{"ids":{"simkl":5,"imdb":"tt2560140"}}}]"""
        val projected = projectSimkl(snapshot(entries, playback))
        val mark = projected.watchedItems.single()
        assertEquals(2 to 4, mark.season to mark.episode)
        assertEquals(2 to 5, projected.progress.single().let { it.season to it.episode })
        assertEquals(2 to 4, projected.nextUpSeeds.single().let { it.season to it.episode })
    }

    private val animeSeason2 = """{"anime":[{"status":"watching","next_to_watch":"S01E05","last_watched_at":"2026-11-01T10:00:00Z",
        "show":{"title":"AoT S2","ids":{"simkl":5,"imdb":"tt2560140","tmdb":"1429","tvdb":"267440","mal":"25777","kitsu":"8671"}},
        "seasons":[{"number":1,"episodes":[{"number":4,"watched_at":"2026-11-01T10:00:00Z","tvdb":{"season":2,"episode":4}}]}]}]}"""
    private val animePlayback = """[{"id":1,"progress":10,"paused_at":"2026-11-01T11:00:00Z","episode":{"season":1,"number":5,"tvdb_season":2,"tvdb_number":5},
        "anime":{"ids":{"simkl":5,"imdb":"tt2560140","mal":"25777","kitsu":"8671"}}}]"""

    @Test
    fun `one per season names anime by Kitsu and numbers episodes within the season`() {
        val projected = projectSimkl(snapshot(animeSeason2, animePlayback), SimklAnimeSeasons.PER_SEASON)
        val mark = projected.watchedItems.single()
        assertEquals("kitsu:8671", mark.contentId)
        assertEquals(1 to 4, mark.season to mark.episode)
        assertEquals(Triple("kitsu:8671", 1, 5), projected.progress.single().let { Triple(it.contentId, it.season, it.episode) })
        assertEquals("kitsu:8671:1:5", projected.progress.single().videoId)
        assertEquals(1 to 4, projected.nextUpSeeds.single().let { it.season to it.episode })
        assertEquals(setOf(1 to 4, 1 to 5), projected.episodeProgress.getValue("kitsu:8671").keys)
    }

    @Test
    fun `one per show names anime by IMDb, else TMDB, else TVDB, with the TVDB numbering`() {
        val projected = projectSimkl(snapshot(animeSeason2, animePlayback), SimklAnimeSeasons.PER_SHOW)
        assertEquals("tt2560140", projected.watchedItems.single().contentId)
        assertEquals(2 to 4, projected.watchedItems.single().let { it.season to it.episode })
        fun media(ids: String) = simklJson.decodeFromString(SimklMedia.serializer(), """{"ids":$ids}""")
        val noImdb = media("""{"simkl":5,"tmdb":"1429","tvdb":"267440","kitsu":"8671"}""")
        assertEquals("tmdb:1429", noImdb.contentId(SimklAnimeSeasons.PER_SHOW))
        assertEquals("tvdb:267440", media("""{"simkl":5,"tvdb":"267440","kitsu":"8671"}""").contentId(SimklAnimeSeasons.PER_SHOW))
        assertEquals("mal:25777", media("""{"simkl":5,"imdb":"tt1","mal":"25777"}""").contentId(SimklAnimeSeasons.PER_SEASON))
        assertEquals("tt1", media("""{"simkl":5,"imdb":"tt1"}""").contentId(SimklAnimeSeasons.PER_SEASON))
        // Films and shows never take the anime order: a show's TVDB id is not a name.
        assertEquals("kitsu:8671", media("""{"simkl":5,"tvdb":"267440","kitsu":"8671"}""").contentId())
    }

    @Test
    fun `one per season with no Kitsu or MAL id keeps the TVDB numbering of the show-wide id`() {
        val entries = """{"anime":[{"status":"watching","show":{"ids":{"simkl":5,"imdb":"tt2560140"}},
            "seasons":[{"number":1,"episodes":[{"number":4,"watched_at":"2026-11-01T10:00:00Z","tvdb":{"season":2,"episode":4}}]}]},
            {"status":"watching","show":{"ids":{"simkl":6,"imdb":"tt2560140"}},
            "seasons":[{"number":1,"episodes":[{"number":4,"watched_at":"2026-11-01T10:00:00Z","tvdb":{"season":3,"episode":4}}]}]}]}"""
        val marks = projectSimkl(snapshot(entries), SimklAnimeSeasons.PER_SEASON).watchedItems
        assertEquals(setOf(Triple("tt2560140", 2, 4), Triple("tt2560140", 3, 4)), marks.map { Triple(it.contentId, it.season, it.episode) }.toSet())
    }

    @Test
    fun `one per season keeps an entry's specials in season 0`() {
        val entries = """{"anime":[{"status":"watching","show":{"ids":{"simkl":5,"kitsu":"8671"}},
            "seasons":[{"number":0,"episodes":[{"number":1,"watched_at":"2026-11-01T10:00:00Z"}]},
            {"number":1,"episodes":[{"number":1,"watched_at":"2026-11-01T10:00:00Z"}]}]}]}"""
        val marks = projectSimkl(snapshot(entries), SimklAnimeSeasons.PER_SEASON).watchedItems
        assertEquals(setOf(0 to 1, 1 to 1), marks.map { it.season to it.episode }.toSet())
    }

    @Test
    fun `anime films keep the film naming in both modes`() {
        val entries = """{"anime":[{"status":"completed","anime_type":"movie",
            "show":{"ids":{"simkl":3,"tmdb":"372058","tvdb":"999","kitsu":"11614"}}}]}"""
        SimklAnimeSeasons.entries.forEach { mode ->
            val projected = projectSimkl(snapshot(entries), mode)
            assertEquals(mode.name, listOf("tmdb:372058"), projected.watchedItems.map { it.contentId })
            assertTrue(mode.name, "tmdb:372058" in projected.watchedMovieIds)
        }
    }

    @Test
    fun `the anime mode leaves shows and films as they are`() {
        val entries = """{"shows":[{"status":"watching","show":{"ids":{"simkl":1,"imdb":"tt1","kitsu":"7"}},
            "seasons":[{"number":2,"episodes":[{"number":3,"watched_at":"2026-11-01T00:00:00Z"}]}]}],
            "movies":[{"status":"completed","movie":{"ids":{"simkl":2,"tmdb":"603","kitsu":"9"}}}]}"""
        val projected = projectSimkl(snapshot(entries), SimklAnimeSeasons.PER_SEASON)
        assertEquals(listOf(Triple("tt1", 2, 3), Triple("tmdb:603", null, null)),
            projected.watchedItems.map { Triple(it.contentId, it.season, it.episode) })
        assertEquals(SimklAnimeSeasons.PER_SHOW, SimklAnimeSeasons.fromStorage("weird"))
        assertEquals(SimklAnimeSeasons.PER_SEASON, SimklAnimeSeasons.fromStorage(" Season "))
        assertEquals(SimklAnimeSeasons.PER_SHOW, SimklAnimeSeasons.fromStorage(null))
    }

    @Test
    fun `Up next seeds come from watching shows with something left, at their latest watched episode`() {
        val entries = """{"shows":[
            {"status":"watching","next_to_watch":"S01E03","last_watched_at":"2026-11-02T00:00:00Z","show":{"ids":{"simkl":1,"imdb":"tt1"}},
             "seasons":[{"number":1,"episodes":[{"number":1,"watched_at":"2026-11-01T00:00:00Z"},{"number":2,"watched_at":"2026-11-02T00:00:00Z"}]}]},
            {"status":"watching","show":{"ids":{"simkl":2,"imdb":"tt2"}},"seasons":[{"number":1,"episodes":[{"number":1}]}]},
            {"status":"hold","next_to_watch":"S01E02","show":{"ids":{"simkl":3,"imdb":"tt3"}},"seasons":[{"number":1,"episodes":[{"number":1}]}]}]}"""
        val seed = projectSimkl(snapshot(entries)).nextUpSeeds.single()
        assertEquals("tt1", seed.contentId)
        assertEquals(1 to 2, seed.season to seed.episode)
        assertEquals(100f, seed.progressPercent)
    }

    @Test
    fun `Up next has one seed per show and a special never leads it`() {
        val entries = """{"shows":[
            {"status":"watching","next_to_watch":"S01E02","show":{"ids":{"simkl":1,"imdb":"tt1"}},
             "seasons":[{"number":1,"episodes":[{"number":1,"watched_at":"2026-11-01T00:00:00Z"}]},
                        {"number":0,"episodes":[{"number":1,"watched_at":"2026-11-05T00:00:00Z"}]}]},
            {"status":"watching","next_to_watch":"S02E01","show":{"ids":{"simkl":2,"imdb":"tt1"}},
             "seasons":[{"number":1,"episodes":[{"number":9,"watched_at":"2026-10-01T00:00:00Z"}]}]}]}"""
        val seed = projectSimkl(snapshot(entries)).nextUpSeeds.single()
        assertEquals(1 to 1, seed.season to seed.episode)
    }

    @Test
    fun `one resume point per title, the newest`() {
        val playback = listOf(showPlayback(1, "tt1", "2026-11-01T10:00:00Z", episode = 2), showPlayback(1, "tt1", "2026-11-02T10:00:00Z", episode = 3))
            .joinToString(prefix = "[", postfix = "]")
        assertEquals(3, projectSimkl(snapshot(playback = playback)).progress.single().episode)
    }

    @Test
    fun `completed films are watched in every id form, other films are not`() {
        val entries = """{"movies":[{"status":"completed","last_watched_at":"2026-11-01T00:00:00Z","movie":{"title":"Matrix","ids":{"simkl":1,"imdb":"tt0133093","tmdb":"603"}}},
            {"status":"plantowatch","movie":{"ids":{"simkl":2,"imdb":"tt2"}}}],
            "anime":[{"status":"completed","anime_type":"movie","show":{"ids":{"simkl":3,"mal":"199"}}}]}"""
        val projected = projectSimkl(snapshot(entries))
        assertEquals(setOf("tt0133093", "tmdb:603", "mal:199"), projected.watchedMovieIds)
        assertEquals(listOf("tt0133093", "mal:199"), projected.watchedItems.map { it.contentId })
        assertTrue(projected.watchedItems.all { it.season == null && it.contentType == "movie" })
        assertTrue("tt0133093" in projected.watchedKeys)
        assertTrue("tmdb:603" in projected.watchedMovieIds)
    }

    @Test
    fun `a dropped show's watched episodes stay watched`() {
        val entries = """{"shows":[{"status":"dropped","show":{"ids":{"simkl":1,"imdb":"tt1"}},"seasons":[{"number":1,"episodes":[{"number":1}]}]}]}"""
        assertEquals(1, projectSimkl(snapshot(entries)).watchedItems.size)
    }

    @Test
    fun `episode progress has watched episodes finished and a later playback on top`() {
        val entries = """{"shows":[{"status":"watching","show":{"ids":{"simkl":1,"imdb":"tt1"}},
            "seasons":[{"number":1,"episodes":[{"number":1,"watched_at":"2026-11-01T00:00:00Z"},{"number":2,"watched_at":"2026-11-01T00:00:00Z"}]}]}]}"""
        val episodes = projectSimkl(snapshot(entries, "[${showPlayback(1, "tt1", "2026-11-03T00:00:00Z")}]")).episodeProgress.getValue("tt1")
        assertEquals(100f, episodes.getValue(1 to 1).progressPercent)
        assertEquals(42.5f, episodes.getValue(1 to 2).progressPercent)
    }

    @Test
    fun `only ids Simkl can match leave local entries to Simkl`() {
        assertFalse(isSimklUnmatchable("tt1"))
        assertFalse(isSimklUnmatchable("kitsu:1:5"))
        assertTrue(isSimklUnmatchable("yt_id:abc"))
        assertTrue(isSimklUnmatchable("12345"))
    }
}
