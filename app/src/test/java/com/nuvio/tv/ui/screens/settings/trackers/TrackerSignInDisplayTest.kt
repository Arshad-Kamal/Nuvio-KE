package com.nuvio.tv.ui.screens.settings.trackers

import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec 104 B1/B2: the address the sign-in shows for typing the code by hand. */
class TrackerSignInDisplayTest {

    @Test
    fun `the address drops its scheme, www and trailing slash`() {
        assertEquals("simkl.com/pin", displayAddress("https://simkl.com/pin/"))
        assertEquals("mdblist.com/device", displayAddress(" https://www.mdblist.com/device "))
        assertEquals("example.test/pin", displayAddress("http://example.test/pin"))
    }
}
