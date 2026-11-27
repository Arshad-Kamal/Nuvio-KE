package com.nuvio.tv.core.report

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Spec 116 D3.6: the 24 h cut of a threadtime dump. */
class LogcatParserTest {

    private val zone: ZoneId = ZoneOffset.UTC

    private fun at(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant()

    @Test
    fun `keeps the last 24 hours and lines that follow a kept line`() {
        val log = """
            --------- beginning of main
            11-13 19:00:00.000  1  1 I Old: dropped
            11-13 21:00:00.001  1  1 I New: kept
            	at wrapped.continuation(Kept.kt:1)
            11-14 20:59:59.000  1  1 I Now: kept
        """.trimIndent()
        assertEquals(
            "11-13 21:00:00.001  1  1 I New: kept\n\tat wrapped.continuation(Kept.kt:1)\n11-14 20:59:59.000  1  1 I Now: kept",
            LogcatParser.lastDay(log, at("2026-11-14T21:00:00"), zone),
        )
    }

    @Test
    fun `a line dated after now belongs to the previous year`() {
        val log = "12-31 23:30:00.000  1  1 I Eve: kept\n01-01 00:10:00.000  1  1 I Day: kept\n12-30 10:00:00.000  1  1 I Old: dropped"
        assertEquals(
            "12-31 23:30:00.000  1  1 I Eve: kept\n01-01 00:10:00.000  1  1 I Day: kept",
            LogcatParser.lastDay(log, at("2027-01-01T00:20:00"), zone),
        )
    }

    @Test
    fun `the cut uses the injected zone, and a line up to an hour ahead stays this year`() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        val now = LocalDateTime.parse("2026-11-15T06:00:00").atZone(tokyo).toInstant()
        val log = "11-14 05:59:00.000  1  1 I Old: dropped\n11-14 06:00:00.000  1  1 I Edge: kept\n" +
            "11-15 06:59:00.000  1  1 I Skew: kept"
        assertEquals(
            "11-14 06:00:00.000  1  1 I Edge: kept\n11-15 06:59:00.000  1  1 I Skew: kept",
            LogcatParser.lastDay(log, now, tokyo),
        )
    }

    @Test
    fun `an impossible date counts as old`() {
        assertEquals("", LogcatParser.lastDay("02-29 10:00:00.000  1  1 I Leap: x", at("2027-03-01T09:00:00"), zone))
    }
}
