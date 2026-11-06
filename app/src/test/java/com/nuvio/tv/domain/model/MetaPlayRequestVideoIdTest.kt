package com.nuvio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MetaPlayRequestVideoIdTest {

    @Test
    fun `defaultVideoId is the stream request id when present`() {
        val meta = meta(id = "yt_channel:abc", defaultVideoId = "yt_id:xyz")

        assertEquals("yt_id:xyz", meta.playRequestVideoId())
    }

    @Test
    fun `falls back to meta id without behaviorHints`() {
        assertEquals("tt0111161", meta(id = "tt0111161", defaultVideoId = null).playRequestVideoId())
    }

    @Test
    fun `blank defaultVideoId falls back to meta id`() {
        assertEquals("tt0111161", meta(id = "tt0111161", defaultVideoId = "  ").playRequestVideoId())
    }

    private fun meta(id: String, defaultVideoId: String?) = Meta(
        id = id,
        type = ContentType.MOVIE,
        name = "Meta Name",
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList(),
        runtime = null,
        director = emptyList(),
        cast = emptyList(),
        videos = emptyList(),
        links = emptyList(),
        country = null,
        awards = null,
        language = null,
        behaviorHints = defaultVideoId?.let { MetaBehaviorHints(defaultVideoId = it) }
    )
}
