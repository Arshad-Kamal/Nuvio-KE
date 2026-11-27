package com.nuvio.tv.data.mdblist

import com.nuvio.tv.core.tracking.TrackingProviderId
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 105 S2a: what Continue Watching, Up next and the watched marks read from an MDBList snapshot. */
class MdbListProjectionTest {

    private fun snapshot(marks: List<MdbListWatchedMark> = emptyList(), playback: String = "[]") = MdbListSnapshot(
        marks = marks,
        playback = mdbListJson.decodeFromString(ListSerializer(MdbListPlayback.serializer()), playback)
    )

    private fun media(ids: String, title: String = "Show") =
        mdbListJson.decodeFromString(MdbListMedia.serializer(), """{"title":"$title","ids":$ids}""")

    private val show = media("""{"imdb":"tt1","tmdb":11,"mdblist":"s1"}""")

    private fun episodePlayback(pausedAt: String, season: Int = 1, episode: Int = 2, progress: Double = 42.5, ids: String = """{"imdb":"tt1","tmdb":11}""") =
        """{"id":$season$episode,"progress":$progress,"paused_at":"$pausedAt","type":"episode","show":{"title":"Show","ids":$ids},
            "episode":{"season":$season,"number":$episode,"name":"Pilot"}}"""

    @Test
    fun `a playback becomes a resume point under the title's IMDb id`() {
        val progress = projectMdbList(snapshot(playback = "[${episodePlayback("2026-11-01T10:00:00Z")}]")).progress.single()
        assertEquals("tt1", progress.contentId)
        assertEquals("series", progress.contentType)
        assertEquals(1 to 2, progress.season to progress.episode)
        assertEquals("tt1:1:2", progress.videoId)
        assertEquals("Pilot", progress.episodeTitle)
        assertEquals(42.5f, progress.progressPercent)
        assertEquals(12L, progress.mdbListPlaybackId)
        assertEquals(TrackingProviderId.MDBLIST.storageId, progress.trackingProviderId)
        assertEquals(parseMdbListTime("2026-11-01T10:00:00Z"), progress.lastWatched)
    }

    @Test
    fun `a film playback, the TMDB fallback and a title with neither id`() {
        val playback = """[{"id":1,"progress":10,"updated_at":"2026-11-01T10:00:00Z","type":"movie","movie":{"title":"Inception","ids":{"tmdb":"27205","imdb":null}}},
            ${episodePlayback("2026-11-01T09:00:00Z", ids = """{"tvdb":81189}""")}]"""
        val progress = projectMdbList(snapshot(playback = playback)).progress.single()
        assertEquals("tmdb:27205", progress.contentId)
        assertEquals("movie", progress.contentType)
        assertEquals("tmdb:27205", progress.videoId)
        assertEquals(parseMdbListTime("2026-11-01T10:00:00Z"), progress.lastWatched)
    }

    @Test
    fun `a pause at MDBList's watched mark is not a resume point`() {
        val playback = "[${episodePlayback("2026-11-01T10:00:00Z", progress = 80.0)}]"
        assertTrue(projectMdbList(snapshot(playback = playback)).progress.isEmpty())
    }

    @Test
    fun `a pause before the episode's watch is hidden, a later one is a rewatch and shows`() {
        val watched = MdbListWatchedMark(show, 1, 2, "2026-11-01T12:00:00Z")
        val before = "[${episodePlayback("2026-11-01T10:00:00Z")}]"
        assertTrue(projectMdbList(snapshot(listOf(watched), before)).progress.isEmpty())
        val after = "[${episodePlayback("2026-11-01T13:00:00Z")}]"
        val projected = projectMdbList(snapshot(listOf(watched), after))
        assertEquals(listOf("tt1"), projected.progress.map { it.contentId })
        assertEquals(42.5f, projected.episodeProgress.getValue("tt1").getValue(1 to 2).progressPercent)
    }

    @Test
    fun `one resume point per title, the newest`() {
        val playback = "[${episodePlayback("2026-11-01T10:00:00Z", episode = 2)}, ${episodePlayback("2026-11-02T10:00:00Z", episode = 5)}]"
        val projected = projectMdbList(snapshot(playback = playback))
        assertEquals(listOf(5), projected.progress.map { it.episode })
        assertEquals(setOf(1 to 2, 1 to 5), projected.episodeProgress.getValue("tt1").keys)
    }

    @Test
    fun `watched films carry every id form, episodes are marks under the show`() {
        val film = MdbListWatchedMark(media("""{"imdb":"tt0133093","tmdb":603}""", "Matrix"), watchedAt = "2026-11-01T10:00:00Z")
        val projected = projectMdbList(snapshot(listOf(film, MdbListWatchedMark(show, 1, 1, "2026-11-01T11:00:00Z"))))
        assertEquals(setOf("tt0133093", "tmdb:603"), projected.watchedMovieIds)
        assertEquals(listOf("tt0133093" to null, "tt1" to 1), projected.watchedItems.map { it.contentId to it.episode })
        assertTrue("tt1_s1e1" in projected.watchedKeys)
        assertEquals("s1", projected.watchedItems[1].trackingProviderItemId)
    }

    @Test
    fun `Up next takes each show's latest regular episode, never a special`() {
        val marks = listOf(
            MdbListWatchedMark(show, 1, 1, "2026-11-01T10:00:00Z"),
            MdbListWatchedMark(show, 1, 2, "2026-11-02T10:00:00Z"),
            MdbListWatchedMark(show, 0, 1, "2026-11-03T10:00:00Z"),
            MdbListWatchedMark(media("""{"tmdb":22}""", "Other"), 2, 4, "2026-11-01T09:00:00Z")
        )
        val seeds = projectMdbList(snapshot(marks)).nextUpSeeds
        assertEquals(listOf("tt1" to (1 to 2), "tmdb:22" to (2 to 4)), seeds.map { it.contentId to (it.season!! to it.episode!!) })
        assertEquals(100f, seeds.first().progressPercent)
    }

    @Test
    fun `only titles with neither an IMDb nor a TMDB id stay local`() {
        assertFalse(isMdbListUnmatchable("tt0133093"))
        assertFalse(isMdbListUnmatchable("tmdb:603"))
        assertTrue(isMdbListUnmatchable("kitsu:7"))
        assertTrue(isMdbListUnmatchable("custom-addon-id"))
    }
}
