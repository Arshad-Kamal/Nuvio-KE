package com.nuvio.tv.core.server

import fi.iki.elonen.NanoHTTPD.IHTTPSession
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the DNS-rebinding Host allow-list ([isAllowedConfigHost]) and the POST
 * same-origin check ([isSameOriginOrNonBrowserPost]) of the LAN config servers.
 */
class ConfigServerGuardHostTest {

    private val own = setOf("192.168.1.50", "127.0.0.1")

    @Test
    fun `own address with or without port is allowed`() {
        assertTrue(isAllowedConfigHost("192.168.1.50:8080", own))
        assertTrue(isAllowedConfigHost("192.168.1.50", own))
    }

    @Test
    fun `loopback names are allowed even when enumeration is empty`() {
        assertTrue(isAllowedConfigHost("127.0.0.1:8091", emptySet()))
        assertTrue(isAllowedConfigHost("LocalHost:8091", emptySet()))
    }

    @Test
    fun `rebinding hostname is rejected`() {
        assertFalse(isAllowedConfigHost("attacker.example:8080", own))
        assertFalse(isAllowedConfigHost("192.168.1.50.attacker.example", own))
    }

    @Test
    fun `missing or blank host is rejected`() {
        assertFalse(isAllowedConfigHost(null, own))
        assertFalse(isAllowedConfigHost("  ", own))
        assertFalse(isAllowedConfigHost(":8080", own))
    }

    @Test
    fun `other lan address is rejected`() {
        assertFalse(isAllowedConfigHost("192.168.1.51:8080", own))
    }

    private fun session(vararg headers: Pair<String, String>): IHTTPSession =
        mockk { every { this@mockk.headers } returns mapOf(*headers) }

    @Test
    fun `post without origin or referer passes as a non-browser client`() {
        assertTrue(session().isSameOriginOrNonBrowserPost(own))
    }

    @Test
    fun `post from the page served at the device ip passes`() {
        assertTrue(session("origin" to "http://192.168.1.50:8080").isSameOriginOrNonBrowserPost(own))
        assertTrue(session("referer" to "http://192.168.1.50:8080/").isSameOriginOrNonBrowserPost(own))
    }

    @Test
    fun `post whose origin equals a foreign host header is rejected`() {
        // The removed Origin==Host shortcut accepted exactly this rebinding shape.
        val rebinding = session("origin" to "http://attacker.example:8080", "host" to "attacker.example:8080")
        assertFalse(rebinding.isSameOriginOrNonBrowserPost(own))
    }

    @Test
    fun `post with malformed origin is rejected`() {
        assertFalse(session("origin" to "http://exa mple").isSameOriginOrNonBrowserPost(own))
        assertFalse(session("origin" to "null").isSameOriginOrNonBrowserPost(own))
    }
}
