package com.nuvio.tv.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AmbientAccentTest {
    private val vibrant = 0xFF1DA3D1.toInt()
    private val darkVibrant = 0xFF0F5B37.toInt()
    private val lightVibrant = 0xFFB2DFFC.toInt()

    @Test
    fun vibrantSwatchWins() {
        assertEquals(
            AmbientAccent.normalise(vibrant),
            AmbientAccent.fromSwatches(vibrant, darkVibrant, lightVibrant)
        )
    }

    @Test
    fun fallsThroughDarkVibrantThenLightVibrant() {
        assertEquals(
            AmbientAccent.normalise(darkVibrant),
            AmbientAccent.fromSwatches(null, darkVibrant, lightVibrant)
        )
        assertEquals(
            AmbientAccent.normalise(lightVibrant),
            AmbientAccent.fromSwatches(null, null, lightVibrant)
        )
    }

    @Test
    fun noSwatchMeansNoAccent() {
        // Grey art (no vibrant swatch): the caller falls back to the palette Secondary.
        assertNull(AmbientAccent.fromSwatches(null, null, null))
    }

    @Test
    fun normaliseMatchesTheApprovedMockupValues() {
        // Values from the U1 Home mockup (sandbox u1-home-marquee): Cosmos and Tears of Steel art.
        assertRgbClose(0xFF5DC5E8.toInt(), AmbientAccent.normalise(0xFF1DA3D1.toInt()))
        assertRgbClose(0xFFDAA86C.toInt(), AmbientAccent.normalise(0xFFBE9C73.toInt()))
    }

    @Test
    fun normaliseCoversEveryHueBranch() {
        // Expected values computed independently (Python colorsys, HLS -> L .64, S max(s, .60)).
        // Red-max with blue > green: the negative operand only Float.mod keeps in [0, 360).
        assertRgbClose(0xFFE95E8D.toInt(), AmbientAccent.normalise(0xFFD11D5A.toInt()))
        // Green-max branch.
        assertRgbClose(0xFF5EE98D.toInt(), AmbientAccent.normalise(0xFF1DD15A.toInt()))
    }

    private fun assertRgbClose(expected: Int, actual: Color) {
        val a = actual.toArgb()
        listOf(16, 8, 0).forEach { shift ->
            val e = (expected shr shift) and 0xFF
            val got = (a shr shift) and 0xFF
            assertTrue("channel@$shift expected $e got $got", abs(e - got) <= 2)
        }
    }
}
