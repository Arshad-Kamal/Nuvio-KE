package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.data.local.InternalPlayerEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSectionsTest {

    @Test
    fun `Buffer and network shows for ExoPlayer and Auto, last`() {
        listOf(InternalPlayerEngine.EXOPLAYER, InternalPlayerEngine.AUTO).forEach { engine ->
            assertEquals(PlaybackSection.entries, visiblePlaybackSections(engine))
        }
    }

    @Test
    fun `libmpv hides Buffer and network and keeps the rest in order`() {
        assertEquals(
            listOf(
                PlaybackSection.GENERAL,
                PlaybackSection.STREAMS,
                PlaybackSection.AUDIO_VIDEO,
                PlaybackSection.SUBTITLES,
                PlaybackSection.P2P
            ),
            visiblePlaybackSections(InternalPlayerEngine.MVP_PLAYER)
        )
    }

    @Test
    fun `a hidden selected chip shows General, a visible one stays`() {
        val libmpv = visiblePlaybackSections(InternalPlayerEngine.MVP_PLAYER)
        assertEquals(PlaybackSection.GENERAL, shownPlaybackSection(PlaybackSection.BUFFER_NETWORK, libmpv))
        assertEquals(PlaybackSection.SUBTITLES, shownPlaybackSection(PlaybackSection.SUBTITLES, libmpv))
    }
}
