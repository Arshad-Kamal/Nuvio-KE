package com.nuvio.tv.core.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 33 D6: the hold's three ways out (runSkippable). Back cancels only the test and the start
 * goes on; cancelling the start itself stops the test with it; a failing test reaches the caller,
 * whose catch lets the film play.
 */
class NetworkTestHoldSkipTest {

    @Test
    fun `a finished test returns its result`() = runTest {
        assertEquals(42, runSkippable(register = {}) { 42 })
    }

    @Test
    fun `Back cancels only the test, the caller carries on`() = runTest {
        var testJob: Job? = null
        val result = CompletableDeferred<Int?>()
        val caller = launch {
            result.complete(runSkippable(register = { testJob = it }) { awaitCancellation() })
        }
        runCurrent()
        testJob!!.cancel()
        runCurrent()
        assertNull(result.await())
        assertTrue(caller.isCompleted && !caller.isCancelled)
    }

    @Test
    fun `cancelling the caller cancels the test and propagates`() = runTest {
        var testJob: Job? = null
        var reachedAfter = false
        val caller = launch {
            runSkippable(register = { testJob = it }) { awaitCancellation() }
            reachedAfter = true
        }
        runCurrent()
        caller.cancel()
        runCurrent()
        assertTrue(caller.isCancelled)
        assertTrue(testJob!!.isCancelled)
        assertTrue(!reachedAfter)
    }

    @Test
    fun `a failing test is rethrown, not read as a skip`() = runTest {
        val thrown = runCatching { runSkippable<Int>(register = {}) { error("probe failed") } }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
    }
}
