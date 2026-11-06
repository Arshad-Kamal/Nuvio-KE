package com.nuvio.tv.ui.screens.detail

import org.junit.Assert.assertEquals
import org.junit.Test

/** P48: a rebuilt episode row starts at the season's remembered card so Down can reach it. */
class EpisodeRowInitialIndexTest {

    private val ids = (1..12).map { "ep$it" }

    @Test
    fun `no target starts at the first card`() {
        assertEquals(0, episodeRowInitialIndex(ids, null, null, null))
    }

    @Test
    fun `the remembered card lands one card in`() {
        assertEquals(8, episodeRowInitialIndex(ids, null, null, "ep10"))
        assertEquals(0, episodeRowInitialIndex(ids, null, null, "ep1"))
    }

    @Test
    fun `restore and scroll targets win over the anchor`() {
        assertEquals(4, episodeRowInitialIndex(ids, "ep6", null, "ep10"))
        assertEquals(5, episodeRowInitialIndex(ids, null, "ep6", "ep10"))
        assertEquals(4, episodeRowInitialIndex(ids, "ep6", "ep9", null))
        assertEquals(5, episodeRowInitialIndex(ids, null, "ep6", null))
    }

    @Test
    fun `an id not in the season starts at the first card, never falling through to the anchor`() {
        assertEquals(0, episodeRowInitialIndex(ids, "other", null, "ep10"))
        assertEquals(0, episodeRowInitialIndex(ids, null, "other", "ep10"))
        assertEquals(0, episodeRowInitialIndex(ids, null, null, "other"))
        assertEquals(0, episodeRowInitialIndex(emptyList(), null, null, "ep3"))
    }
}
