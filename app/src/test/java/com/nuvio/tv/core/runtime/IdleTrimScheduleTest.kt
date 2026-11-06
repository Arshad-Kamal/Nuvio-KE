package com.nuvio.tv.core.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * L10-18: a foreground must cancel the nightly schedule at every step up to the
 * exit — including while the blocking disk trim runs on IO.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IdleTrimScheduleTest {

    private val delayMs = 10L * 60 * 1000

    private class Harness(testScope: TestScope, delayMs: Long, trim: (Harness) -> Boolean) {
        var trims = 0
        var exits = 0
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScope.testScheduler))
        val schedule = IdleTrimSchedule(
            scope = scope,
            idleDelayMs = delayMs,
            ioDispatcher = StandardTestDispatcher(testScope.testScheduler),
            trimOnce = { trims++; trim(this) },
            exit = { exits++ }
        )
    }

    @Test
    fun `idle delay elapses then trims and exits`() = runTest {
        val h = Harness(this, delayMs) { true }
        h.schedule.onBackgrounded()
        advanceUntilIdle()
        assertEquals(1, h.trims)
        assertEquals(1, h.exits)
        h.scope.cancel()
    }

    @Test
    fun `foreground inside the idle delay cancels the trim`() = runTest {
        val h = Harness(this, delayMs) { true }
        h.schedule.onBackgrounded()
        advanceTimeBy(delayMs / 2)
        h.schedule.onForegrounded()
        advanceUntilIdle()
        assertEquals(0, h.trims)
        assertEquals(0, h.exits)
        h.scope.cancel()
    }

    @Test
    fun `foreground during the trim skips the exit`() = runTest {
        // The user returns while diskCache.clear() runs: the trim finishes and
        // asks to exit, but the cancelled schedule must not kill the process.
        val h = Harness(this, delayMs) { harness ->
            harness.schedule.onForegrounded()
            true
        }
        h.schedule.onBackgrounded()
        advanceUntilIdle()
        assertEquals(1, h.trims)
        assertEquals(0, h.exits)
        h.scope.cancel()
    }

    @Test
    fun `re-background during the trim keeps the new schedule cancellable`() = runTest {
        // Foreground then background again while the first trim is still running:
        // the finishing first job must not clear the second job's reference, or
        // the next foreground could no longer cancel it.
        val h = Harness(this, delayMs) { harness ->
            if (harness.trims == 1) {
                harness.schedule.onForegrounded()
                harness.schedule.onBackgrounded()
            }
            true
        }
        h.schedule.onBackgrounded()
        advanceTimeBy(delayMs + delayMs / 2)
        assertEquals(1, h.trims)
        h.schedule.onForegrounded()
        advanceUntilIdle()
        assertEquals(1, h.trims)
        assertEquals(0, h.exits)
        h.scope.cancel()
    }
}
