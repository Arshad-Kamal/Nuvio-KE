package com.nuvio.tv.core.player

import com.nuvio.tv.domain.model.ProxyHeaders
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 131 D9/D10: the placeholder Retry's fallback stream and the late track-AFR save gate. */
class PlaceholderRetryTest {

    @Test
    fun `a late track-AFR save is skipped after a placeholder reject or once a new stream started`() {
        assertTrue(trackAfrMayCache(4, 4, placeholderRejected = false, sameStreamUrl = true))
        // Rejected while the coroutine suspended, the box shown (no switch yet).
        assertFalse(trackAfrMayCache(4, 4, placeholderRejected = true, sameStreamUrl = true))
        // The failover's switch (or Retry's) ran first: the new stream's init bumped the generation and
        // cleared the reject flag before the clip's coroutine resumed.
        assertFalse(trackAfrMayCache(4, 5, placeholderRejected = false, sameStreamUrl = true))
        // A torrent failover: the URL changed and the flag is cleared at once, its init (and the
        // generation bump) only once TorrServer answers.
        assertFalse(trackAfrMayCache(4, 4, placeholderRejected = false, sameStreamUrl = false))
    }

    @Test
    fun `a hand-picked source never fails over, an automatic one does under the cap (Spec 131 D5)`() {
        assertTrue(sourceFailoverAllowed(handPicked = false, failoverCount = 0))
        assertTrue(sourceFailoverAllowed(handPicked = false, failoverCount = 2))
        assertFalse(sourceFailoverAllowed(handPicked = false, failoverCount = 3))
        assertFalse(sourceFailoverAllowed(handPicked = true, failoverCount = 0))
    }

    private fun launchStream(hints: StreamBehaviorHints?) = Stream(
        name = "4K REMUX", title = null, description = null, url = "https://debrid.example/resolve/abc",
        ytId = null, infoHash = null, fileIdx = null, externalUrl = null, behaviorHints = hints,
        addonName = "AIOStreams", addonLogo = null
    )

    @Test
    fun `a route launch's stream gets the request headers the player kept`() {
        val headers = mapOf("Authorization" to "Bearer t")
        val stream = launchStream(
            StreamBehaviorHints(null, null, null, null, filename = "Film.2160p.mkv")
        ).withRequestHeaders(headers)
        assertEquals(headers, stream.behaviorHints?.proxyHeaders?.request)
        assertEquals("Film.2160p.mkv", stream.behaviorHints?.filename)
        assertEquals(headers, launchStream(null).withRequestHeaders(headers).behaviorHints?.proxyHeaders?.request)
    }

    @Test
    fun `a stream that carries its own request headers, or no headers to add, is unchanged`() {
        val own = launchStream(
            StreamBehaviorHints(null, null, null, ProxyHeaders(request = mapOf("X-Own" to "1"), response = null))
        )
        assertSame(own, own.withRequestHeaders(mapOf("Authorization" to "Bearer t")))
        val plain = launchStream(null)
        assertSame(plain, plain.withRequestHeaders(emptyMap()))
    }
}
