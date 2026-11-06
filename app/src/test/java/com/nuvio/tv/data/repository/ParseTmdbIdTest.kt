package com.nuvio.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pins parseTmdbId, the poster-options canonicalisation input (L8-05/27/51). */
class ParseTmdbIdTest {

    @Test
    fun `tmdb prefix and bare numbers parse`() {
        assertEquals(550, parseTmdbId("tmdb:550"))
        assertEquals(550, parseTmdbId("TMDB:550"))
        assertEquals(550, parseTmdbId("550"))
    }

    @Test
    fun `other ids do not parse`() {
        assertNull(parseTmdbId("tt0137523"))
        assertNull(parseTmdbId("trakt:550"))
        assertNull(parseTmdbId("tmdb:550:1:2"))
        assertNull(parseTmdbId("550:1:2"))
        assertNull(parseTmdbId("tmdb:abc"))
    }
}
