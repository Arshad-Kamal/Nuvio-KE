package com.nuvio.tv.ui.components.marquee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** T1: the Detail credit line's award is the first sentence of the addon's awards text. */
class TopAwardTest {

    @Test
    fun `the first sentence wins, without its full stop`() {
        assertEquals("Won 2 Oscars", topAward("Won 2 Oscars. 164 wins & 165 nominations total"))
        assertEquals("Won 16 Primetime Emmys", topAward("Won 16 Primetime Emmys. 172 wins & 269 nominations total."))
        assertEquals("3 wins & 5 nominations total", topAward("3 wins & 5 nominations total."))
    }

    @Test
    fun `blank or missing awards show nothing`() {
        assertNull(topAward(null))
        assertNull(topAward("  "))
        assertNull(topAward("."))
    }
}
