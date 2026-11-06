package com.nuvio.tv.ui.screens.home

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CwPrefetchGateTest {

    /** [startMs] > 0 models production's suspending start (the settings read before the prefetch). */
    private class Harness(scope: TestScope, initialTarget: String?, startMs: Long = 0L) {
        val targets = MutableStateFlow(initialTarget)
        val onTop = MutableStateFlow(true)
        val started = mutableListOf<String>()

        init {
            scope.backgroundScope.launch {
                collectPrefetchTargetsWhileOnTop(targets, onTop, DEBOUNCE) {
                    if (startMs > 0) delay(startMs)
                    started += it
                }
            }
        }
    }

    @Test
    fun `on top, a target starts after the debounce`() = runTest {
        val h = Harness(this, "A")
        advanceTimeBy(DEBOUNCE - 1); runCurrent()
        assertEquals(emptyList<String>(), h.started)
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf("A"), h.started)
    }

    @Test
    fun `a target that changes while away starts only on return`() = runTest {
        val h = Harness(this, "A")
        advanceTimeBy(DEBOUNCE); runCurrent()
        h.onTop.value = false
        h.targets.value = "B"
        advanceTimeBy(DEBOUNCE * 10); runCurrent()
        assertEquals(listOf("A"), h.started)
        h.onTop.value = true
        advanceTimeBy(DEBOUNCE); runCurrent()
        assertEquals(listOf("A", "B"), h.started)
    }

    @Test
    fun `an unchanged target does not start again on return`() = runTest {
        val h = Harness(this, "A")
        advanceTimeBy(DEBOUNCE); runCurrent()
        h.onTop.value = false; runCurrent()
        h.onTop.value = true
        advanceTimeBy(DEBOUNCE * 10); runCurrent()
        assertEquals(listOf("A"), h.started)
    }

    @Test
    fun `leaving during the debounce cancels the start, and return starts it`() = runTest {
        val h = Harness(this, "A")
        advanceTimeBy(DEBOUNCE / 2); runCurrent()
        h.onTop.value = false
        advanceTimeBy(DEBOUNCE * 10); runCurrent()
        assertEquals(emptyList<String>(), h.started)
        h.onTop.value = true
        advanceTimeBy(DEBOUNCE); runCurrent()
        assertEquals(listOf("A"), h.started)
    }

    @Test
    fun `leaving while the start itself is suspended does not mark it started`() = runTest {
        val h = Harness(this, "A", startMs = 100)
        advanceTimeBy(DEBOUNCE + 50); runCurrent()
        h.onTop.value = false
        advanceTimeBy(DEBOUNCE * 10); runCurrent()
        assertEquals(emptyList<String>(), h.started)
        h.onTop.value = true
        advanceTimeBy(DEBOUNCE + 100); runCurrent()
        assertEquals(listOf("A"), h.started)
    }

    @Test
    fun `a null target clears the memory, so the same target starts again (as before T27)`() = runTest {
        val h = Harness(this, "A")
        advanceTimeBy(DEBOUNCE); runCurrent()
        h.targets.value = null; runCurrent()
        h.targets.value = "A"
        advanceTimeBy(DEBOUNCE); runCurrent()
        assertEquals(listOf("A", "A"), h.started)
    }

    private companion object {
        const val DEBOUNCE = 500L
    }
}
