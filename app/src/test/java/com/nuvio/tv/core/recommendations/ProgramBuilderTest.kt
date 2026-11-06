package com.nuvio.tv.core.recommendations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgramBuilderTest {

    @Test
    fun `watch next content id match does not delete prefix collisions`() {
        assertTrue(watchNextIdMatchesContentId("wn_tt1", "tt1"))
        assertTrue(watchNextIdMatchesContentId("wn_tt1_s2e3", "tt1"))

        assertFalse(watchNextIdMatchesContentId("wn_tt10", "tt1"))
        assertFalse(watchNextIdMatchesContentId("wn_tt10_s2e3", "tt1"))
        assertFalse(watchNextIdMatchesContentId("wn_tt1extra", "tt1"))
        assertFalse(watchNextIdMatchesContentId("wn_tt1_series", "tt1"))
        assertFalse(watchNextIdMatchesContentId(null, "tt1"))
    }

    @Test
    fun `watch next ids keep the persisted launcher format`() {
        assertEquals("wn_tt1_s2e3", watchNextId("tt1", season = 2, episode = 3))
        assertEquals("wn_tt1", watchNextId("tt1", season = null, episode = null))
        assertEquals("wn_tt1", watchNextId("tt1", season = 2, episode = null))
    }

    @Test
    fun `episode removal drops only that episode and the bare title row`() {
        assertTrue(watchNextIdMatchesRemoval("wn_tt1_s1e3", "tt1", season = 1, episode = 3))
        assertTrue(watchNextIdMatchesRemoval("wn_tt1", "tt1", season = 1, episode = 3))

        assertFalse(watchNextIdMatchesRemoval("wn_tt1_s2e5", "tt1", season = 1, episode = 3))
        assertFalse(watchNextIdMatchesRemoval("wn_tt1_s1e30", "tt1", season = 1, episode = 3))
        assertFalse(watchNextIdMatchesRemoval("wn_tt12_s1e3", "tt1", season = 1, episode = 3))
        assertFalse(watchNextIdMatchesRemoval("wn_tt12", "tt1", season = 1, episode = 3))
    }

    @Test
    fun `title removal drops every row of the title only`() {
        assertTrue(watchNextIdMatchesRemoval("wn_tt1", "tt1", season = null, episode = null))
        assertTrue(watchNextIdMatchesRemoval("wn_tt1_s1e3", "tt1", season = null, episode = null))
        assertTrue(watchNextIdMatchesRemoval("wn_tt1_s2e5", "tt1", season = null, episode = null))

        assertFalse(watchNextIdMatchesRemoval("wn_tt12_s1e3", "tt1", season = null, episode = null))
        assertFalse(watchNextIdMatchesRemoval("wn_tt12", "tt1", season = null, episode = null))
    }
}
