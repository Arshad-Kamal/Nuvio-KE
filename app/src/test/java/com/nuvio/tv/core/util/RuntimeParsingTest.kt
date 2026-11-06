package com.nuvio.tv.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** One parser for addon runtime strings (Spec 21 B2: the data and ui copies merged). */
class RuntimeParsingTest {

    @Test
    fun `parses the common addon runtime forms`() {
        assertEquals(130, parseRuntimeMinutes("130"))
        assertEquals(130, parseRuntimeMinutes("130 min"))
        assertEquals(130, parseRuntimeMinutes("130min"))
        assertEquals(130, parseRuntimeMinutes("2h 10min"))
        assertEquals(130, parseRuntimeMinutes("2h10m"))
        assertEquals(60, parseRuntimeMinutes("1h"))
        assertEquals(45, parseRuntimeMinutes("45m"))
        assertEquals(45, parseRuntimeMinutes(" 45 Min "))
        assertEquals(92, parseRuntimeMinutes("1h 32m"))
        assertEquals(0, parseRuntimeMinutes("0"))
        assertEquals(90, parseRuntimeMinutes("90 mins"))
        assertEquals(90, parseRuntimeMinutes("1h30"))
        assertEquals(90, parseRuntimeMinutes("1.5h"))
        assertEquals(90, parseRuntimeMinutes("1.5 hours"))
        assertEquals(150, parseRuntimeMinutes("2 hours 30 minutes"))
    }

    @Test
    fun `clock forms read as hours and minutes`() {
        // The data copy read "1:30" as 1 minute (first digit run); the ui copy as 90.
        assertEquals(90, parseRuntimeMinutes("1:30"))
        assertEquals(92, parseRuntimeMinutes("1:32"))
        assertEquals(90, parseRuntimeMinutes("1:30:00"))
        assertEquals(0, parseRuntimeMinutes("00:00"))
        assertEquals(65, parseRuntimeMinutes("1:5"))
    }

    @Test
    fun `without a unit or clock form the first digit run wins`() {
        // The ui copy joined every digit ("90 / 95" -> 9095).
        assertEquals(90, parseRuntimeMinutes("90 / 95"))
        assertEquals(95, parseRuntimeMinutes("approx. 95"))
    }

    @Test
    fun `unknown runtime is null`() {
        assertNull(parseRuntimeMinutes(null))
        assertNull(parseRuntimeMinutes(""))
        assertNull(parseRuntimeMinutes("   "))
        assertNull(parseRuntimeMinutes("abc"))
        assertNull(parseRuntimeMinutes("N/A"))
        assertNull(parseRuntimeMinutes("99999999999:00"))
        assertNull(parseRuntimeMinutes("99999999999"))
        // Fit an Int alone but overflow once multiplied: null, not a wrapped number.
        assertNull(parseRuntimeMinutes("2000000000:00"))
        assertNull(parseRuntimeMinutes("40000000h"))
    }
}
