package com.nuvio.tv.ui.screens.collection

import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * B19: FolderDetailViewModel.rebuildFollowLayoutState used to launch an untracked build per
 * call, so overlapping builds wrote the shared row cache concurrently and an older snapshot
 * could publish over a newer one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LatestWinsSerialBuildsTest {

    @Test
    fun `queued build superseded before it starts never publishes`() = runTest {
        val builds = LatestWinsSerialBuilds(this, StandardTestDispatcher(testScheduler))
        val published = mutableListOf<String>()

        builds.launch(build = { "first" }, publish = { published += it })
        builds.launch(build = { "second" }, publish = { published += it })
        advanceUntilIdle()

        assertEquals(listOf("second"), published)
    }

    @Test
    fun `build superseded while running never publishes its result`() = runTest {
        val builds = LatestWinsSerialBuilds(this, StandardTestDispatcher(testScheduler))
        val published = mutableListOf<String>()

        builds.launch(
            build = {
                // A newer request arrives while this build is mid-way (non-suspending work).
                builds.launch(build = { "newer" }, publish = { published += it })
                "stale"
            },
            publish = { published += it }
        )
        advanceUntilIdle()

        assertEquals(listOf("newer"), published)
    }

    // Pins cancellation of the superseded build (launch cancels the previous job). The identity
    // check under the lock guards a different window, between ensureActive() and publish, which
    // has no suspension point: no deterministic test can land a newer launch inside it without
    // a test-only hook in production code, so it is covered by reasoning only.
    @Test
    fun `build cancelled while suspended never resumes or publishes once a newer launch arrives`() = runTest {
        val builds = LatestWinsSerialBuilds(this, StandardTestDispatcher(testScheduler))
        val published = mutableListOf<String>()
        val steps = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()

        builds.launch(
            build = {
                steps += "first:start"
                gate.await()
                steps += "first:after-suspend"
                "first"
            },
            publish = { published += it }
        )
        runCurrent()
        // The first build is parked on the gate, which frees the single worker.
        assertEquals(listOf("first:start"), steps)

        builds.launch(build = { steps += "second"; "second" }, publish = { published += it })
        // Release the gate: the newer launch already cancelled the first build, so its
        // gate.await() throws instead of resuming.
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("first:start", "second"), steps)
        assertEquals(listOf("second"), published)
    }

    @Test
    fun `null build publishes nothing`() = runTest {
        val builds = LatestWinsSerialBuilds(this, StandardTestDispatcher(testScheduler))
        val published = mutableListOf<String>()

        builds.launch<String>(build = { null }, publish = { published += it })
        advanceUntilIdle()

        assertEquals(emptyList<String>(), published)
    }

    @Test
    fun `builds never overlap on a multi-threaded base dispatcher and the latest publishes last`() =
        runBlocking {
            val scope = this
            val builds = LatestWinsSerialBuilds(scope, Dispatchers.Default)
            val running = AtomicInteger(0)
            val maxRunning = AtomicInteger(0)
            val published = Collections.synchronizedList(mutableListOf<Int>())
            var last: Job? = null

            repeat(200) { request ->
                last = builds.launch(
                    build = {
                        val now = running.incrementAndGet()
                        maxRunning.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                        // Non-suspending work, like buildModernHomePresentation.
                        Thread.sleep(1)
                        running.decrementAndGet()
                        request
                    },
                    publish = { published += it }
                )
            }
            last!!.join()

            assertEquals(1, maxRunning.get())
            assertEquals(199, published.last())
        }
}
