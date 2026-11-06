package com.nuvio.tv.core.player

import com.nuvio.tv.domain.model.Stream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class P2pPermittedTest {

    private fun stream(url: String? = null, infoHash: String? = null) = Stream(
        name = "s",
        title = null,
        description = null,
        url = url,
        ytId = null,
        infoHash = infoHash,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = null,
        addonName = "addon",
        addonLogo = null
    )

    private val hash = "a".repeat(40)

    @Test
    fun `torrent-only row is excluded with P2P off`() {
        assertFalse(isP2pPermitted(stream(infoHash = hash), p2pEnabled = false))
    }

    @Test
    fun `torrent-only row is allowed with P2P on`() {
        assertTrue(isP2pPermitted(stream(infoHash = hash), p2pEnabled = true))
    }

    @Test
    fun `resolved url with infoHash stays eligible with P2P off`() {
        // Debrid-resolved rows carry both; the HTTP url plays without TorrServer.
        assertTrue(isP2pPermitted(stream(url = "https://cdn.example/f.mkv", infoHash = hash), p2pEnabled = false))
    }

    @Test
    fun `magnet url is a torrent and excluded with P2P off`() {
        // getStreamUrl skips magnet links, so isTorrent() is true for a magnet-only row.
        val magnet = stream(url = "magnet:?xt=urn:btih:$hash")
        assertTrue(magnet.isTorrent())
        assertFalse(isP2pPermitted(magnet, p2pEnabled = false))
    }

    @Test
    fun `plain http row is always eligible`() {
        assertTrue(isP2pPermitted(stream(url = "https://cdn.example/f.mkv"), p2pEnabled = false))
    }
}
