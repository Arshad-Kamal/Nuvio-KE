package com.nuvio.tv.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Contract tests for the addon identity key. The 2026-08-26 review caught the
 * trailing-slash trim running before the path/query split, corrupting keys
 * whose query values end in '/'.
 */
class AddonUrlUtilsTest {

    @Test
    fun `strips manifest suffix and trailing slash from path`() {
        assertEquals(
            "https://addon.example",
            canonicalizeAddonUrl("https://addon.example/manifest.json/")
        )
    }

    @Test
    fun `preserves query string byte-exact including trailing slash in value`() {
        assertEquals(
            "https://example.com?config=x89+/",
            canonicalizeAddonUrl("https://example.com/manifest.json?config=x89+/")
        )
    }

    @Test
    fun `trailing slash before query still yields same key as without`() {
        assertEquals(
            canonicalizeAddonUrl("https://addon.example/manifest.json"),
            canonicalizeAddonUrl("https://addon.example/manifest.json/")
        )
        assertEquals(
            canonicalizeAddonUrl("https://addon.example/manifest.json?config=x89+/"),
            canonicalizeAddonUrl("https://addon.example/manifest.json/?config=x89+/")
        )
    }

    @Test
    fun `query survives when path has manifest suffix`() {
        assertEquals(
            "https://addon.example?config=abc",
            canonicalizeAddonUrl("https://addon.example/manifest.json?config=abc")
        )
    }

    @Test
    fun `plain url is unchanged`() {
        assertEquals("https://a.b/c", canonicalizeAddonUrl("  https://a.b/c  "))
    }

    @Test
    fun `accepted scheme keeps http and https and rewrites stremio to https`() {
        assertEquals("http://a.b/manifest.json", withAcceptedAddonScheme("http://a.b/manifest.json"))
        assertEquals("https://a.b", withAcceptedAddonScheme("https://a.b"))
        assertEquals("https://a.b/manifest.json", withAcceptedAddonScheme("stremio://a.b/manifest.json"))
    }

    @Test
    fun `unaccepted schemes are null and matching is case-sensitive and untrimmed`() {
        listOf("ftp://a.b", "javascript:alert(1)", "file:///x", "HTTPS://a.b", "STREMIO://a.b", " https://a.b", "")
            .forEach { assertNull(it, withAcceptedAddonScheme(it)) }
    }

    @Test
    fun `match key folds whitespace, manifest suffix and case`() {
        assertEquals(
            addonUrlMatchKey("https://Addon.Example/cfg?k=V"),
            addonUrlMatchKey(" https://addon.example/cfg/manifest.json?k=v ")
        )
    }
}
