package com.nuvio.tv.core.player

import com.nuvio.tv.core.torrent.TorrentService
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

/** B22: TorrServer is shut down when a player session that used a torrent is released. */
class TorrentSessionShutdownTest {

    private val torrentService = mockk<TorrentService>(relaxed = true)

    @Test
    fun `release after a torrent session shuts TorrServer down`() {
        shutdownTorrServerAfterSession(session = 3L, torrentService = torrentService)

        verify(exactly = 1) { torrentService.shutdown(3L) }
    }

    @Test
    fun `release after a non-torrent session leaves TorrentService alone`() {
        shutdownTorrServerAfterSession(session = null, torrentService = torrentService)

        verify(exactly = 0) { torrentService.shutdown(any()) }
    }
}
