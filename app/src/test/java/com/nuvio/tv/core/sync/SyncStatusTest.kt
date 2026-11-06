package com.nuvio.tv.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncStatusTest {

    private val allOk = CorePullStep.entries.associateWith { true }

    @Test
    fun `pull succeeds only when every core step succeeded`() {
        assertTrue(pullSucceeded(allOk))
        CorePullStep.entries.forEach { step ->
            assertFalse(step.name, pullSucceeded(allOk + (step to false)))
            assertFalse("missing $step", pullSucceeded(allOk - step))
        }
        assertFalse(pullSucceeded(emptyMap()))
    }

    @Test
    fun `never synced when no stored time`() {
        assertEquals(SyncAge.Never, syncAge(nowMs = NOW, lastOkAtMs = null))
        assertEquals(SyncAge.Never, syncAge(nowMs = NOW, lastOkAtMs = 0L))
    }

    @Test
    fun `age buckets round down`() {
        assertEquals(SyncAge.JustNow, syncAge(NOW, NOW))
        assertEquals(SyncAge.JustNow, syncAge(NOW, NOW - 59_999))
        assertEquals(SyncAge.Minutes(1), syncAge(NOW, NOW - 60_000))
        assertEquals(SyncAge.Minutes(59), syncAge(NOW, NOW - HOUR + 1))
        assertEquals(SyncAge.Hours(1), syncAge(NOW, NOW - HOUR))
        assertEquals(SyncAge.Hours(23), syncAge(NOW, NOW - DAY + 1))
        assertEquals(SyncAge.Days(1), syncAge(NOW, NOW - DAY))
        assertEquals(SyncAge.Days(40), syncAge(NOW, NOW - 40 * DAY))
    }

    @Test
    fun `future time after a clock change reads as just now`() {
        assertEquals(SyncAge.JustNow, syncAge(NOW, NOW + 5 * DAY))
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
    }
}
