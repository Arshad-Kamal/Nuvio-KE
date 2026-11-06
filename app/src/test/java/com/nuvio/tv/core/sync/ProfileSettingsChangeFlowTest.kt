package com.nuvio.tv.core.sync

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * L9-03: the settings push must only fire for an edit observed within one profile's
 * subscription, tagged with that profile; a profile switch is a baseline, not an edit.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileSettingsChangeFlowTest {
    private val activeProfileId = MutableStateFlow(1)
    private val signatures = mapOf(
        1 to MutableSharedFlow<String>(replay = 1),
        2 to MutableSharedFlow<String>(replay = 1)
    )

    @Test
    fun `profile switch and cold start emit no change`() = runTest(UnconfinedTestDispatcher()) {
        signatures.getValue(1).emit("p1-local")
        signatures.getValue(2).emit("p2-stale-local")
        val changes = mutableListOf<Pair<Int, String>>()
        val job = launch {
            activeProfileId.perProfileLocalChanges { signatures.getValue(it) }.collect { changes += it }
        }

        activeProfileId.value = 2
        runCurrent()
        activeProfileId.value = 1
        runCurrent()

        assertEquals(emptyList<Pair<Int, String>>(), changes)
        job.cancel()
    }

    @Test
    fun `an edit after a switch is pushed for the profile it happened on`() = runTest(UnconfinedTestDispatcher()) {
        signatures.getValue(1).emit("p1-local")
        signatures.getValue(2).emit("p2-baseline")
        val changes = mutableListOf<Pair<Int, String>>()
        val job = launch {
            activeProfileId.perProfileLocalChanges { signatures.getValue(it) }.collect { changes += it }
        }

        activeProfileId.value = 2
        runCurrent() // profile 2's subscription is live before its data changes
        signatures.getValue(2).emit("p2-baseline") // DataStore re-emits identical data
        signatures.getValue(2).emit("p2-edited")
        runCurrent()

        assertEquals(listOf(2 to "p2-edited"), changes)
        job.cancel()
    }
}
