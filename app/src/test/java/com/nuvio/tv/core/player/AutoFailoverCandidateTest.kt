package com.nuvio.tv.core.player

import com.nuvio.tv.domain.model.Stream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Register A2 P13: the dead-source failover only switches to rows the player can open. */
class AutoFailoverCandidateTest {

    private fun stream(
        url: String? = null,
        externalUrl: String? = null,
        infoHash: String? = null,
        ytId: String? = null
    ) = Stream(
        name = "s",
        title = null,
        description = null,
        url = url,
        ytId = ytId,
        infoHash = infoHash,
        fileIdx = null,
        externalUrl = externalUrl,
        behaviorHints = null,
        addonName = "addon",
        addonLogo = null
    )

    private val hash = "a".repeat(40)

    @Test
    fun `http and https links qualify`() {
        assertTrue(isAutoFailoverCandidate(stream(url = "https://cdn.example/f.mkv"), p2pEnabled = false))
        assertTrue(isAutoFailoverCandidate(stream(url = " HTTP://10.0.0.2:8080/f.mkv"), p2pEnabled = false))
    }

    @Test
    fun `external link is skipped`() {
        assertFalse(isAutoFailoverCandidate(stream(externalUrl = "https://addon.example/watch"), p2pEnabled = true))
    }

    @Test
    fun `non-http or missing link is skipped`() {
        assertFalse(isAutoFailoverCandidate(stream(url = "stremio:///detail/movie/tt1"), p2pEnabled = true))
        assertFalse(isAutoFailoverCandidate(stream(ytId = "dQw4w9WgXcQ"), p2pEnabled = true))
    }

    @Test
    fun `torrent rows follow P2P consent`() {
        assertTrue(isAutoFailoverCandidate(stream(infoHash = hash), p2pEnabled = true))
        assertFalse(isAutoFailoverCandidate(stream(infoHash = hash), p2pEnabled = false))
        assertTrue(isAutoFailoverCandidate(stream(url = "magnet:?xt=urn:btih:$hash"), p2pEnabled = true))
    }

    @Test
    fun `torrent row without an info hash is skipped`() {
        assertFalse(isAutoFailoverCandidate(stream(url = "magnet:?dn=no-hash"), p2pEnabled = true))
    }

    @Test
    fun `resolved url with infoHash plays over http with P2P off`() {
        assertTrue(isAutoFailoverCandidate(stream(url = "https://cdn.example/f.mkv", infoHash = hash), p2pEnabled = false))
    }
}
