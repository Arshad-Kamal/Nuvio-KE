package com.nuvio.tv.core.player.autosync

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutoSyncFirstFrameGateTest {
    @Test
    fun returnsAtOnceWhenTheFirstFrameIsAlreadyRendered() = runTest {
        awaitAutoSyncFirstFrame(firstFrameRendered = { true })
        assertEquals(0L, currentTime)
    }

    @Test
    fun waitsUntilTheFirstFrameThenRunsWithinOnePoll() = runTest {
        var rendered = false
        var started = false
        launch {
            awaitAutoSyncFirstFrame(firstFrameRendered = { rendered })
            started = true
        }
        advanceTimeBy(5_000)
        runCurrent()
        assertFalse(started)

        rendered = true
        advanceTimeBy(AUTO_SYNC_FIRST_FRAME_POLL_MS)
        runCurrent()
        assertTrue(started)
    }

    @Test
    fun cancellingTheRunEndsTheWait() = runTest {
        val job = launch { awaitAutoSyncFirstFrame(firstFrameRendered = { false }) }
        advanceTimeBy(1_000)
        job.cancel()
        runCurrent()
        assertTrue(job.isCancelled && job.isCompleted)
    }
}
