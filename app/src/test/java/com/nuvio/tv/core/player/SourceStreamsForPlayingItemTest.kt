package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T22 (upstream-sync Register A2 P19): the in-player source list is only this item's list
 * when it was loaded for the playing item's request key. Episode switches keep the
 * previous episode's list in the state, and the dead-source failover took it as current.
 */
class SourceStreamsForPlayingItemTest {

    @Test
    fun `series key is episode scoped and prefers the video id`() {
        assertEquals("series|tt1:2:3|2|3", playingItemSourceRequest("series", "tt1", "tt1:2:3", 2, 3)?.key)
        assertEquals("series|tt1|2|3", playingItemSourceRequest("series", "tt1", null, 2, 3)?.key)
    }

    @Test
    fun `tv episodes are episode scoped too`() {
        assertEquals("tv|tt1:1:2|1|2", playingItemSourceRequest("tv", "tt1", "tt1:1:2", 1, 2)?.key)
    }

    @Test
    fun `next episode gets a different key`() {
        val e3 = playingItemSourceRequest("series", "tt1", "tt1:2:3", 2, 3)?.key
        val e4 = playingItemSourceRequest("series", "tt1", "tt1:2:4", 2, 4)?.key
        assertTrue(e3 != e4)
    }

    @Test
    fun `movie and series without an episode use the content id, not the video id`() {
        assertEquals("movie|tt9|-1|-1", playingItemSourceRequest("movie", "tt9", "tt9:x", null, null)?.key)
        assertEquals("movie|tt9|-1|-1", playingItemSourceRequest(null, "tt9", null, null, null)?.key)
        assertEquals("series|tt1|-1|-1", playingItemSourceRequest("series", "tt1", "tt1:2:3", null, 3)?.key)
    }

    @Test
    fun `unknown identity has no request`() {
        assertNull(playingItemSourceRequest("movie", null, null, null, null))
        assertNull(playingItemSourceRequest("series", null, null, 1, 1))
    }

    @Test
    fun `list loaded for the playing item is current`() {
        assertTrue(sourceListLoadedFor("series|tt1:2:3|2|3", "series|tt1:2:3|2|3"))
    }

    @Test
    fun `previous episode's list is not the playing item's`() {
        assertFalse(sourceListLoadedFor("series|tt1:2:3|2|3", "series|tt1:2:4|2|4"))
    }

    @Test
    fun `nothing loaded or unknown identity is not current`() {
        assertFalse(sourceListLoadedFor(null, "movie|tt9|-1|-1"))
        assertFalse(sourceListLoadedFor(null, null))
    }
}
