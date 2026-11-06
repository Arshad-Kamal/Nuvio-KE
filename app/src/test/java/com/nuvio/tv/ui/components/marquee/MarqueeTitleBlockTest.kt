package com.nuvio.tv.ui.components.marquee

import org.junit.Assert.assertEquals
import org.junit.Test

/** The shared title block (U5g meta line, U6i hand-off eyebrow) and the hand-off percentage. */
class MarqueeTitleBlockTest {

    @Test
    fun `year runtime and two genres in mockup order`() {
        assertEquals(
            listOf("2012", "12m", "Sci-Fi", "Short"),
            titleMetaParts(year = "2012", runtimeMinutes = 12, genres = "Sci-Fi • Short • Drama")
        )
    }

    @Test
    fun `route genres split on commas and long runtimes read in hours`() {
        assertEquals(
            listOf("2015", "1h 32m", "Animation", "Comedy"),
            titleMetaParts(year = " 2015 ", runtimeMinutes = 92, genres = "Animation, Comedy")
        )
    }

    @Test
    fun `missing, blank and zero-runtime parts are skipped`() {
        assertEquals(emptyList<String>(), titleMetaParts(year = " ", runtimeMinutes = 0, genres = " • "))
        assertEquals(listOf("Drama"), titleMetaParts(year = null, runtimeMinutes = null, genres = "Drama"))
    }

    @Test
    fun `hand-off eyebrow joins type, episode and status`() {
        assertEquals("Series · S1 E4 · Starting", titleBlockEyebrow("Series", "S1 E4", "Starting"))
        assertEquals("Movie · Starting", titleBlockEyebrow("Movie", null, "Starting"))
    }

    @Test
    fun `eyebrow drops blank parts and may be empty`() {
        assertEquals("Series", titleBlockEyebrow("Series", null, null))
        assertEquals("S1 E1", titleBlockEyebrow(" ", "S1 E1", ""))
        assertEquals("", titleBlockEyebrow("", null, null))
    }

    @Test
    fun `loading percent rounds down and clamps`() {
        assertEquals(42, loadingPercent(0.429f))
        assertEquals(99, loadingPercent(0.999f))
        assertEquals(100, loadingPercent(1.4f))
        assertEquals(0, loadingPercent(-0.2f))
    }
}
