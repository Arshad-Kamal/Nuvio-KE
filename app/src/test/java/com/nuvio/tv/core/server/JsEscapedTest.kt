package com.nuvio.tv.core.server

import org.junit.Assert.assertEquals
import org.junit.Test

/** The config pages' one JS-literal escaper (L10-42). */
class JsEscapedTest {

    @Test
    fun `escapes backslash before quote and newline`() {
        assertEquals("""it\'s \\ a\nb""", "it's \\ a\nb".jsEscaped())
        // The backslash goes first, so an escaped quote is not double-escaped.
        assertEquals("""\\\'""", "\\'".jsEscaped())
    }
}
