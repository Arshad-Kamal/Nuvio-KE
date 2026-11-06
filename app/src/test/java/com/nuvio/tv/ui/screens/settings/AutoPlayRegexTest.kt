package com.nuvio.tv.ui.screens.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoPlayRegexTest {

    @Test
    fun `every preset can be saved`() {
        AUTO_PLAY_REGEX_PRESETS.forEach { (_, pattern) ->
            assertTrue(pattern, isSavableAutoPlayRegex(pattern))
        }
    }

    @Test
    fun `blank means no pattern and saves`() {
        assertTrue(isSavableAutoPlayRegex(""))
        assertTrue(isSavableAutoPlayRegex("   "))
    }

    @Test
    fun `a pattern that does not compile is refused`() {
        assertFalse(isSavableAutoPlayRegex("(4K|2160p"))
        assertFalse(isSavableAutoPlayRegex("[remux"))
    }
}
