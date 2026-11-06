package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** U6l Episodes panel: the row it opens on (as built: last opened, else playing, else first). */
class EpisodesPanelTest {

    @Test
    fun `the episode whose sources were last opened wins`() {
        assertEquals(4, episodesPanelInitialIndex(lastOpenedIndex = 4, playingIndex = 2))
    }

    @Test
    fun `else the playing episode`() {
        assertEquals(2, episodesPanelInitialIndex(lastOpenedIndex = -1, playingIndex = 2))
    }

    @Test
    fun `else the first row`() {
        assertEquals(0, episodesPanelInitialIndex(lastOpenedIndex = -1, playingIndex = -1))
    }
}
