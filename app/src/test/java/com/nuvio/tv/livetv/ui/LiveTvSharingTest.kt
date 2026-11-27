package com.nuvio.tv.livetv.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec 146: values derived from Live TV's stores are kept through a restart, dropped after an unload. */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveTvSharingTest {

    @Test
    fun `the held value survives a stop and goes once the resets move while stopped`() = runTest {
        val resets = MutableStateFlow(0)
        val upstream = MutableStateFlow(1)
        val state = upstream.stateIn(backgroundScope, whileSubscribedResetOn(5_000, resets), 0)

        var collector = launch { state.collect {} }
        runCurrent()
        assertEquals(1, state.value)
        collector.cancel()
        advanceTimeBy(6_000)
        runCurrent()
        assertEquals(1, state.value) // kept: a plain return shows it at once

        resets.value = 1
        runCurrent()
        assertEquals(0, state.value) // dropped to the initial value

        upstream.value = 2
        collector = launch { state.collect {} }
        runCurrent()
        assertEquals(2, state.value)
        collector.cancel()
        advanceTimeBy(6_000)
        runCurrent()
        assertEquals(2, state.value) // derived after the reset: kept again
    }

    @Test
    fun `a reset while started keeps what is derived after it, and the next one while stopped drops it`() = runTest {
        val resets = MutableStateFlow(0)
        val upstream = MutableStateFlow(1)
        val state = upstream.stateIn(backgroundScope, whileSubscribedResetOn(5_000, resets), 0)
        val collector = launch { state.collect {} }
        runCurrent()
        collector.cancel()
        advanceTimeBy(1_000) // within the stop timeout: still started
        resets.value = 1
        upstream.value = 3 // the view model re-derives from the unloaded stores meanwhile
        runCurrent()
        assertEquals(3, state.value)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(3, state.value)
        resets.value = 2
        runCurrent()
        assertEquals(0, state.value)
    }
}
