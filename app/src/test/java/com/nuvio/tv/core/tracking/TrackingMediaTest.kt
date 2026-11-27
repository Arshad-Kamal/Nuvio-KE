package com.nuvio.tv.core.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingMediaTest {

    @Test
    fun `ids are read from Nuvio content and video ids`() {
        assertEquals(TrackingExternalIds(imdb = "tt0944947"), parseTrackingExternalIds("tt0944947:1:2"))
        assertEquals(TrackingExternalIds(tmdb = 603L), parseTrackingExternalIds("tmdb:603"))
        assertEquals(TrackingExternalIds(kitsu = 1376L), parseTrackingExternalIds("kitsu:1376:5"))
        assertEquals(TrackingExternalIds(mdblist = "abc"), parseTrackingExternalIds("mdblist:abc"))
        assertFalse(parseTrackingExternalIds("trakt:42").hasAny)
        assertFalse(parseTrackingExternalIds("12345").hasAny)
        assertFalse(parseTrackingExternalIds(null).hasAny)
    }

    @Test
    fun `an episode reference merges the show and video ids and keeps its coordinates`() {
        val ref = buildTrackingMediaReference(
            contentType = "series",
            parentMetaId = "tt0944947",
            videoId = "tt0944947:1:2",
            title = " Game of Thrones ",
            releaseInfo = "2011–2019",
            seasonNumber = 1,
            episodeNumber = 2
        )
        assertEquals(TrackingMediaKind.SHOW, ref.kind)
        assertEquals("Game of Thrones", ref.title)
        assertEquals(2011, ref.year)
        assertEquals(TrackingEpisode(season = 1, number = 2), ref.episode)
        assertEquals("show:imdb:tt0944947", ref.stableKey)
        assertEquals("tt0944947:1:2", ref.catalog?.videoId)
    }

    @Test
    fun `anime ids make an anime, whatever the catalog type`() {
        assertEquals(TrackingMediaKind.ANIME, trackingMediaKind("series", parseTrackingExternalIds("kitsu:1")))
        assertEquals(TrackingMediaKind.MOVIE, trackingMediaKind("Movie"))
    }

    @Test
    fun `only a film may be marked without episode coordinates`() {
        val movie = buildTrackingMediaReference("movie", "tt0133093")
        val show = buildTrackingMediaReference("series", "tt0944947")
        val anime = buildTrackingMediaReference("anime", "kitsu:1")
        assertFalse(movie.isWholeSeries)
        assertTrue(show.isWholeSeries)
        assertTrue(anime.isWholeSeries)
        assertFalse(show.copy(episode = TrackingEpisode(1, 1)).isWholeSeries)
    }

    @Test
    fun `a title-only reference keys on title and year`() {
        val ref = TrackingMediaReference(kind = TrackingMediaKind.MOVIE, title = "Big Buck Bunny", year = 2008)
        assertTrue(ref.hasResolvableIdentity)
        assertEquals("movie:title:big buck bunny:2008", ref.stableKey)
        assertNull(extractTrackingYear("no year"))
    }
}
