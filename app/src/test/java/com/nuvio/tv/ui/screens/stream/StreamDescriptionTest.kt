package com.nuvio.tv.ui.screens.stream

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** U5g: the plot under the Stream screen title (episode overview, else the title's description). */
class StreamDescriptionTest {

    private fun video(season: Int, episode: Int, overview: String?) = Video(
        id = "tt0000001:$season:$episode",
        title = "Episode $episode",
        released = null,
        thumbnail = null,
        season = season,
        episode = episode,
        overview = overview
    )

    private fun meta(description: String?, videos: List<Video> = emptyList()) = Meta(
        id = "tt0000001",
        type = ContentType.SERIES,
        name = "Example Show",
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = description,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList(),
        runtime = null,
        director = emptyList(),
        cast = emptyList(),
        videos = videos,
        country = null,
        awards = null,
        language = null,
        links = emptyList()
    )

    @Test
    fun `episode reads its own overview`() {
        val m = meta("Show plot", listOf(video(1, 1, "Pilot plot"), video(1, 2, "Second plot")))
        assertEquals("Second plot", streamDescription(m, season = 1, episode = 2))
    }

    @Test
    fun `episode without an overview falls back to the show description`() {
        val m = meta("Show plot", listOf(video(1, 1, " ")))
        assertEquals("Show plot", streamDescription(m, season = 1, episode = 1))
        assertEquals("Show plot", streamDescription(m, season = 2, episode = 5))
    }

    @Test
    fun `movie reads the description and blanks read as none`() {
        assertEquals("Movie plot", streamDescription(meta("Movie plot"), season = null, episode = null))
        assertNull(streamDescription(meta(" "), season = null, episode = null))
        assertNull(streamDescription(meta(null, listOf(video(1, 1, null))), season = 1, episode = 1))
    }

    // Spec 51 P5: the runtime the Stream screen and the Details prefetch both count.
    @Test
    fun `stream runtime is the episode's for an episode, else the title's`() {
        val m = meta("Show plot", listOf(video(1, 1, null).copy(runtime = 47))).copy(runtime = "120 min")
        assertEquals(47, streamRuntimeMinutes(m, season = 1, episode = 1))
        assertNull("an episode the meta lacks has no runtime, not the title's", streamRuntimeMinutes(m, season = 1, episode = 2))
        assertNull(streamRuntimeMinutes(m.copy(videos = listOf(video(1, 1, null))), season = 1, episode = 1))
        assertEquals(120, streamRuntimeMinutes(m, season = null, episode = null))
        assertNull(streamRuntimeMinutes(m.copy(runtime = "0 min"), season = null, episode = null))
        assertNull(streamRuntimeMinutes(m.copy(runtime = null), season = null, episode = null))
    }
}
