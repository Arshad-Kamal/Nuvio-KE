package com.nuvio.tv.ui.screens.collection

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlHostPathTest {

    @Test
    fun `drops scheme, www, query and fragment`() {
        assertEquals(
            "elephantsdream.org/stills/proog.jpg",
            urlHostPath("https://www.elephantsdream.org/stills/proog.jpg?w=1280#top")
        )
    }

    @Test
    fun `keeps a bare host and trims a trailing slash`() {
        assertEquals("example.com", urlHostPath(" http://example.com/ "))
        assertEquals("example.com/a", urlHostPath("example.com/a"))
    }
}
