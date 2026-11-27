package com.nuvio.tv.core.tracking

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingScrobbleDispatchTest {

    @Test
    fun `one tracker failing does not stop the others`() = runTest {
        val broken = FakeScrobbler(TrackingProviderId.MDBLIST, failure = IllegalStateException("503"))
        val fine = FakeScrobbler(TrackingProviderId.SIMKL)

        val failures = dispatchTrackingScrobble(listOf(broken, fine), TrackingScrobbleAction.START, movieEvent())

        assertEquals(listOf(TrackingProviderId.MDBLIST), failures.map { it.providerId })
        assertEquals(listOf(TrackingScrobbleAction.START), fine.calls.map { it.first })
    }

    @Test
    fun `a tracker's own cancellation is its failure, not the caller's`() = runTest {
        val cancelled = FakeScrobbler(TrackingProviderId.MDBLIST, failure = CancellationException("account switched"))
        val fine = FakeScrobbler(TrackingProviderId.SIMKL)

        val failures = dispatchTrackingScrobble(listOf(cancelled, fine), TrackingScrobbleAction.PAUSE, movieEvent())

        assertEquals(listOf(TrackingProviderId.MDBLIST), failures.map { it.providerId })
        assertEquals(1, fine.calls.size)
    }

    @Test
    fun `a caller cancelled during a seek stop sends no start`() = runTest {
        val stopping = CompletableDeferred<Unit>()
        val restarting = object : TrackingScrobbler {
            override val providerId = TrackingProviderId.SIMKL
            override val seekScrobblePolicy = TrackingSeekScrobblePolicy.STOP_AND_RESTART
            override suspend fun scrobble(action: TrackingScrobbleAction, event: TrackingScrobbleEvent) {
                stopping.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
        }
        var restartAsked = false
        val job = launch {
            dispatchTrackingSeekRestart(listOf(restarting), movieEvent()) { restartAsked = true; movieEvent(70.0) }
        }
        stopping.await()
        job.cancel()
        job.join()

        // Without ensureActive the cancelled stop would read as the tracker's failure and the restart would run.
        assertEquals(false, restartAsked)
    }

    @Test
    fun `a cancelled caller rethrows instead of reporting a tracker failure`() = runTest {
        var outcome: Result<Exception?>? = null
        val job = launch {
            coroutineContext[kotlinx.coroutines.Job]!!.cancel()
            outcome = runCatching { trackerCallFailure { throw CancellationException("caller gone") } }
        }
        job.join()

        assertTrue(outcome!!.exceptionOrNull() is CancellationException)
    }

    @Test(expected = OutOfMemoryError::class)
    fun `an Error is never turned into a tracker failure`() = runTest {
        trackerCallFailure { throw OutOfMemoryError("test") }
    }

    @Test
    fun `skipped trackers get nothing`() = runTest {
        val mdb = FakeScrobbler(TrackingProviderId.MDBLIST)
        val simkl = FakeScrobbler(TrackingProviderId.SIMKL)

        dispatchTrackingScrobble(listOf(mdb, simkl), TrackingScrobbleAction.START, movieEvent(skipped = setOf(TrackingProviderId.SIMKL)))

        assertEquals(1, mdb.calls.size)
        assertTrue(simkl.calls.isEmpty())
    }

    @Test
    fun `a seek restarts only trackers that ask for it, stop then start`() = runTest {
        val restarting = FakeScrobbler(TrackingProviderId.SIMKL, seekScrobblePolicy = TrackingSeekScrobblePolicy.STOP_AND_RESTART)
        val plain = FakeScrobbler(TrackingProviderId.MDBLIST)

        dispatchTrackingSeekRestart(listOf(restarting, plain), movieEvent(30.0)) { movieEvent(70.0) }

        assertEquals(listOf(TrackingScrobbleAction.STOP, TrackingScrobbleAction.START), restarting.calls.map { it.first })
        assertEquals(listOf(30.0, 70.0), restarting.calls.map { it.second.progressPercent })
        assertTrue(plain.calls.isEmpty())
    }

    @Test
    fun `a throwing restart is that tracker's start failure, and the other tracker's seek goes on`() = runTest {
        val first = FakeScrobbler(TrackingProviderId.MDBLIST, seekScrobblePolicy = TrackingSeekScrobblePolicy.STOP_AND_RESTART)
        val second = FakeScrobbler(TrackingProviderId.SIMKL, seekScrobblePolicy = TrackingSeekScrobblePolicy.STOP_AND_RESTART)
        var calls = 0

        val failures = dispatchTrackingSeekRestart(listOf(first, second), movieEvent()) {
            if (calls++ == 0) error("bad metadata") else movieEvent(70.0)
        }

        assertEquals(listOf(TrackingScrobbleAction.START), failures.map { it.action })
        assertEquals(listOf(TrackingProviderId.MDBLIST), failures.map { it.providerId })
        val calledStarts = (first.calls + second.calls).count { it.first == TrackingScrobbleAction.START }
        assertEquals(1, calledStarts)
        assertEquals(2, (first.calls + second.calls).count { it.first == TrackingScrobbleAction.STOP })
    }

    @Test
    fun `a failure names the action that failed`() = runTest {
        val broken = FakeScrobbler(TrackingProviderId.SIMKL, seekScrobblePolicy = TrackingSeekScrobblePolicy.STOP_AND_RESTART,
            failure = IllegalStateException("500"))

        val failures = dispatchTrackingSeekRestart(listOf(broken), movieEvent()) { movieEvent(70.0) }

        assertEquals(listOf(TrackingScrobbleAction.STOP, TrackingScrobbleAction.START), failures.map { it.action })
    }

    @Test
    fun `a seek restart that returns null sends only the stop`() = runTest {
        val restarting = FakeScrobbler(TrackingProviderId.SIMKL, seekScrobblePolicy = TrackingSeekScrobblePolicy.STOP_AND_RESTART)

        dispatchTrackingSeekRestart(listOf(restarting), movieEvent()) { null }

        assertEquals(listOf(TrackingScrobbleAction.STOP), restarting.calls.map { it.first })
    }

    @Test
    fun `leaving while paused stops only trackers whose pause saves nothing`() = runTest {
        val needsStop = FakeScrobbler(TrackingProviderId.MDBLIST, stopsWhenLeftPaused = true)
        val savesOnPause = FakeScrobbler(TrackingProviderId.SIMKL)

        dispatchTrackingStopWhenLeftPaused(listOf(needsStop, savesOnPause), movieEvent())

        assertEquals(listOf(TrackingScrobbleAction.STOP), needsStop.calls.map { it.first })
        assertTrue(savesOnPause.calls.isEmpty())
    }

    @Test
    fun `the coordinator returns a failing tracker's failure with its action`() = runTest {
        val broken = FakeTrackingProvider(
            TrackingProviderId.SIMKL, connected = true,
            scrobbler = FakeScrobbler(TrackingProviderId.SIMKL, stopsWhenLeftPaused = true, failure = IllegalStateException("500"))
        )
        val coordinator = TrackingScrobbleCoordinator(TrackingProviderRegistry(setOf(broken)))

        assertEquals(listOf(TrackingScrobbleAction.PAUSE), coordinator.scrobble(TrackingScrobbleAction.PAUSE, movieEvent()).map { it.action })
        assertEquals(listOf(TrackingScrobbleAction.STOP), coordinator.scrobbleStopWhenLeftPaused(movieEvent()).map { it.action })
    }

    @Test
    fun `the coordinator reports to connected trackers only`() = runTest {
        val connected = FakeTrackingProvider(TrackingProviderId.MDBLIST, connected = true)
        val signedOut = FakeTrackingProvider(TrackingProviderId.SIMKL, connected = false)
        val coordinator = TrackingScrobbleCoordinator(TrackingProviderRegistry(setOf(connected, signedOut)))

        val failures = coordinator.scrobble(TrackingScrobbleAction.START, movieEvent())

        assertTrue(failures.isEmpty())
        assertEquals(1, (connected.scrobbler as FakeScrobbler).calls.size)
        assertTrue((signedOut.scrobbler as FakeScrobbler).calls.isEmpty())
    }
}
