package com.nuvio.tv.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the broken-poster blacklist semantics: entries skip candidate artwork
 * until the TTL passes, and an overflow purges only expired entries so steady
 * state stays bounded without blacklisting live URLs.
 */
class ExpiringUrlBlacklistTest {

    @Test
    fun `added urls are contained until the ttl passes`() {
        var now = 1_000L
        val blacklist = ExpiringUrlBlacklist(ttlMs = 1_000L, maxEntries = 10, nowMs = { now })
        blacklist.add("https://cdn/a.jpg")
        assertTrue("https://cdn/a.jpg" in blacklist)
        now = 1_999L
        assertTrue("https://cdn/a.jpg" in blacklist)
        now = 2_001L
        assertFalse("https://cdn/a.jpg" in blacklist)
    }

    @Test
    fun `unknown and null urls are never contained`() {
        val blacklist = ExpiringUrlBlacklist(ttlMs = 1_000L, maxEntries = 10, nowMs = { 0L })
        assertFalse("https://cdn/never-added.jpg" in blacklist)
        assertFalse(null in blacklist)
    }

    @Test
    fun `overflow purges only entries past the ttl`() {
        var now = 0L
        val blacklist = ExpiringUrlBlacklist(ttlMs = 100L, maxEntries = 3, nowMs = { now })
        blacklist.add("a")
        blacklist.add("b")
        now = 90L
        blacklist.add("c")
        now = 95L
        // Overflow with nothing expired yet: everything stays, nothing evicted.
        blacklist.add("d")
        assertTrue("a" in blacklist)
        now = 150L
        // Overflow: cutoff is 50, so a and b (added at 0) are purged; c and d stay.
        blacklist.add("e")
        assertFalse("a" in blacklist)
        assertFalse("b" in blacklist)
        assertTrue("c" in blacklist)
        assertTrue("d" in blacklist)
        assertTrue("e" in blacklist)
        now = 191L
        // Overflow again: cutoff is 91, so c (added at 90) drops; d survives.
        blacklist.add("f")
        assertFalse("c" in blacklist)
        assertTrue("d" in blacklist)
        assertTrue("e" in blacklist)
        assertTrue("f" in blacklist)
    }

    @Test
    fun `re-adding refreshes the ttl clock`() {
        var now = 0L
        val blacklist = ExpiringUrlBlacklist(ttlMs = 100L, maxEntries = 10, nowMs = { now })
        blacklist.add("x")
        now = 90L
        blacklist.add("x")
        now = 170L
        // First add would have expired at 100; the refresh at 90 moves it to 190.
        assertTrue("x" in blacklist)
        now = 191L
        assertFalse("x" in blacklist)
    }
}
