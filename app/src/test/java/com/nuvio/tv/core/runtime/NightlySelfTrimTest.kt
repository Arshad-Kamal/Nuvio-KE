package com.nuvio.tv.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * M3 (rescoped per derisk S5): the trim-day bucket flips at 04:00 local
 * (DST-safe), missed windows catch up on the next background, and the disk
 * cache trims only past 3/4 of its cap.
 */
class NightlySelfTrimTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Calendar =
        Calendar.getInstance().apply {
            clear()
            set(year, month, day, hour, minute, 0)
        }

    @Test
    fun `bucket flips at the trim hour`() {
        val before = at(2026, Calendar.AUGUST, 31, 3, 59)
        val after = at(2026, Calendar.AUGUST, 31, 4, 0)
        assertNotEquals(trimDayBucket(before), trimDayBucket(after))
    }

    @Test
    fun `before the trim hour belongs to the previous day's bucket`() {
        val lateNight = at(2026, Calendar.AUGUST, 31, 23, 59)
        val nextEarly = at(2026, Calendar.SEPTEMBER, 1, 0, 1)
        // 23:59 on the 31st and 00:01 on the 1st are both BEFORE 04:00 -> both
        // bucket to the 31st: a missed evening window still catches up once.
        assertEquals(trimDayBucket(lateNight), trimDayBucket(nextEarly))
    }

    @Test
    fun `each day gets a distinct bucket`() {
        val a = at(2026, Calendar.AUGUST, 30, 12, 0)
        val b = at(2026, Calendar.AUGUST, 31, 12, 0)
        assertNotEquals(trimDayBucket(a), trimDayBucket(b))
    }

    @Test
    fun `custom trim hour moves the flip`() {
        val cal = at(2026, Calendar.AUGUST, 31, 5, 0)
        // 05:00 with trimHour=6 still belongs to the previous bucket day
        val bucketWith6 = trimDayBucket(cal, trimHour = 6)
        val previousDayNoon = at(2026, Calendar.AUGUST, 30, 12, 0)
        assertEquals(trimDayBucket(previousDayNoon, trimHour = 6), bucketWith6)
    }

    @Test
    fun `disk cache trims only past three quarters of the cap`() {
        assertFalse(shouldTrimDiskCache(sizeBytes = 0, maxSizeBytes = 128L * 1024 * 1024))
        assertFalse(shouldTrimDiskCache(sizeBytes = 96L * 1024 * 1024, maxSizeBytes = 128L * 1024 * 1024))
        assertTrue(shouldTrimDiskCache(sizeBytes = 96L * 1024 * 1024 + 1, maxSizeBytes = 128L * 1024 * 1024))
        assertTrue(shouldTrimDiskCache(sizeBytes = 128L * 1024 * 1024, maxSizeBytes = 128L * 1024 * 1024))
    }

    @Test
    fun `unknown cache bounds never trim`() {
        assertFalse(shouldTrimDiskCache(sizeBytes = -1, maxSizeBytes = -1))
        assertFalse(shouldTrimDiskCache(sizeBytes = 100, maxSizeBytes = 0))
    }

    @Test
    fun `fall-back DST night still buckets to the previous evening`() {
        // America/Edmonton falls back 2026-11-01 (02:00 -> 01:00). Both sides of
        // the repeat hour are before the trim hour -> both bucket to Oct 31.
        val tz = java.util.TimeZone.getTimeZone("America/Edmonton")
        fun edmonton(hour: Int, minute: Int) = Calendar.getInstance(tz).apply {
            clear()
            set(2026, Calendar.NOVEMBER, 1, hour, minute, 0)
        }
        assertEquals(trimDayBucket(edmonton(0, 30)), trimDayBucket(edmonton(3, 30)))
    }

    @Test
    fun `year rollover rolls the bucket year back too`() {
        // Jan 1 03:00 (before the trim hour) belongs to Dec 31 of the prior year;
        // the YEAR*1000 + DAY_OF_YEAR encoding must drop both together.
        assertEquals(
            trimDayBucket(at(2026, Calendar.DECEMBER, 31, 12, 0)),
            trimDayBucket(at(2027, Calendar.JANUARY, 1, 3, 0))
        )
    }
}
