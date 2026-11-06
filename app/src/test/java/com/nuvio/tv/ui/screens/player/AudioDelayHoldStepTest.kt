package com.nuvio.tv.ui.screens.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.nuvio.tv.core.player.AUDIO_DELAY_HOLD_FAST_STEP_MS
import com.nuvio.tv.core.player.AUDIO_DELAY_STEP_MS
import com.nuvio.tv.ui.components.HoldState
import com.nuvio.tv.ui.components.consumeClick
import com.nuvio.tv.ui.components.runHoldRepeat

/** T21 (P18): held +/- repeat and the click that ends a hold. */
@OptIn(ExperimentalCoroutinesApi::class)
class AudioDelayHoldStepTest {

    @Test
    fun tapStepUntilTwoSecondsHeldThenFastStep() {
        assertEquals(AUDIO_DELAY_STEP_MS, audioDelayHoldStepMs(360L))
        assertEquals(25, audioDelayHoldStepMs(1_999L))
        assertEquals(100, audioDelayHoldStepMs(2_000L))
        assertEquals(AUDIO_DELAY_HOLD_FAST_STEP_MS, audioDelayHoldStepMs(10_000L))
    }

    private class Recorder {
        val ticks = mutableListOf<Long>()
        var ends = 0
    }

    @Test
    fun shortTapNeitherTicksNorEndsAndItsClickRuns() = runTest {
        val state = HoldState()
        val rec = Recorder()
        val job = launch { runHoldRepeat(state, 360, 72, { rec.ticks += it }, { rec.ends++ }) }
        advanceTimeBy(200)
        assertTrue(state.consumeClick())
        job.cancel() // the pressed state drops a frame later
        runCurrent()
        assertTrue(rec.ticks.isEmpty())
        assertEquals(0, rec.ends)
    }

    @Test
    fun holdTicksOnCadenceAndReleaseClickIsSwallowedThenEndsOnce() = runTest {
        val state = HoldState()
        val rec = Recorder()
        launch { runHoldRepeat(state, 360, 72, { rec.ticks += it }, { rec.ends++ }) }
        advanceTimeBy(361)
        assertEquals(listOf(360L), rec.ticks)
        advanceTimeBy(144)
        assertEquals(listOf(360L, 432L, 504L), rec.ticks)
        assertFalse(state.consumeClick())
        advanceTimeBy(500) // no tick after the release click, even before cancellation
        assertEquals(3, rec.ticks.size)
        assertEquals(1, rec.ends)
    }

    @Test
    fun limitHandOverEndsOnCancelAndTheOtherButtonSwallowsTheKeyUp() = runTest {
        val state = HoldState()
        val rec = Recorder()
        val job = launch { runHoldRepeat(state, 360, 72, { rec.ticks += it }, { rec.ends++ }) }
        advanceTimeBy(400)
        job.cancel() // pressed button disabled at the limit
        runCurrent()
        assertEquals(1, rec.ends)
        assertFalse(state.consumeClick()) // key-up lands on the other button of the pair
        assertTrue(state.consumeClick()) // the next tap there works
    }

    @Test
    fun freshPressClearsASwallowLeftBehind() = runTest {
        val state = HoldState().apply { swallowNextClick = true }
        val job = launch { runHoldRepeat(state, 360, 72, {}, {}) }
        runCurrent()
        assertTrue(state.consumeClick())
        job.cancel()
    }
}
