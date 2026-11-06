package com.nuvio.tv.ui.screens.cast

import androidx.compose.ui.unit.dp
import com.nuvio.tv.ui.theme.NuvioMarquee
import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec 29e band geometry on the 960x540 canvas (sandbox u9-cast-marquee stages 3, 5). */
class PersonBandGeometryTest {

    private val marquee = NuvioMarquee.tokens

    @Test
    fun `portrait band rises to the anchor, the hero above its tabs`() {
        val geometry = personBandGeometry(540.dp, landscape = false, marquee)
        // Cards 326..494, tabs at 288: the band moves up 436 - 288.
        assertEquals(112.dp, geometry.cardWidth)
        assertEquals(148.dp, geometry.travel)
        // Hero bottom at 288 - 24 = 264; room up to y 80.
        assertEquals(276.dp, geometry.heroBottomInset)
        assertEquals(184.dp, geometry.heroMaxHeight)
    }

    @Test
    fun `landscape cards are shorter, so the band travels less`() {
        val geometry = personBandGeometry(540.dp, landscape = true, marquee)
        // Cards 366..494, tabs at 328.
        assertEquals(228.dp, geometry.cardWidth)
        assertEquals(108.dp, geometry.travel)
        assertEquals(236.dp, geometry.heroBottomInset)
    }

    @Test
    fun `a tall window never moves the band down, a short one leaves the hero no room`() {
        assertEquals(0.dp, personBandGeometry(1000.dp, landscape = false, marquee).travel)
        assertEquals(0.dp, personBandGeometry(200.dp, landscape = false, marquee).heroMaxHeight)
    }
}
