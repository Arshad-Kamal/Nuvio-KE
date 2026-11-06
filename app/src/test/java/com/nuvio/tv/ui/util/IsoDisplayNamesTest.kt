package com.nuvio.tv.ui.util

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class IsoDisplayNamesTest {

    @Test
    fun `country codes become names in the given locale`() {
        assertEquals("United States, United Kingdom", isoDisplayNames("US, GB", isCountry = true, Locale.US))
        assertEquals("Allemagne", isoDisplayNames("de", isCountry = true, Locale.FRANCE))
    }

    @Test
    fun `language codes become language names`() {
        assertEquals("English, Japanese", isoDisplayNames("en,ja", isCountry = false, Locale.US))
    }

    @Test
    fun `text that is not a nameable code stays as given`() {
        // Not a code; a three-letter country code (the platform names only two-letter regions);
        // an unassigned region the platform echoes back.
        assertEquals("Amsterdam, Netherlands", isoDisplayNames("Amsterdam, Netherlands", isCountry = true, Locale.US))
        assertEquals("USA", isoDisplayNames("USA", isCountry = true, Locale.US))
        assertEquals("QQ", isoDisplayNames("QQ", isCountry = true, Locale.US))
    }
}
