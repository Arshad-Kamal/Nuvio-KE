package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pins the one probe-request shape shared by the AFR probes and the OpenSubtitles hasher. */
class ProbeRequestsTest {

    @Test
    fun forwardsHeadersExceptRangeAndBlankValues() {
        val request = probeRequestBuilder(
            "https://cdn.example/v.mkv",
            mapOf("Referer" to "https://addon.example", "Range" to "bytes=5-", "Cookie" to " ")
        ).build()

        assertEquals("https://addon.example", request.header("Referer"))
        assertNull(request.header("Range"))
        // The hasher used to forward blank values; every probe now drops them.
        assertNull(request.header("Cookie"))
    }

    @Test
    fun addsTheDefaultUserAgentOnlyWhenNoneIsSet() {
        assertEquals(
            PlayerMediaSourceFactory.DEFAULT_USER_AGENT,
            probeRequestBuilder("https://cdn.example/v.mkv", emptyMap()).build().header("User-Agent")
        )
        assertEquals(
            "Addon/1",
            probeRequestBuilder("https://cdn.example/v.mkv", mapOf("user-agent" to "Addon/1"))
                .build().header("User-Agent")
        )
    }

    @Test
    fun dropsRangeWhateverItsCase() {
        // The HEAD probes never set their own Range, so a forwarded one would survive.
        val request = probeRequestBuilder("https://cdn.example/v.mkv", mapOf("range" to "bytes=5-")).build()

        assertNull(request.header("Range"))
    }

    @Test
    fun blankUserAgentGetsTheDefault() {
        val request = probeRequestBuilder("https://cdn.example/v.mkv", mapOf("User-Agent" to " ")).build()

        assertEquals(PlayerMediaSourceFactory.DEFAULT_USER_AGENT, request.header("User-Agent"))
    }
}
