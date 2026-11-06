package com.nuvio.tv.data.repository

import com.nuvio.tv.core.util.canonicalizeAddonUrl
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins splitAddonBaseUrl: catalog URLs built from it are disk-cache keys, so the
 * path shape stays that of the six copies it replaced (L8-09/49), and the query
 * passes through byte-exact (L8-19).
 */
class AddonBaseUrlTest {

    @Test
    fun `plain and slash-terminated urls split to the same path`() {
        assertEquals("https://a.example" to "", splitAddonBaseUrl("https://a.example"))
        assertEquals("https://a.example" to "", splitAddonBaseUrl("https://a.example//"))
        assertEquals("https://a.example/cfg" to "", splitAddonBaseUrl("https://a.example/cfg/"))
    }

    @Test
    fun `query is kept apart from the path`() {
        assertEquals("https://a.example/cfg" to "?token=abc", splitAddonBaseUrl("https://a.example/cfg?token=abc"))
        assertEquals("https://a.example/cfg" to "?token=abc", splitAddonBaseUrl("https://a.example/cfg/?token=abc"))
    }

    @Test
    fun `a slash ending the query is kept (L8-19)`() {
        assertEquals("https://a.example" to "?config=x89+/", splitAddonBaseUrl("https://a.example?config=x89+/"))
        assertEquals("https://a.example" to "?config=x89+//", splitAddonBaseUrl("https://a.example/?config=x89+//"))
        assertEquals("https://a.example" to "?/", splitAddonBaseUrl("https://a.example/?/"))
    }

    @Test
    fun `a slash or a second question mark inside the query does not split it`() {
        assertEquals("https://a.example/cfg" to "?u=a/b/", splitAddonBaseUrl("https://a.example/cfg/?u=a/b/"))
        assertEquals("https://a.example/cfg" to "?a=1?b=/", splitAddonBaseUrl("https://a.example/cfg/?a=1?b=/"))
    }

    @Test
    fun `agrees with canonicalizeAddonUrl, the stored identity form`() {
        listOf(
            "https://a.example/cfg/",
            "https://a.example/cfg/?config=x89+/",
            "https://a.example/cfg/manifest.json?config=x89+/",
            "https://a.example/?a=1?b=/",
        ).forEach { url ->
            val canonical = canonicalizeAddonUrl(url)
            val (path, query) = splitAddonBaseUrl(canonical)
            assertEquals(url, canonical, path + query)
        }
    }
}
