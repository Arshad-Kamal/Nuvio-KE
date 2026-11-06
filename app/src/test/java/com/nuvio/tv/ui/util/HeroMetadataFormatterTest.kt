package com.nuvio.tv.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T9 (0.8.12 relay): TMDB reports unknown runtimes as 0, not null; formatting that
 * produced a literal "0m" in the metadata row. Zero is now the honest null.
 */
class HeroMetadataFormatterTest {

    @Test
    fun `zero runtime formats to null not 0m`() {
        assertNull(formatHeroRuntime("0"))
        assertNull(formatHeroRuntime("0h 0m"))
        assertNull(formatHeroRuntime("00:00"))
    }

    @Test
    fun `positive runtimes still format`() {
        assertEquals("45m", formatHeroRuntime("45"))
        assertEquals("2h 22m", formatHeroRuntime("142"))
        assertEquals("1h 30m", formatHeroRuntime("1h 30m"))
        assertEquals("1h 45m", formatHeroRuntime("1:45"))
        assertEquals("2h", formatHeroRuntime("02:00"))
        assertEquals("1h 30m", formatHeroRuntime("1:30:00"))
    }

    @Test
    fun `overlong clock runtime passes through instead of throwing`() {
        assertEquals("99999999999:00", formatHeroRuntime("99999999999:00"))
    }

    @Test
    fun `unparseable runtime passes through`() {
        assertEquals("n/a", formatHeroRuntime("n/a"))
    }
}
