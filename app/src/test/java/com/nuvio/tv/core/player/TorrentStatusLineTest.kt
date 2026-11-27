package com.nuvio.tv.core.player

import com.nuvio.tv.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Spec 138 D6: the torrent status line before and after the first frame. */
class TorrentStatusLineTest {

    @Test
    fun `before the first frame the swarm line shows whatever the setting says`() {
        for (hidden in listOf(true, false)) {
            assertEquals(
                R.string.player_torrent_status,
                torrentStatusLineRes(renderedFirstFrame = false, statsHidden = hidden, statsKnown = true)
            )
        }
    }

    @Test
    fun `before the first stats poll the start keeps connecting to peers`() {
        for (hidden in listOf(true, false)) {
            assertEquals(
                R.string.player_torrent_connecting_peers,
                torrentStatusLineRes(renderedFirstFrame = false, statsHidden = hidden, statsKnown = false)
            )
        }
    }

    @Test
    fun `during playback the setting governs`() {
        for (known in listOf(true, false)) {
            assertNull(torrentStatusLineRes(renderedFirstFrame = true, statsHidden = true, statsKnown = known))
            assertEquals(
                R.string.player_torrent_status,
                torrentStatusLineRes(renderedFirstFrame = true, statsHidden = false, statsKnown = known)
            )
        }
    }
}
