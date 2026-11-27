package com.nuvio.tv.core.tracking

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackingRefreshGateTest {

    @Test
    fun `waiters share one refresh that started after they arrived`() = runTest {
        val gate = TrackingRefreshGate()
        val release = CompletableDeferred<Unit>()
        val ran = mutableListOf<String>()

        val first = launch { gate.runIfNeeded(1L, { true }) { ran += "first"; release.await() } }
        testScheduler.runCurrent()
        val second = launch { gate.runIfNeeded(1L, { true }) { ran += "second" } }
        val third = launch { gate.runIfNeeded(1L, { true }) { ran += "third" } }
        testScheduler.runCurrent()
        release.complete(Unit)
        first.join(); second.join(); third.join()

        // The refresh in flight when they arrived may hold older data, so one of them runs; the other shares it.
        assertEquals(listOf("first", "second"), ran)
    }

    @Test
    fun `a waiting caller for another profile still runs`() = runTest {
        val gate = TrackingRefreshGate()
        val release = CompletableDeferred<Unit>()
        val ran = mutableListOf<Long>()

        val first = launch { gate.runIfNeeded(1L, { true }) { ran += 1L; release.await() } }
        testScheduler.runCurrent()
        val second = launch { gate.runIfNeeded(1L, { true }) { ran += 10L; release.await() } }
        val third = launch { gate.runIfNeeded(2L, { true }) { ran += 2L } }
        testScheduler.runCurrent()
        release.complete(Unit)
        first.join(); second.join(); third.join()

        assertEquals(listOf(1L, 10L, 2L), ran)
    }

    @Test
    fun `the staleness rule is read after the run ahead finished`() = runTest {
        val gate = TrackingRefreshGate()
        val release = CompletableDeferred<Unit>()
        var fresh = false
        var waiterRan = false

        val older = launch { gate.runIfNeeded(1L, { true }) { release.await(); fresh = true } }
        testScheduler.runCurrent()
        // Arrived during the older run, so the gate does not skip it; only its own rule can.
        val waiter = launch { gate.runIfNeeded(1L, { !fresh }) { waiterRan = true } }
        testScheduler.runCurrent()
        release.complete(Unit)
        older.join()
        waiter.join()

        assertEquals(false, waiterRan)
    }

    @Test
    fun `sequential calls run again and the staleness rule can say no`() = runTest {
        val gate = TrackingRefreshGate()
        var runs = 0

        gate.runIfNeeded(1L, { true }) { runs++ }
        gate.runIfNeeded(1L, { true }) { runs++ }
        gate.runIfNeeded(1L, { false }) { runs++ }

        assertEquals(2, runs)
    }

    @Test
    fun `a failed refresh counts for its waiters, a cancelled one does not`() = runTest {
        val gate = TrackingRefreshGate()
        val ran = mutableListOf<String>()

        // Failed: a waiter that arrived before it started shares it.
        val failRelease = CompletableDeferred<Unit>()
        val blocker = launch { gate.runIfNeeded(1L, { true }) { failRelease.await() } }
        testScheduler.runCurrent()
        val failing = launch { runCatching { gate.runIfNeeded(1L, { true }) { ran += "failing"; error("offline") } } }
        val afterFail = launch { gate.runIfNeeded(1L, { true }) { ran += "afterFail" } }
        testScheduler.runCurrent()
        failRelease.complete(Unit)
        blocker.join(); failing.join(); afterFail.join()
        assertEquals(listOf("failing"), ran)

        // Cancelled: the waiter runs itself.
        ran.clear()
        val cancelRelease = CompletableDeferred<Unit>()
        val blocker2 = launch { gate.runIfNeeded(1L, { true }) { cancelRelease.await() } }
        testScheduler.runCurrent()
        val cancelled = launch { gate.runIfNeeded(1L, { true }) { ran += "cancelled"; CompletableDeferred<Unit>().await() } }
        val afterCancel = launch { gate.runIfNeeded(1L, { true }) { ran += "afterCancel" } }
        testScheduler.runCurrent()
        cancelRelease.complete(Unit)
        testScheduler.runCurrent()
        cancelled.cancel()
        blocker2.join(); cancelled.join(); afterCancel.join()
        assertEquals(listOf("cancelled", "afterCancel"), ran)
    }
}
