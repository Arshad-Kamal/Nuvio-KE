package com.nuvio.tv.core.player

import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins for the dead-source failover's list wait (2026-09-03 404 report): the
 * old single-predicate wait read the PRE-load state as "completed with
 * nothing" and surrendered in the same millisecond it started the load, while
 * the scrape landed seconds later with the failover candidates.
 */
class DeadSourceFailoverWaitTest {

    @Test
    fun `pre-load idle state is not read as completed-with-nothing`() = runTest {
        // Defensive pin: the wait may start before the load marks itself as
        // loading (the 2026-09-03 sequence; since T22 loadSourceStreams sets
        // loading before it returns, see the next test). The idle state must
        // not read as completed-with-nothing.
        val state = MutableStateFlow(PlayerUiState())
        val await = launchAwait(state)

        advanceTimeBy(100)
        assertTrue(await.isActive)

        state.value = state.value.copy(isLoadingSourceStreams = true)
        advanceTimeBy(100)
        assertTrue(await.isActive)

        state.value = state.value.copy(
            isLoadingSourceStreams = false,
            sourceAllStreams = listOf(stream("alive"))
        )
        val loaded = await.await()

        assertNotNull(loaded)
        assertEquals(1, loaded!!.sourceAllStreams.size)
    }

    @Test
    fun `wait started in the loading state returns once the list lands`() = runTest {
        // Production shape since T22: loadSourceStreams switches the key, clears the
        // stale list and sets loading before the wait starts.
        val state = MutableStateFlow(PlayerUiState(isLoadingSourceStreams = true))
        val await = launchAwait(state)

        advanceTimeBy(100)
        assertTrue(await.isActive)

        state.value = state.value.copy(
            isLoadingSourceStreams = false,
            sourceAllStreams = listOf(stream("alive"))
        )

        assertEquals(1, await.await()?.sourceAllStreams?.size)
    }

    @Test
    fun `cached list present without a load returns immediately`() = runTest {
        val state = MutableStateFlow(
            PlayerUiState(sourceAllStreams = listOf(stream("cached")))
        )
        val await = launchAwait(state)

        val loaded = await.await()

        assertEquals(1, loaded?.sourceAllStreams?.size)
    }

    @Test
    fun `load completing empty ends the wait with the empty list`() = runTest {
        val state = MutableStateFlow(PlayerUiState())
        val await = launchAwait(state)

        state.value = state.value.copy(isLoadingSourceStreams = true)
        advanceTimeBy(100)
        state.value = state.value.copy(
            isLoadingSourceStreams = false,
            sourceStreamsError = "all addons failed"
        )
        val loaded = await.await()

        // A genuinely completed-empty scrape is a legitimate verdict — the
        // caller surfaces the error. It must NOT hang until timeout.
        assertNotNull(loaded)
        assertTrue(loaded!!.sourceAllStreams.isEmpty())
        assertEquals("all addons failed", loaded.sourceStreamsError)
    }

    @Test
    fun `list landing while still loading returns immediately with the partial list`() = runTest {
        // Production shape: the scrape emits per addon, so the list can go
        // non-empty while isLoadingSourceStreams is still true. The wait must
        // advance on the first available list, not wait for the full scrape
        // (the +7.4 s on-device failover depends on it).
        val state = MutableStateFlow(PlayerUiState())
        val await = launchAwait(state)

        state.value = state.value.copy(isLoadingSourceStreams = true)
        advanceTimeBy(100)
        assertTrue(await.isActive)
        state.value = state.value.copy(sourceAllStreams = listOf(stream("first-addon")))

        val loaded = await.await()

        assertEquals(1, loaded?.sourceAllStreams?.size)
    }

    @Test
    fun `load that never starts times out with null`() = runTest {
        val state = MutableStateFlow(PlayerUiState())
        val await = launchAwait(state, timeoutMs = 300)
        advanceTimeBy(1_000)

        assertNull(await.await())
    }

    private fun TestScope.launchAwait(
        state: MutableStateFlow<PlayerUiState>,
        timeoutMs: Long = 15_000
    ): Deferred<PlayerUiState?> {
        val deferred = async { awaitSourceListForFailover(state, timeoutMs) }
        // runCurrent, NOT advanceUntilIdle: idle-advance would run the
        // withTimeoutOrNull timer and complete the deferred with null.
        runCurrent()
        return deferred
    }

    private fun stream(id: String): Stream = Stream(
        name = id,
        title = null,
        description = null,
        url = "https://$id.example.test/a",
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = null,
        addonName = "Addon",
        addonLogo = null
    )
}
