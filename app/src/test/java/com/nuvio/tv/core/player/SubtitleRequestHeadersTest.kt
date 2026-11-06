package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the 0.9.0 subtitle 403-header policy (T10 item 4): domain-scoped stream-header
 * forwarding, explicit per-subtitle header override, hop-by-hop exclusion, and
 * User-Agent/Accept defaults.
 */
class SubtitleRequestHeadersTest {

    private val defaultUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) test"
    private val defaultAccept = "text/plain, text/vtt, application/x-subrip, */*"

    private val streamHeaders = mapOf(
        "Authorization" to "Bearer debrid-token",
        "Cookie" to "session=1",
        "Proxy-Authorization" to "Basic x",
        "Referer" to "https://cdn.example.com/",
        "Origin" to "https://cdn.example.com",
        "User-Agent" to "NuvioPlayer/1.0",
        "Range" to "bytes=0-100",
        "Host" to "cdn.example.com",
        "Connection" to "keep-alive",
        "Transfer-Encoding" to "chunked"
    )

    private fun headerValue(headers: Map<String, String>, name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    @Test
    fun sameHost_forwardsAuthCookiesAndReferer_dropsHopByHop() {
        val headers = buildSubtitleRequestHeaders(
            streamHeaders, "cdn.example.com", "cdn.example.com",
            explicitHeaders = null, defaultUserAgent = defaultUa, defaultAccept = defaultAccept
        )
        assertEquals("Bearer debrid-token", headerValue(headers, "Authorization"))
        assertEquals("session=1", headerValue(headers, "Cookie"))
        assertEquals("Basic x", headerValue(headers, "Proxy-Authorization"))
        assertEquals("https://cdn.example.com/", headerValue(headers, "Referer"))
        assertEquals("NuvioPlayer/1.0", headerValue(headers, "User-Agent"))
        assertNull(headerValue(headers, "Range"))
        assertNull(headerValue(headers, "Host"))
        assertNull(headerValue(headers, "Connection"))
        assertNull(headerValue(headers, "Transfer-Encoding"))
    }

    @Test
    fun subdomain_countsAsSameDomain() {
        val headers = buildSubtitleRequestHeaders(
            streamHeaders, "sub.cdn.example.com", "cdn.example.com",
            explicitHeaders = null, defaultUserAgent = defaultUa, defaultAccept = defaultAccept
        )
        assertEquals("Bearer debrid-token", headerValue(headers, "Authorization"))
    }

    @Test
    fun crossDomain_dropsAuth_keepsRefererOriginAndUa() {
        val headers = buildSubtitleRequestHeaders(
            streamHeaders, "dl.opensubtitles.org", "cdn.example.com",
            explicitHeaders = null, defaultUserAgent = defaultUa, defaultAccept = defaultAccept
        )
        assertNull(headerValue(headers, "Authorization"))
        assertNull(headerValue(headers, "Cookie"))
        assertNull(headerValue(headers, "Proxy-Authorization"))
        assertEquals("https://cdn.example.com/", headerValue(headers, "Referer"))
        assertEquals("https://cdn.example.com", headerValue(headers, "Origin"))
        assertEquals("NuvioPlayer/1.0", headerValue(headers, "User-Agent"))
    }

    @Test
    fun explicitHeaders_overrideStreamHeaders_andHopByHopStillFiltered() {
        val headers = buildSubtitleRequestHeaders(
            streamHeaders, "cdn.example.com", "cdn.example.com",
            explicitHeaders = mapOf(
                "Authorization" to "addon-token",
                "X-Addon" to "1",
                "Range" to "bytes=0-50"
            ),
            defaultUserAgent = defaultUa, defaultAccept = defaultAccept
        )
        assertEquals("addon-token", headerValue(headers, "Authorization"))
        assertEquals("1", headerValue(headers, "X-Addon"))
        assertNull(headerValue(headers, "Range"))
    }

    @Test
    fun userAgent_defaultsOnlyWhenAbsent() {
        val noUa = streamHeaders - "User-Agent"
        val withDefault = buildSubtitleRequestHeaders(
            noUa, "cdn.example.com", "cdn.example.com",
            explicitHeaders = null, defaultUserAgent = defaultUa, defaultAccept = defaultAccept
        )
        assertEquals(defaultUa, headerValue(withDefault, "User-Agent"))

        val withStreamUa = buildSubtitleRequestHeaders(
            streamHeaders, "cdn.example.com", "cdn.example.com",
            explicitHeaders = null, defaultUserAgent = defaultUa, defaultAccept = defaultAccept
        )
        assertEquals("NuvioPlayer/1.0", headerValue(withStreamUa, "User-Agent"))
    }

    @Test
    fun accept_defaultAddedOnlyWhenStreamHasNone() {
        assertEquals(defaultAccept, headerValue(buildSubtitleRequestHeaders(
            streamHeaders, "cdn.example.com", "cdn.example.com",
            null, defaultUa, defaultAccept
        ), "Accept"))
        // Stream already carries an Accept → preserved untouched.
        val withAccept = buildSubtitleRequestHeaders(
            streamHeaders + ("Accept" to "*/*"), "cdn.example.com", "cdn.example.com",
            null, defaultUa, defaultAccept
        )
        assertEquals("*/*", headerValue(withAccept, "Accept"))
    }

    @Test
    fun explicitHeaders_overrideStreamHeaders_caseInsensitively() {
        val headers = buildSubtitleRequestHeaders(
            streamHeaders, "cdn.example.com", "cdn.example.com",
            explicitHeaders = mapOf("user-agent" to "AddonUA/2.0"),
            defaultUserAgent = defaultUa, defaultAccept = defaultAccept
        )
        assertEquals("AddonUA/2.0", headerValue(headers, "User-Agent"))
        assertEquals(1, headers.keys.count { it.equals("user-agent", ignoreCase = true) })
    }

    @Test
    fun isSameOrSubdomain_edges() {
        assertTrue(isSameOrSubdomain("a.example.com", "example.com"))
        assertTrue(isSameOrSubdomain("example.com", "EXAMPLE.com"))
        assertFalse(isSameOrSubdomain(null, "example.com"))
        assertFalse(isSameOrSubdomain("", "example.com"))
        assertFalse(isSameOrSubdomain("example.org", "example.com"))
        assertFalse(isSameOrSubdomain("com", "example.com"))
        // Naive 2-part-root heuristic (upstream 0.9.0): multi-part TLDs over-match. Pinned as
        // documented behaviour, not desired behaviour.
        assertTrue(isSameOrSubdomain("a.co.uk", "b.co.uk"))
    }
}
